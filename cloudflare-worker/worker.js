/**
 * ============================================================================
 *  نظام موحّد لـ "إجمالي المستخدمين" و"المستخدمين النشطين الآن" — Durable Object واحد
 *  بتخزين SQLite حقيقي، يحل محل فايربيس بالكامل لهذه الميزة.
 * ============================================================================
 *
 * لماذا تركنا فايربيس بالكامل لهذا الجزء تحديداً:
 * معاملات (Transactions) Realtime Database تعتمد على اتصال حي واللعب بصلاحيات .read/.write
 * بشكل يصعب ضمانه 100%، وهذا ما تسبب على الأغلب بمشكلة "العدّاد لا يزيد إلا لمستخدم واحد".
 * الحل الجذري: تنفيذ منطق العدّ بأنفسنا بقاعدة SQLite حقيقية داخل Durable Object واحد، حيث
 * **كل الطلبات تُعالَج بترتيب متسلسل صارم (Single-threaded) من تصميم المنصة نفسها** — هذا
 * يعني استحالة رياضية لأي Race Condition بين جهازين يسجّلان بنفس اللحظة، بلا حاجة لأي معاملات
 * معقدة أو إعادة محاولة. هذا أقوى ضمان دقة متاح تقنياً لهذا النوع من العدّادات.
 *
 * ========================= إجمالي المستخدمين =========================
 * جدول SQLite واحد (installs) بثلاثة أعمدة: device_id (مفتاح فريد)، first_seen_at، last_seen_at.
 * كل اتصال WebSocket جديد (أي فتح فعلي للتطبيق) يُنفّذ:
 *   INSERT ... ON CONFLICT(device_id) DO UPDATE SET last_seen_at = now
 * جهاز جديد تماماً → يُنشئ صفاً جديداً (يُحتسب). نفس الجهاز (تحديث تطبيق/حذف وإعادة تثبيت/فتح
 * متكرر) → نفس device_id → يُحدّث last_seen_at فقط، لا صف جديد، لا زيادة بالعدّاد. هذا مضمون
 * بواسطة قيد PRIMARY KEY نفسه على مستوى قاعدة البيانات، وليس بمنطق تطبيقي قابل للخطأ.
 *
 * "إجمالي المستخدمين" المعروض = عدد الصفوف التي last_seen_at خلال آخر 100 يوم فقط:
 *   SELECT COUNT(*) FROM installs WHERE last_seen_at > (الآن - 100 يوم)
 * جهاز غاب أكثر من 100 يوم → يخرج تلقائياً من هذا العدّ فور أي قراءة تالية (بلا أي حذف أو
 * عملية "تنظيف" منفصلة يمكن أن تفشل أو تُنسى). لو رجع نفس الجهاز لاحقاً → last_seen_at
 * يتحدّث فيدخل ضمن النافذة من جديد، وبما إن الصف نفسه (device_id) لم يتكرر، يُحتسب كمستخدم
 * واحد فقط بالضبط كما هو مطلوب.
 *
 * ========================= المستخدمون النشطون الآن =========================
 * كل جهاز مفتوح فعلياً يبقي اتصال WebSocket واحداً مفتوحاً، ويرسل "نبضة" تطبيقية خفيفة كل 15
 * ثانية. خط أمان صارم عبر Alarm دوري كل 20 ثانية: أي اتصال لم ترد منه نبضة خلال آخر 45 ثانية
 * (أي فاتته نبضتان إلى ثلاث) يُغلَق قسراً من طرف الخادم نفسه ويُعاد بث العدد الصحيح فوراً.
 * هذا يضمن حداً أقصى صارماً وقابلاً للقياس (لا يتجاوز ~65 ثانية تقريباً) لتصحيح العدّاد حتى في
 * أسوأ سيناريو (تعطّل التطبيق أو تجميده من نظام أندرويد دون فرصة لإرسال إغلاق نظيف) — أما
 * الخروج الطبيعي (رجوع المستخدم للشاشة الرئيسية) فيبقى فورياً 100% كما كان (إغلاق صريح فوري).
 */

const INACTIVE_WINDOW_MS = 100 * 24 * 60 * 60 * 1000; // 100 يوم بالميلي ثانية
const PING_STALE_MS = 45_000;   // اتصال بلا نبضة لأكثر من هذا يُعتبر ميتاً
const SWEEP_INTERVAL_MS = 20_000; // دورية فحص الاتصالات الراكدة

function isValidDeviceId(deviceId) {
  return typeof deviceId === 'string' && deviceId.length >= 8 && deviceId.length <= 128
      && /^[a-zA-Z0-9_-]+$/.test(deviceId);
}

function isAuthorized(request, env) {
  const provided = request.headers.get('X-YG-Secret') || '';
  return Boolean(env.YG_SHARED_SECRET) && provided === env.YG_SHARED_SECRET;
}

function json(data, status = 200) {
  return new Response(JSON.stringify(data), {
    status,
    headers: {
      'Content-Type': 'application/json; charset=utf-8',
      'Access-Control-Allow-Origin': '*',
    },
  });
}

export class PresenceRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;

    // إنشاء الجدول مرة واحدة فقط (idempotent) قبل معالجة أي طلب لهذا الكائن.
    this.ctx.blockConcurrencyWhile(async () => {
      this.ctx.storage.sql.exec(`
        CREATE TABLE IF NOT EXISTS installs (
          device_id TEXT PRIMARY KEY,
          first_seen_at INTEGER NOT NULL,
          last_seen_at INTEGER NOT NULL
        )
      `);
    });
  }

  async fetch(request) {
    const url = new URL(request.url);
    const upgradeHeader = request.headers.get('Upgrade');
    const isWs = upgradeHeader && upgradeHeader.toLowerCase() === 'websocket';

    if (!isWs) {
      if (url.pathname === '/count') {
        return json({ total: this.computeTotalUsers(), active: this.ctx.getWebSockets().length });
      }
      return new Response('not found', { status: 404 });
    }

    const deviceId = url.searchParams.get('deviceId') || '';
    if (!isValidDeviceId(deviceId)) {
      return new Response('bad device id', { status: 400 });
    }

    // لو نفس الجهاز عنده اتصال قديم مفتوح (إعادة اتصال بعد انقطاع شبكة قصير)، نغلقه أولاً حتى
    // لا يُحتسب مرتين بعدّاد "نشط الآن".
    for (const oldSocket of this.ctx.getWebSockets(deviceId)) {
      try { oldSocket.close(4000, 'replaced_by_new_connection'); } catch (e) { /* تجاهل */ }
    }

    // تسجيل/تحديث هذا الجهاز في جدول التثبيتات — هذا وحده يحدّد "مستخدم فريد" بدقة تامة.
    this.touchInstall(deviceId);

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    server.serializeAttachment({ deviceId, lastPingAt: Date.now() });
    this.ctx.acceptWebSocket(server, [deviceId]);

    await this.ensureSweepAlarm();
    await this.broadcastCounts();

    return new Response(null, { status: 101, webSocket: client });
  }

  /** يُنفَّذ بأمان تام لتكرار الاستدعاء: أول ظهور لجهاز يُنشئ صفاً، وأي ظهور لاحق يُحدّث فقط. */
  touchInstall(deviceId) {
    const now = Date.now();
    this.ctx.storage.sql.exec(
      `INSERT INTO installs (device_id, first_seen_at, last_seen_at) VALUES (?, ?, ?)
       ON CONFLICT(device_id) DO UPDATE SET last_seen_at = excluded.last_seen_at`,
      deviceId, now, now
    );
  }

  computeTotalUsers() {
    const cutoff = Date.now() - INACTIVE_WINDOW_MS;
    const row = this.ctx.storage.sql
      .exec(`SELECT COUNT(*) AS c FROM installs WHERE last_seen_at > ?`, cutoff)
      .one();
    return row ? Number(row.c) : 0;
  }

  // نبضة تطبيقية خفيفة من العميل كل 15 ثانية — لا حاجة لأي رد، فقط نحدّث وقت آخر نبضة.
  async webSocketMessage(ws, _message) {
    try {
      const attachment = ws.deserializeAttachment() || {};
      attachment.lastPingAt = Date.now();
      ws.serializeAttachment(attachment);
    } catch (e) { /* تجاهل */ }
  }

  async webSocketClose(_ws, _code, _reason, _wasClean) {
    await this.broadcastCounts();
  }

  async webSocketError(_ws, _error) {
    await this.broadcastCounts();
  }

  /** يعمل كل ~20 ثانية طالما هناك اتصال واحد على الأقل: يغلق أي اتصال راكد (بلا نبضة). */
  async alarm() {
    const now = Date.now();
    let anyRemaining = false;

    for (const ws of this.ctx.getWebSockets()) {
      let attachment = null;
      try { attachment = ws.deserializeAttachment(); } catch (e) { /* تجاهل */ }
      const lastPingAt = attachment && attachment.lastPingAt ? attachment.lastPingAt : 0;

      if (now - lastPingAt > PING_STALE_MS) {
        try { ws.close(4001, 'stale_connection'); } catch (e) { /* تجاهل */ }
      } else {
        anyRemaining = true;
      }
    }

    await this.broadcastCounts();

    // نعيد جدولة الفحص فقط طالما بقي اتصال واحد حقيقي على الأقل — بعدها يهدأ الكائن تماماً
    // (Hibernation) بلا أي تكلفة تشغيل، وهذا ما يبقي الحل مجانياً حتى مع آلاف الأجهزة.
    if (anyRemaining) {
      await this.ctx.storage.setAlarm(Date.now() + SWEEP_INTERVAL_MS);
    }
  }

  async ensureSweepAlarm() {
    const current = await this.ctx.storage.getAlarm();
    if (!current) {
      await this.ctx.storage.setAlarm(Date.now() + SWEEP_INTERVAL_MS);
    }
  }

  async broadcastCounts() {
    const total = this.computeTotalUsers();
    const active = this.ctx.getWebSockets().length;
    const payload = JSON.stringify({ total, active });
    for (const ws of this.ctx.getWebSockets()) {
      try { ws.send(payload); } catch (e) { /* اتصال ميت فعلياً، سيُنظَّف قريباً */ }
    }
  }
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return new Response(null, {
        status: 204,
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Headers': 'Content-Type, X-YG-Secret',
          'Access-Control-Allow-Methods': 'GET, OPTIONS',
        },
      });
    }

    const url = new URL(request.url);

    if (url.pathname === '/ws') {
      if (!isAuthorized(request, env)) {
        return new Response('unauthorized', { status: 401 });
      }
      const id = env.PRESENCE_ROOM.idFromName('global');
      const stub = env.PRESENCE_ROOM.get(id);
      return stub.fetch(request);
    }

    // اختياري — للاختبار اليدوي فقط (يتطلب نفس السر).
    if (url.pathname === '/count' && request.method === 'GET') {
      if (!isAuthorized(request, env)) {
        return new Response('unauthorized', { status: 401 });
      }
      const id = env.PRESENCE_ROOM.idFromName('global');
      const stub = env.PRESENCE_ROOM.get(id);
      return stub.fetch(new Request('https://internal/count'));
    }

    return json({ error: 'not_found' }, 404);
  },
};

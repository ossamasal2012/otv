/**
 * ============================================================================
 *  عدّاد "المستخدمين النشطين الآن" لتطبيق يلا گول — Cloudflare Worker + Workers KV
 * ============================================================================
 *
 * لماذا هذا التصميم:
 * كل جهاز يرسل "نبضة" HTTP قصيرة كل ~30 ثانية طالما التطبيق مفتوح أمامه (من UserStatsManager
 * بجانب أندرويد)، فتُخزَّن حالته بـ Workers KV بمدة صلاحية (TTL) قصيرة. لو توقف الجهاز عن إرسال
 * النبضات — خروج طبيعي، إغلاق قسري، تعطّل التطبيق، انقطاع الشبكة المفاجئ — تنتهي صلاحية سجله
 * تلقائياً ويختفي من العدّاد بمفرده، دون أي كود إضافي يراقب "الانقطاع" (هذا يعادل onDisconnect()
 * بفايربيس، لكن بدون الحاجة لإبقاء اتصال دائم مفتوح، فلا يوجد أي سقف "اتصالات متزامنة").
 *
 * العدّاد المعروض لا يُعاد حسابه بمسح كامل لكل طلب (مكلف عند الحمل العالي)، بل يُخزَّن بذاكرة
 * مؤقتة (Cache) تتجدد تلقائياً كل ~10 ثوانٍ فقط عند الحاجة الفعلية، بصرف النظر عن عدد الطلبات.
 * بالإضافة لذلك: الانضمام والمغادرة الصريحان (heartbeat الأول / leave) يُحدّثان العدّاد المخزّن
 * فوراً بزيادة/نقصان لحظي، فتبقى الاستجابة فورية بالحالات الطبيعية، بينما تبقى دورة إعادة
 * الحساب الكاملة (التي تعتمد فقط على المفاتيح الحيّة فعلياً بفضل TTL) الضامن النهائي لصحة
 * الرقم وتصحيح أي انحراف تلقائياً خلال ثوانٍ معدودة — هذا هو معنى "نظام ذكي جداً" هنا: يصحح
 * نفسه بنفسه دون تدخّل يدوي.
 */

const PRESENCE_TTL_SECONDS = 70;   // مهلة انتهاء الحضور إن لم تصل نبضة جديدة (أكثر من ضِعف فترة النبض)
const CACHE_FRESH_MS = 10_000;     // مدة صلاحية العدّاد المخزّن مؤقتاً قبل إعادة حسابه من الأصل
const CACHE_KEY = 'active_count_cache';
const PRESENCE_PREFIX = 'presence:';

function withCors(resp) {
  resp.headers.set('Access-Control-Allow-Origin', '*');
  resp.headers.set('Access-Control-Allow-Headers', 'Content-Type, X-YG-Secret');
  resp.headers.set('Access-Control-Allow-Methods', 'POST, GET, OPTIONS');
  return resp;
}

function json(data, status = 200) {
  return withCors(new Response(JSON.stringify(data), {
    status,
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  }));
}

function isAuthorized(request, env) {
  const provided = request.headers.get('X-YG-Secret') || '';
  return Boolean(env.YG_SHARED_SECRET) && provided === env.YG_SHARED_SECRET;
}

function isValidDeviceId(deviceId) {
  return typeof deviceId === 'string' && deviceId.length >= 8 && deviceId.length <= 128
      && /^[a-zA-Z0-9_-]+$/.test(deviceId);
}

/** إعادة حساب كاملة: عدّ المفاتيح الحيّة فعلياً فقط (المنتهية بـ TTL تُستبعد تلقائياً من list()). */
async function recomputeCount(env) {
  let count = 0;
  let cursor;
  do {
    const page = await env.PRESENCE.list({ prefix: PRESENCE_PREFIX, cursor, limit: 1000 });
    count += page.keys.length;
    cursor = page.list_complete ? undefined : page.cursor;
  } while (cursor);

  await env.PRESENCE.put(CACHE_KEY, JSON.stringify({ count, updatedAt: Date.now() }));
  return count;
}

/** يعيد العدّاد المخزّن إن كان لا يزال حديثاً، وإلا يعيد حسابه من جديد. */
async function getCount(env) {
  const cachedRaw = await env.PRESENCE.get(CACHE_KEY);
  if (cachedRaw) {
    const cached = JSON.parse(cachedRaw);
    if (Date.now() - cached.updatedAt < CACHE_FRESH_MS) {
      return cached.count;
    }
  }
  return recomputeCount(env);
}

/** تعديل فوري لطيف للعدّاد المخزّن (استجابة لحظية)، دون انتظار دورة إعادة الحساب الكاملة. */
async function bumpCache(env, delta) {
  const cachedRaw = await env.PRESENCE.get(CACHE_KEY);
  const cached = cachedRaw ? JSON.parse(cachedRaw) : { count: 0, updatedAt: 0 };
  const next = Math.max(0, cached.count + delta);
  await env.PRESENCE.put(CACHE_KEY, JSON.stringify({ count: next, updatedAt: Date.now() }));
  return next;
}

async function readJsonBody(request) {
  try {
    return await request.json();
  } catch {
    return null;
  }
}

export default {
  async fetch(request, env) {
    if (request.method === 'OPTIONS') {
      return withCors(new Response(null, { status: 204 }));
    }

    const url = new URL(request.url);

    // -------- POST /heartbeat { deviceId } --------
    // يُرسل كل ~30 ثانية طالما التطبيق مفتوح. يسجّل/يجدّد حضور الجهاز، ويعيد العدّاد الحالي.
    if (url.pathname === '/heartbeat' && request.method === 'POST') {
      if (!isAuthorized(request, env)) return json({ error: 'unauthorized' }, 401);

      const body = await readJsonBody(request);
      const deviceId = body && typeof body.deviceId === 'string' ? body.deviceId.trim() : '';
      if (!isValidDeviceId(deviceId)) return json({ error: 'bad_device_id' }, 400);

      const key = PRESENCE_PREFIX + deviceId;
      const existing = await env.PRESENCE.get(key);
      await env.PRESENCE.put(key, '1', { expirationTtl: PRESENCE_TTL_SECONDS });

      const active = existing ? await getCount(env) : await bumpCache(env, +1);
      return json({ active });
    }

    // -------- POST /leave { deviceId } --------
    // يُرسل مرة واحدة عند خروج المستخدم الطبيعي من التطبيق (انتقاله للخلفية)، لإزالة فورية
    // بدل انتظار انتهاء صلاحية آخر نبضة.
    if (url.pathname === '/leave' && request.method === 'POST') {
      if (!isAuthorized(request, env)) return json({ error: 'unauthorized' }, 401);

      const body = await readJsonBody(request);
      const deviceId = body && typeof body.deviceId === 'string' ? body.deviceId.trim() : '';
      if (!isValidDeviceId(deviceId)) return json({ error: 'bad_device_id' }, 400);

      const key = PRESENCE_PREFIX + deviceId;
      const existing = await env.PRESENCE.get(key);
      await env.PRESENCE.delete(key);

      const active = existing ? await bumpCache(env, -1) : await getCount(env);
      return json({ active });
    }

    // -------- GET /active-count --------
    // قراءة فقط (اختيارية) — غير مستخدمة حالياً من تطبيق أندرويد لأن رد /heartbeat يكفي، لكنها
    // مفيدة للاختبار اليدوي أو لأي واجهة أخرى مستقبلاً (كلوحة تحكم admin.html مثلاً).
    if (url.pathname === '/active-count' && request.method === 'GET') {
      const active = await getCount(env);
      return json({ active });
    }

    return json({ error: 'not_found' }, 404);
  },
};

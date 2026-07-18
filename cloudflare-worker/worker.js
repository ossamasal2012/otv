/**
 * ============================================================================
 *  عدّاد "المستخدمين النشطين الآن" — الآن عبر WebSocket حقيقي (Durable Objects)
 * ============================================================================
 *
 * لماذا هذا التصميم الجديد (بدل النبضات الدورية):
 * كل جهاز يفتح اتصال WebSocket واحد مباشر مع Durable Object واحد مشترك للجميع، طالما التطبيق
 * بالمقدمة. لحظة أي جهاز يتصل أو ينفصل، الخادم يحسب العدد الحالي فوراً ويرسله (push) لكل
 * الأجهزة المتصلة بنفس اللحظة تقريباً — بدون أي تأخير انتظار "دورة" قادمة، وبدون أي سقف
 * "اتصالات متزامنة" (100 فقط كانت مشكلة فايربيس).
 *
 * نستخدم "WebSocket Hibernation API" الخاص بـ Durable Objects: يسمح لـ Cloudflare بإبقاء
 * آلاف الاتصالات مفتوحة بتكلفة شبه معدومة أثناء عدم النشاط (الكائن "ينام" ويُستيقظ فقط عند
 * وصول رسالة فعلية)، وهذا ما يجعل هذا الحل يعمل بأمان على الخطة المجانية بدون أي بطاقة دفع.
 *
 * الخروج الطبيعي (خلفية التطبيق) → إغلاق فوري وصريح من الجهاز → إزالة واستجابة فورية.
 * الانقطاع المفاجئ (تعطّل/فقد شبكة) → تكتشفه شبكة Cloudflare عند موت الاتصال فعلياً (قد يستغرق
 * دقيقة أو دقيقتين، أقل احتمالاً بكثير من سيناريوهات الخروج العادي، ويُصحَّح تلقائياً بمجرد اكتشافه).
 */

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

/**
 * كائن واحد مشترك يمثّل "غرفة" تجمع كل الأجهزة النشطة حالياً. اسمه ثابت ("global") فيُستخدم
 * نفس الكائن دائماً لكل الطلبات، فيمتلك رؤية دقيقة لكل الاتصالات المفتوحة بنفس اللحظة.
 */
export class PresenceRoom {
  constructor(ctx, env) {
    this.ctx = ctx;
    this.env = env;
  }

  async fetch(request) {
    const upgradeHeader = request.headers.get('Upgrade');
    const isWebSocketUpgrade = upgradeHeader && upgradeHeader.toLowerCase() === 'websocket';

    if (!isWebSocketUpgrade) {
      // طلب عادي (غير WebSocket) — يُستخدم فقط للاختبار اليدوي لقراءة العدد الحالي.
      return json({ active: this.ctx.getWebSockets().length });
    }

    const url = new URL(request.url);
    const deviceId = url.searchParams.get('deviceId') || '';
    if (!isValidDeviceId(deviceId)) {
      return new Response('bad device id', { status: 400 });
    }

    // لو نفس الجهاز عنده اتصال قديم مفتوح (مثلاً أعاد الاتصال بعد انقطاع شبكة قصير)، نغلقه
    // أولاً حتى لا يُحتسب مرتين.
    for (const oldSocket of this.ctx.getWebSockets(deviceId)) {
      try { oldSocket.close(4000, 'replaced_by_new_connection'); } catch (e) { /* تجاهل */ }
    }

    const pair = new WebSocketPair();
    const [client, server] = Object.values(pair);

    // Hibernation API: الاتصال يبقى مُدارًا من Cloudflare حتى أثناء "سبات" الكائن، بدون أي
    // تكلفة تشغيل وقت عدم النشاط.
    this.ctx.acceptWebSocket(server, [deviceId]);

    await this.broadcastCount();

    return new Response(null, { status: 101, webSocket: client });
  }

  // رسائل "ping" الخفيفة التي يرسلها العميل دورياً للحفاظ على الاتصال حياً عبر أي وسيط شبكي —
  // لا حاجة لأي رد خاص، فقط نتجاهلها بهدوء.
  async webSocketMessage(_ws, _message) {}

  async webSocketClose(_ws, _code, _reason, _wasClean) {
    await this.broadcastCount();
  }

  async webSocketError(_ws, _error) {
    await this.broadcastCount();
  }

  async broadcastCount() {
    const sockets = this.ctx.getWebSockets();
    const payload = JSON.stringify({ active: sockets.length });
    for (const ws of sockets) {
      try {
        ws.send(payload);
      } catch (e) {
        // اتصال ميت فعلياً؛ سيُنظَّف تلقائياً بواسطة webSocketClose/webSocketError قريباً.
      }
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

    // اختياري — للاختبار اليدوي فقط من المتصفح (GET بدون WebSocket).
    if (url.pathname === '/active-count' && request.method === 'GET') {
      const id = env.PRESENCE_ROOM.idFromName('global');
      const stub = env.PRESENCE_ROOM.get(id);
      return stub.fetch(new Request('https://internal/count'));
    }

    return json({ error: 'not_found' }, 404);
  },
};

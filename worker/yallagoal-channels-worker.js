const CHANNELS_CACHE_KEY = 'https://yallagoal.internal/cache/channels.json';
const SERVICE_UNAVAILABLE_MESSAGE = 'Service temporarily unavailable';
const CHANNELS_PATH = '/channels';
const CACHE_TTL_SECONDS = 600;
const STALE_WHILE_REVALIDATE_SECONDS = 600;
const RATE_LIMIT_WINDOW_SECONDS = 60;
const RATE_LIMIT_MAX_REQUESTS = 60;
const GITHUB_RETRY_DELAY_MS = 400;
const GITHUB_TIMEOUT_MS = 5000;

const SECURITY_HEADERS = {
  'X-Content-Type-Options': 'nosniff',
  'X-Frame-Options': 'DENY',
  'Referrer-Policy': 'no-referrer',
  'Permissions-Policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=(), interest-cohort=()',
};

export default {
  async fetch(request, env, ctx) {
    try {
      const validation = validateRequest(request);
      if (!validation.ok) {
        return jsonResponse({ ok: false, error: validation.error }, validation.status || 403);
      }

      const rateLimit = await checkRateLimit(request);
      if (!rateLimit.ok) {
        return jsonResponse({ ok: false, error: 'rate_limited' }, 429, {
          'Retry-After': String(RATE_LIMIT_WINDOW_SECONDS),
        });
      }

      const cache = caches.default;
      const cacheRequest = new Request(CHANNELS_CACHE_KEY, { method: 'GET' });
      const cached = await cache.match(cacheRequest);
      if (cached) {
        const cachedAt = Number(cached.headers.get('X-Yalla-Cache-Time') || '0');
        const ageSeconds = Math.floor(Date.now() / 1000) - cachedAt;
        if (ageSeconds < CACHE_TTL_SECONDS) {
          console.log('channels cache hit');
          return withResponseHeaders(cached, { 'X-Yalla-Cache': 'HIT' });
        }
        if (ageSeconds < CACHE_TTL_SECONDS + STALE_WHILE_REVALIDATE_SECONDS) {
          console.log('channels cache stale; revalidating in background');
          ctx.waitUntil(refreshChannels(cache, cacheRequest, env, cached.clone()));
          return withResponseHeaders(cached, { 'X-Yalla-Cache': 'STALE' });
        }
      }

      try {
        const fresh = await refreshChannels(cache, cacheRequest, env, cached ? cached.clone() : null);
        console.log(cached ? 'channels cache revalidated' : 'channels cache miss');
        return withResponseHeaders(fresh, { 'X-Yalla-Cache': cached ? 'REVALIDATED' : 'MISS' });
      } catch (error) {
        if (cached) {
          console.warn('channels using stale-if-error after GitHub failure');
          return withResponseHeaders(cached, {
            'X-Yalla-Cache': 'STALE-IF-ERROR',
            'Warning': '110 - \"Response is stale because GitHub is temporarily unavailable\"',
          });
        }
        return serviceUnavailableResponse();
      }
    } catch (error) {
      return serviceUnavailableResponse();
    }
  },
};

function validateRequest(request) {
  const url = new URL(request.url);
  if (request.method !== 'GET') return { ok: false, status: 405, error: 'method_not_allowed' };
  if (url.pathname !== CHANNELS_PATH) return { ok: false, status: 404, error: 'not_found' };
  if (Number(request.headers.get('content-length') || '0') > 0) return { ok: false, status: 413, error: 'body_not_allowed' };
  return { ok: true };
}

async function refreshChannels(cache, cacheRequest, env, cachedResponse = null) {
  const githubUrl = `https://api.github.com/repos/${env.GITHUB_OWNER}/${env.GITHUB_REPO}/contents/${env.GITHUB_CHANNELS_PATH}?ref=${env.GITHUB_REF || 'main'}`;
  const previousEtag = cachedResponse ? cachedResponse.headers.get('ETag') : '';
  const githubHeaders = {
    'Accept': 'application/vnd.github.raw+json',
    'Authorization': `Bearer ${env.GITHUB_TOKEN}`,
    'User-Agent': 'YallaGoal-Cloudflare-Worker',
    'X-GitHub-Api-Version': '2022-11-28',
  };
  if (previousEtag) githubHeaders['If-None-Match'] = previousEtag;

  const githubResponse = await fetchGitHubWithRetry(githubUrl, githubHeaders);

  if (githubResponse.status === 304 && cachedResponse) {
    console.log('GitHub returned 304 for channels');
    const body = await cachedResponse.text();
    const response = buildChannelsResponse(body, previousEtag);
    await cache.put(cacheRequest, response.clone());
    return response;
  }

  if (!githubResponse.ok) throw new Error(`GitHub returned ${githubResponse.status}`);

  console.log('GitHub returned 200 for channels');
  const body = await githubResponse.text();
  JSON.parse(body);

  const response = buildChannelsResponse(body, githubResponse.headers.get('ETag') || previousEtag);
  await cache.put(cacheRequest, response.clone());
  return response;
}

async function fetchGitHubWithRetry(githubUrl, headers) {
  let lastError = null;
  for (let attempt = 0; attempt < 2; attempt++) {
    const controller = new AbortController();
    const timeout = setTimeout(() => controller.abort(), GITHUB_TIMEOUT_MS);
    try {
      const response = await fetch(githubUrl, { headers, signal: controller.signal });
      clearTimeout(timeout);
      if (attempt === 0 && isRetryableGitHubStatus(response.status)) {
        console.warn(`GitHub returned retryable ${response.status}; retrying once`);
        await delay(GITHUB_RETRY_DELAY_MS);
        continue;
      }
      return response;
    } catch (error) {
      clearTimeout(timeout);
      lastError = error;
      if (attempt === 0) {
        console.warn('GitHub request failed or timed out; retrying once');
        await delay(GITHUB_RETRY_DELAY_MS);
        continue;
      }
    }
  }
  throw lastError || new Error('GitHub request failed');
}

function isRetryableGitHubStatus(status) {
  return status === 500 || status === 502 || status === 503 || status === 504;
}

function delay(ms) {
  return new Promise(resolve => setTimeout(resolve, ms));
}

function buildChannelsResponse(body, etag = '') {
  const headers = {
    ...SECURITY_HEADERS,
    'Content-Type': 'application/json; charset=utf-8',
    'Cache-Control': `max-age=${CACHE_TTL_SECONDS}, stale-while-revalidate=${STALE_WHILE_REVALIDATE_SECONDS}`,
    'X-Yalla-Cache-Time': String(Math.floor(Date.now() / 1000)),
  };
  if (etag) headers['ETag'] = etag;
  return new Response(body, { status: 200, headers });
}

async function checkRateLimit(request) {
  const ip = request.headers.get('CF-Connecting-IP') || 'unknown';
  const bucket = Math.floor(Date.now() / 1000 / RATE_LIMIT_WINDOW_SECONDS);
  const cacheKey = new Request(`https://yallagoal.internal/rl/${ip}/${bucket}`);
  const cache = caches.default;
  const current = await cache.match(cacheKey);
  const count = current ? Number(await current.text()) + 1 : 1;
  if (count > RATE_LIMIT_MAX_REQUESTS) return { ok: false };
  await cache.put(cacheKey, new Response(String(count), {
    headers: { 'Cache-Control': `max-age=${RATE_LIMIT_WINDOW_SECONDS}` },
  }));
  return { ok: true };
}

function serviceUnavailableResponse() {
  return jsonResponse({
    success: false,
    ok: false,
    message: SERVICE_UNAVAILABLE_MESSAGE,
  }, 503);
}

function jsonResponse(body, status = 200, extraHeaders = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: {
      ...SECURITY_HEADERS,
      ...extraHeaders,
      'Content-Type': 'application/json; charset=utf-8',
      'Cache-Control': 'no-store',
    },
  });
}

function withResponseHeaders(response, extraHeaders = {}) {
  const headers = new Headers(response.headers);
  Object.entries({ ...SECURITY_HEADERS, ...extraHeaders }).forEach(([key, value]) => headers.set(key, value));
  return new Response(response.body, { status: response.status, statusText: response.statusText, headers });
}

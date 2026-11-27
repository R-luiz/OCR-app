/**
 * OCR gateway: lets the owner's phone run OCR jobs on the private RunPod endpoint
 * without the app ever holding a RunPod key.
 *
 *   GET  /                             public, carries no data: 200 once the configuration is complete
 *   GET  /.well-known/assetlinks.json  lets Android verify that /auth/callback belongs to the app
 *   GET  /auth/callback                where GitHub sends the browser back: a static page
 *   POST /auth/token                   trades GitHub's code and its PKCE verifier for a session;
 *                                      only the configured GitHub account gets one
 *   POST /auth/signout                 `Authorization: Bearer <session>`; voids that session
 *   POST /v2/ocr/run          ┐
 *   GET  /v2/ocr/status/{id}  │ `Authorization: Bearer <session>`; relayed to
 *   POST /v2/ocr/cancel/{id}  │ https://api.runpod.ai/v2/{RUNPOD_ENDPOINT_ID}/… with the
 *   GET  /v2/ocr/health       ┘ gateway's own RunPod key
 *
 * The gateway is silent: nothing is logged. A session is a signed token, and the only
 * state kept is one number in KV, `not_before`, which every sign-in and sign-out moves
 * forward so that only the newest session is accepted.
 */

const GITHUB_ACCESS_TOKEN_URL = 'https://github.com/login/oauth/access_token';
const GITHUB_USER_URL = 'https://api.github.com/user';
const RUNPOD_API_BASE = 'https://api.runpod.ai/v2';
const USER_AGENT = 'ocr-app-gateway';

/**
 * Where GitHub sends the browser back. It must be the GitHub App's callback URL, and the
 * app declares it as a verified App Link, so a code issued for it reaches the app.
 */
const CALLBACK_PATH = '/auth/callback';
const CALLBACK_URL = `https://ocr.robinluiz.fr${CALLBACK_PATH}`;
const ASSET_LINKS_PATH = '/.well-known/assetlinks.json';

/** The release key, then the owner's local debug key. */
const ASSET_LINKS = [{
  relation: ['delegate_permission/common.handle_all_urls'],
  target: {
    namespace: 'android_app',
    package_name: 'com.ocrapp',
    sha256_cert_fingerprints: [
      'D9:55:E3:37:CA:10:6C:D4:00:52:C5:5A:20:41:71:E8:75:77:42:D7:A1:62:A5:68:8A:D8:24:7C:9B:6F:CB:CE',
      '3F:9C:A8:EE:70:BB:5C:C4:A9:6E:C7:B2:48:92:78:56:76:9D:80:A1:6B:F0:4E:EE:36:C2:D1:E1:2C:EE:C6:E2',
    ],
  },
}];

const SESSION_TTL_SECONDS = 7 * 24 * 60 * 60;
const CLOCK_SKEW_SECONDS = 60;
const SESSION_FORMAT = /^ocr1\.([A-Za-z0-9_-]{16,512})\.([A-Za-z0-9_-]{43})$/;
const NOT_BEFORE_KEY = 'not_before';
/** KV's edge cache for `not_before`: a sign-out reaches every location within about a minute. */
const NOT_BEFORE_CACHE_SECONDS = 60;

const GITHUB_CODE_FORMAT = /^[\x21-\x7e]{1,512}$/;
/** RFC 7636 §4.1. */
const CODE_VERIFIER_FORMAT = /^[A-Za-z0-9._~-]{43,128}$/;

/** The window of AUTH_LIMITER in wrangler.jsonc, and so the wait a refused client is told. */
const AUTH_LIMIT_PERIOD_SECONDS = 60;

// The Free Worker has a 10 ms CPU budget. Bound the JSON it parses and serializes;
// Android splits larger documents before sending them (MAX_MANAGED_JOB_CHARS).
const MAX_RUN_BODY_BYTES = 2 * 1024 * 1024;
const MAX_AUTH_BODY_BYTES = 4 * 1024;
const MAX_GITHUB_BODY_BYTES = 64 * 1024;

/**
 * The policy of every job, whatever the app sends: 15 minutes of execution, the app's own
 * `JOB_TIMEOUT`, and an hour in the queue. A job then outlives neither the app's interest
 * in it nor a revoked session by more than that.
 */
const RUN_POLICY = { executionTimeout: 15 * 60 * 1000, ttl: 60 * 60 * 1000 };

const JOB_ID = '([A-Za-z0-9-]{1,128})';
const STATUS_ROUTE = new RegExp(`^/v2/ocr/status/${JOB_ID}$`);
const CANCEL_ROUTE = new RegExp(`^/v2/ocr/cancel/${JOB_ID}$`);

const BASE_HEADERS = {
  'cache-control': 'no-store',
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
};

const encoder = new TextEncoder();
const decoder = new TextDecoder();
const utf8 = (text) => encoder.encode(text);

class TooLarge extends Error {}
class UpstreamError extends Error {}

export default {
  async fetch(request, env) {
    try {
      return await dispatch(request, env ?? {});
    } catch {
      // Deliberately silent: nothing about a request is ever logged. A KV failure ends
      // here too, and the app keeps its session on a 5xx.
      return error(500, 'Gateway error');
    }
  },
};

async function dispatch(request, env) {
  const { pathname } = new URL(request.url);
  if (pathname === '/') return request.method === 'GET' ? root(env) : methodNotAllowed('GET');
  if (pathname === ASSET_LINKS_PATH) {
    return request.method === 'GET' ? json(200, ASSET_LINKS) : methodNotAllowed('GET');
  }
  if (pathname === CALLBACK_PATH) {
    return request.method === 'GET' ? callbackPage() : methodNotAllowed('GET');
  }
  if (pathname === '/auth/token' || pathname === '/auth/signout') {
    if (request.method !== 'POST') return methodNotAllowed('POST');
    const refused = await rateLimit(request, env, pathname);
    if (refused) return refused;
    return pathname === '/auth/token' ? exchangeCode(request, env) : signOut(request, env);
  }

  const ocr = matchOcrRoute(pathname);
  if (!ocr) return error(404, 'Not found');
  if (request.method !== ocr.method) return methodNotAllowed(ocr.method);
  const config = readConfig(env);
  if (!config) return notConfigured();
  if (!(await readSession(request, config))) return unauthorized();
  return relayToRunPod(request, config, ocr);
}

/** The four RunPod calls the app makes, and nothing else: no `/runsync`, no queue purge. */
function matchOcrRoute(pathname) {
  if (pathname === '/v2/ocr/run') return { name: 'run', method: 'POST', path: 'run' };
  if (pathname === '/v2/ocr/health') return { name: 'health', method: 'GET', path: 'health' };
  let match = STATUS_ROUTE.exec(pathname);
  if (match) return { name: 'status', method: 'GET', path: `status/${match[1]}` };
  match = CANCEL_ROUTE.exec(pathname);
  if (match) return { name: 'cancel', method: 'POST', path: `cancel/${match[1]}` };
  return null;
}

function root(env) {
  return readConfig(env) ? json(200, { status: 'ok' }) : notConfigured();
}

/**
 * The sign-in calls are counted per route and client address by AUTH_LIMITER. Without
 * that binding the limit is skipped rather than sign-in shut: it guards the request
 * quota and GitHub's patience, not access.
 */
async function rateLimit(request, env, pathname) {
  const limiter = env.AUTH_LIMITER;
  if (typeof limiter?.limit !== 'function') return null;
  const key = `${pathname}:${request.headers.get('cf-connecting-ip') ?? ''}`;
  const { success } = await limiter.limit({ key });
  return success
    ? null
    : error(429, 'Too many sign-in requests', { 'retry-after': String(AUTH_LIMIT_PERIOD_SECONDS) });
}

// ---------------------------------------------------------------------------
// Configuration: every value is checked, and anything missing or malformed shuts
// the gateway rather than running it half-configured.

function readConfig(env) {
  const clientId = configText(env.GITHUB_CLIENT_ID);
  const clientSecret = configText(env.GITHUB_CLIENT_SECRET);
  const ownerId = configText(env.GITHUB_OWNER_ID);
  const endpointId = configText(env.RUNPOD_ENDPOINT_ID);
  const apiKey = configText(env.RUNPOD_API_KEY);
  const sessionKey = configText(env.OCR_SESSION_KEY);
  const sessionState = env.SESSION_STATE;

  if (!/^[A-Za-z0-9._-]{8,64}$/.test(clientId)) return null;
  if (!/^[A-Za-z0-9_-]{20,256}$/.test(clientSecret)) return null;
  if (!/^[1-9][0-9]{0,15}$/.test(ownerId) || !Number.isSafeInteger(Number(ownerId))) return null;
  if (!/^[a-z0-9]{8,64}$/.test(endpointId)) return null;
  if (!/^[A-Za-z0-9_-]{16,256}$/.test(apiKey)) return null;
  const sessionKeyBytes = decodeSessionKey(sessionKey);
  if (!sessionKeyBytes) return null;
  // Without it no session could ever be revoked, so none is accepted either.
  if (typeof sessionState?.get !== 'function' || typeof sessionState?.put !== 'function') return null;

  return {
    clientId, clientSecret, ownerId: Number(ownerId), endpointId, apiKey, sessionKey, sessionKeyBytes, sessionState,
  };
}

function configText(value) {
  if (typeof value === 'number') return String(value);
  return typeof value === 'string' ? value.trim() : '';
}

/** `OCR_SESSION_KEY`: base64url (or base64) of at least 32 random bytes. */
function decodeSessionKey(value) {
  const urlSafe = value.replace(/\+/g, '-').replace(/\//g, '_').replace(/={1,2}$/, '');
  const bytes = fromBase64Url(urlSafe);
  return bytes && bytes.length >= 32 && bytes.length <= 1024 ? bytes : null;
}

// ---------------------------------------------------------------------------
// The signing key comes from OCR_SESSION_KEY through HKDF; rotating the secret signs
// every device out.

let derivedKey = null;

function sessionKeyFor(config) {
  if (derivedKey?.secret !== config.sessionKey) {
    const key = deriveSessionKey(config.sessionKeyBytes);
    derivedKey = { secret: config.sessionKey, key };
    key.catch(() => {
      if (derivedKey?.key === key) derivedKey = null;
    });
  }
  return derivedKey.key;
}

async function deriveSessionKey(secret) {
  const master = await crypto.subtle.importKey('raw', secret, 'HKDF', false, ['deriveKey']);
  return crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: utf8('ocr-app-gateway'), info: utf8('session v1') },
    master,
    { name: 'HMAC', hash: 'SHA-256', length: 256 },
    false,
    ['sign', 'verify'],
  );
}

// ---------------------------------------------------------------------------
// Sessions: `ocr1.<claims>.<HMAC-SHA256>`, claims `{sub, scp, iat, exp}`.

async function issueSession(config, now) {
  const expiresAt = now + SESSION_TTL_SECONDS;
  const claims = { sub: config.ownerId, scp: 'ocr', iat: now, exp: expiresAt };
  const payload = toBase64Url(utf8(JSON.stringify(claims)));
  const key = await sessionKeyFor(config);
  const signature = await crypto.subtle.sign('HMAC', key, utf8(`ocr1.${payload}`));
  return { token: `ocr1.${payload}.${toBase64Url(new Uint8Array(signature))}`, expiresAt };
}

/** The claims of the request's session, or null when it carries none the gateway accepts. */
async function readSession(request, config) {
  const header = /^Bearer +(\S+)$/i.exec(request.headers.get('authorization') ?? '');
  const parts = header && SESSION_FORMAT.exec(header[1]);
  if (!parts) return null;
  const signature = fromBase64Url(parts[2]);
  const payload = fromBase64Url(parts[1]);
  if (!signature || signature.length !== 32 || !payload) return null;

  // `verify` compares in constant time; the claims are not even parsed before it passes.
  const key = await sessionKeyFor(config);
  if (!(await crypto.subtle.verify('HMAC', key, signature, utf8(`ocr1.${parts[1]}`)))) return null;
  const claims = parseObject(decoder.decode(payload));
  const now = nowSeconds();
  // `sub` is checked again so that changing GITHUB_OWNER_ID ends the former owner's sessions.
  const valid = claims !== null
    && claims.scp === 'ocr'
    && claims.sub === config.ownerId
    && Number.isSafeInteger(claims.iat)
    && Number.isSafeInteger(claims.exp)
    && claims.iat <= now + CLOCK_SKEW_SECONDS
    && claims.exp > now
    && claims.exp - claims.iat <= SESSION_TTL_SECONDS;
  if (!valid) return null;

  // Last, so that only a token that is otherwise valid costs a KV read.
  const notBefore = await readNotBefore(config);
  return notBefore !== null && claims.iat >= notBefore ? claims : null;
}

/** 0 before the first sign-in; null for a stored value that is not a time, which admits no session. */
async function readNotBefore(config) {
  const value = await config.sessionState.get(NOT_BEFORE_KEY, { cacheTtl: NOT_BEFORE_CACHE_SECONDS });
  if (value === null) return 0;
  return /^[0-9]{1,15}$/.test(value) ? Number(value) : null;
}

/** Sessions issued before [seconds] are refused from then on, wherever KV has caught up. */
function writeNotBefore(config, seconds) {
  return config.sessionState.put(NOT_BEFORE_KEY, String(seconds));
}

// ---------------------------------------------------------------------------
// GitHub sign-in: authorization code with PKCE (private GitHub App, no permissions).
// The app opens GitHub's authorize page with its own `state` and code challenge; GitHub
// sends the browser back to CALLBACK_URL, an App Link that hands the code to the app,
// and the app trades it here together with the verifier only it holds.

/**
 * The page GitHub sends the browser back to, for when Android did not hand the link to
 * the app. It is the same bytes for every request: nothing from the query reaches the
 * server's answer. Its script only rebuilds the link for the app from the browser's own
 * address, then takes the code out of the address bar.
 */
async function callbackPage() {
  const policy = [
    "default-src 'none'",
    `script-src '${await sha256Source(CALLBACK_SCRIPT)}'`,
    `style-src '${await sha256Source(CALLBACK_STYLE)}'`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ].join('; ');
  return new Response(CALLBACK_PAGE, {
    status: 200,
    headers: { ...BASE_HEADERS, 'content-type': 'text/html; charset=utf-8', 'content-security-policy': policy },
  });
}

const CALLBACK_SCRIPT = `
var query = location.search.slice(1);
if (/(?:^|&)state=/.test(query)) {
  var link = document.getElementById('open');
  link.href = 'intent://ocr.robinluiz.fr/auth/callback?' + query + '#Intent;scheme=https;package=com.ocrapp;end';
  link.hidden = false;
  history.replaceState(null, '', location.pathname);
}
`;

const CALLBACK_STYLE = `
body { font: 16px/1.5 system-ui, sans-serif; margin: 2rem auto; max-width: 32rem; padding: 0 1rem; }
a:not([hidden]) { display: inline-block; margin-top: 1rem; padding: 0.75rem 1.25rem; border-radius: 1.5rem;
                 background: #1b6ef3; color: #fff; text-decoration: none; }
`;

const CALLBACK_PAGE = `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="robots" content="noindex">
<title>OCR app sign-in</title>
<style>${CALLBACK_STYLE}</style>
</head>
<body>
<h1>OCR app sign-in</h1>
<p>Return to the OCR app to finish signing in. If it did not open by itself, tap “Open OCR app”. You can then close this page.</p>
<p lang="fr">Revenez dans l’application OCR pour terminer la connexion. Si elle ne s’est pas ouverte d’elle-même, touchez « Ouvrir l’application OCR ». Vous pouvez ensuite fermer cette page.</p>
<a id="open" hidden>Open OCR app · Ouvrir l’application OCR</a>
<script>${CALLBACK_SCRIPT}</script>
</body>
</html>
`;

async function exchangeCode(request, env) {
  const config = readConfig(env);
  if (!config) return notConfigured();
  if (!isJson(request.headers.get('content-type'))) return error(415, 'Expected application/json');

  let body;
  try {
    body = parseObject(await readText(request.body, MAX_AUTH_BODY_BYTES));
  } catch (failure) {
    return failure instanceof TooLarge
      ? error(413, 'Request body too large')
      : error(400, 'Unreadable request body');
  }
  const code = body?.code;
  const verifier = body?.code_verifier;
  if (typeof code !== 'string' || !GITHUB_CODE_FORMAT.test(code)
    || typeof verifier !== 'string' || !CODE_VERIFIER_FORMAT.test(verifier)) {
    return error(400, 'Expected {"code": string, "code_verifier": string}');
  }

  let answer;
  try {
    answer = await postToGitHub(GITHUB_ACCESS_TOKEN_URL, {
      client_id: config.clientId,
      client_secret: config.clientSecret,
      code,
      code_verifier: verifier,
      redirect_uri: CALLBACK_URL,
    });
  } catch {
    return githubUnavailable();
  }
  if (typeof answer.access_token === 'string') return admitOwner(answer.access_token, config);

  switch (answer.error) {
    // A code that is wrong, expired, already used, or not this verifier's: GitHub's name
    // for it, and OAuth's.
    case 'bad_verification_code':
    case 'invalid_grant':
      return json(200, { status: 'invalid' });
    // The others (bad client secret, callback URL mismatch) are the gateway's to fix.
    default:
      return githubUnavailable();
  }
}

/**
 * Reads who approved the sign-in and issues a session only to the configured account.
 *
 * The GitHub token serves this one `/user` call: it is never returned, stored or
 * logged, and the refresh token GitHub may send alongside it is ignored.
 */
async function admitOwner(githubToken, config) {
  if (!/^[A-Za-z0-9_.-]{1,1024}$/.test(githubToken)) return githubUnavailable();

  let user;
  try {
    const response = await fetch(GITHUB_USER_URL, {
      method: 'GET',
      headers: {
        accept: 'application/vnd.github+json',
        authorization: `Bearer ${githubToken}`,
        'user-agent': USER_AGENT,
        'x-github-api-version': '2022-11-28',
      },
      redirect: 'manual',
      cache: 'no-store',
    });
    if (response.status !== 200) {
      discard(response);
      return githubUnavailable();
    }
    user = await readJsonObject(response);
  } catch {
    return githubUnavailable();
  }
  if (!Number.isSafeInteger(user.id)) return githubUnavailable();
  if (user.id !== config.ownerId) return json(200, { status: 'forbidden' });

  // Each sign-in voids every session issued before it: a sign-in the owner did not make
  // shows up as their phone being signed out.
  const now = nowSeconds();
  await writeNotBefore(config, now);
  const session = await issueSession(config, now);
  return json(200, { status: 'authorized', token: session.token, expires_at: session.expiresAt });
}

/** Voids the caller's session, and with it any older one that may have leaked. */
async function signOut(request, env) {
  const config = readConfig(env);
  if (!config) return notConfigured();
  const claims = await readSession(request, config);
  if (!claims) return unauthorized();
  await writeNotBefore(config, Math.max(nowSeconds(), claims.iat) + 1);
  return json(200, { status: 'signed_out' });
}

async function postToGitHub(url, fields) {
  const response = await fetch(url, {
    method: 'POST',
    headers: {
      accept: 'application/json',
      'content-type': 'application/x-www-form-urlencoded',
      'user-agent': USER_AGENT,
    },
    body: new URLSearchParams(fields).toString(),
    redirect: 'manual',
    cache: 'no-store',
  });
  // OAuth errors arrive as JSON, with a 200 or a 4xx; a redirect or a 5xx is an outage.
  const { status } = response;
  if (status < 200 || (status >= 300 && status < 400) || status >= 500) {
    discard(response);
    throw new UpstreamError();
  }
  return readJsonObject(response);
}

async function readJsonObject(response) {
  if (!isJson(response.headers.get('content-type'))) {
    discard(response);
    throw new UpstreamError();
  }
  const object = parseObject(await readText(response.body, MAX_GITHUB_BODY_BYTES));
  if (!object) throw new UpstreamError();
  return object;
}

// ---------------------------------------------------------------------------
// RunPod relay.

async function relayToRunPod(request, config, route) {
  // Only what the protocol needs goes upstream: none of the caller's headers, cookies,
  // query string or session token.
  const headers = { accept: 'application/json', authorization: `Bearer ${config.apiKey}` };
  const init = { method: route.method, headers, redirect: 'manual', cache: 'no-store' };
  if (route.name === 'run') {
    const job = await readRunRequest(request);
    if (job instanceof Response) return job;
    headers['content-type'] = 'application/json';
    init.body = job;
  } else if (route.name === 'cancel') {
    init.body = new Uint8Array(0); // the empty POST the app itself sends
  }

  let upstream;
  try {
    upstream = await fetch(`${RUNPOD_API_BASE}/${config.endpointId}/${route.path}`, init);
  } catch {
    return error(502, 'RunPod is unreachable');
  }
  return relayResponse(upstream);
}

/**
 * Rebuilds the `/run` body instead of passing the app's bytes on. RunPod reads more than
 * `input` at the top level (`policy`, `webhook`, `s3Config`), and a session holder sets
 * none of them: the policy is the gateway's, so a job can neither run for days nor report
 * to another host. What `input` holds is the worker's to validate.
 *
 * A declared length over the limit is refused before anything is read, and a body
 * of unknown length as soon as it passes the limit. RunPod receives neither.
 */
async function readRunRequest(request) {
  if (!isJson(request.headers.get('content-type'))) return error(415, 'Expected application/json');
  if (!request.body) return error(400, 'Empty request body');

  const declared = request.headers.get('content-length');
  if (declared !== null) {
    if (!/^[0-9]{1,15}$/.test(declared)) return error(400, 'Invalid Content-Length');
    if (Number(declared) > MAX_RUN_BODY_BYTES) return payloadTooLarge();
  }
  let body;
  try {
    body = parseObject(await readText(request.body, MAX_RUN_BODY_BYTES));
  } catch (failure) {
    return failure instanceof TooLarge ? payloadTooLarge() : error(400, 'Unreadable request body');
  }
  const input = body?.input;
  if (Object.keys(body ?? {}).length !== 1 || input === null || typeof input !== 'object' || Array.isArray(input)) {
    return error(400, 'Expected {"input": {...}} and nothing else');
  }
  return JSON.stringify({ input, policy: RUN_POLICY });
}

/** Passes RunPod's JSON on as it streams, with RunPod's status, and none of its other headers. */
function relayResponse(upstream) {
  const { status } = upstream;
  if (status >= 300 && status < 400) {
    discard(upstream);
    return error(502, 'RunPod answered with a redirect; it was not followed');
  }
  // These concern the gateway's own RunPod key. Relayed as is, they would read as
  // "your session is invalid" and send the owner through sign-in for nothing.
  if (status === 401 || status === 403) {
    discard(upstream);
    return error(502, 'RunPod rejected the gateway credentials');
  }

  const extra = {};
  const retryAfter = upstream.headers.get('retry-after');
  if (retryAfter !== null && /^[0-9]{1,6}$/.test(retryAfter)) extra['retry-after'] = retryAfter;

  const type = upstream.headers.get('content-type');
  if (!isJson(type)) {
    discard(upstream);
    return status >= 400
      ? error(status, `RunPod returned HTTP ${status}`, extra)
      : error(502, 'Unexpected response from RunPod');
  }
  const body = status === 204 || status === 205 ? null : upstream.body;
  return new Response(body, { status, headers: { ...BASE_HEADERS, 'content-type': type, ...extra } });
}

// ---------------------------------------------------------------------------
// Helpers.

function json(status, body, extra = {}) {
  return new Response(JSON.stringify(body), {
    status,
    headers: { ...BASE_HEADERS, 'content-type': 'application/json; charset=utf-8', ...extra },
  });
}

const error = (status, message, extra) => json(status, { error: message }, extra);
const methodNotAllowed = (allowed) => error(405, 'Method not allowed', { allow: allowed });
const notConfigured = () => error(503, 'Gateway is not configured');
const githubUnavailable = () => error(502, 'GitHub sign-in is unavailable');
const payloadTooLarge = () => error(413, `Request body exceeds ${MAX_RUN_BODY_BYTES} bytes`);
const unauthorized = () => error(401, 'Sign-in required', { 'www-authenticate': 'Bearer realm="ocr"' });

const nowSeconds = () => Math.floor(Date.now() / 1000);
const isJson = (type) => /^application\/(?:[a-z0-9.-]+\+)?json\s*(?:;|$)/i.test(type ?? '');

/** A CSP source pinning exactly [text]. */
async function sha256Source(text) {
  return `sha256-${toBase64(new Uint8Array(await crypto.subtle.digest('SHA-256', utf8(text))))}`;
}

function discard(response) {
  response.body?.cancel().catch(() => {});
}

function parseObject(text) {
  try {
    const value = JSON.parse(text);
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? value : null;
  } catch {
    return null;
  }
}

/** Reads a whole body, refusing to hold more than `limit` bytes of it. */
async function readText(stream, limit) {
  if (!stream) return '';
  const reader = stream.getReader();
  const chunks = [];
  let size = 0;
  for (;;) {
    const { done, value } = await reader.read();
    if (done) break;
    size += value.byteLength;
    if (size > limit) {
      reader.cancel().catch(() => {});
      throw new TooLarge();
    }
    chunks.push(value);
  }
  const bytes = new Uint8Array(size);
  let offset = 0;
  for (const chunk of chunks) {
    bytes.set(chunk, offset);
    offset += chunk.byteLength;
  }
  return decoder.decode(bytes);
}

function toBase64(bytes) {
  let binary = '';
  for (const byte of bytes) binary += String.fromCharCode(byte);
  return btoa(binary);
}

function toBase64Url(bytes) {
  return toBase64(bytes).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function fromBase64Url(value) {
  if (!/^[A-Za-z0-9_-]*$/.test(value)) return null;
  const base64 = value.replace(/-/g, '+').replace(/_/g, '/');
  let binary;
  try {
    binary = atob(base64.padEnd(base64.length + ((4 - (base64.length % 4)) % 4), '='));
  } catch {
    return null;
  }
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

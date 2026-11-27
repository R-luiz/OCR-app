// Regression tests for the OCR gateway, driven only through its public fetch handler.
// Every outbound request is intercepted: no network, no real GitHub App, no real secret.
//
//   node --test gateway/test/gateway.test.js

import { afterEach, beforeEach, test } from 'node:test';
import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import worker from '../src/index.js';

const ORIGIN = 'https://ocr.robinluiz.fr';
const CALLBACK_URL = `${ORIGIN}/auth/callback`;
const OWNER_ID = 84543755;
const ENDPOINT_ID = '2k90gse2jm67e1';
const RUNPOD = `https://api.runpod.ai/v2/${ENDPOINT_ID}`;
const RUNPOD_KEY = 'rpa_TESTKEY000000000000000000000000000000';
const CLIENT_ID = 'Iv23liTestClient0001';
const CLIENT_SECRET = '0123456789abcdef0123456789abcdef01234567';
const GITHUB_CODE = 'a3f8c0d1e2b4a5968778';
// RFC 7636, appendix B.
const CODE_VERIFIER = 'dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk';
const GITHUB_TOKEN = 'ghu_16C7e42F292c6912E7710c838347Ae178B4a';
const GITHUB_REFRESH_TOKEN = 'ghr_1B4a2e77838347a7E420ce178F2E7c6912E169246c34E1ccbF66C46812d16D5B1A9Dc86A1498';
const SEVEN_DAYS = 7 * 24 * 60 * 60;
const MAX_RUN_BODY_BYTES = 2 * 1024 * 1024;
const START = Date.UTC(2026, 10, 27, 12, 0, 0);
const JSON_TYPE = { 'content-type': 'application/json' };
const RUN_INPUT = { images: ['data:image/jpeg;base64,/9j/4AAQSkZJRg=='] };
const RUN_BODY = JSON.stringify({ input: RUN_INPUT });
const RUN_POLICY = { executionTimeout: 15 * 60 * 1000, ttl: 60 * 60 * 1000 };
const HEALTH = { jobs: { inQueue: 0 }, workers: { idle: 0, ready: 0, running: 0 } };
const OCR_ROUTES = [
  ['POST', '/v2/ocr/run', RUN_BODY],
  ['GET', '/v2/ocr/status/job-1'],
  ['POST', '/v2/ocr/cancel/job-1'],
  ['GET', '/v2/ocr/health'],
];

// --- Outbound requests -----------------------------------------------------------

const realFetch = globalThis.fetch;
/** Every request the gateway sent, in order. */
let outbound;
/** Queued answers, one per expected outbound request. */
let replies;

beforeEach(() => {
  outbound = [];
  replies = [];
  globalThis.fetch = async (url, init = {}) => {
    const call = {
      url: String(url),
      method: init.method ?? 'GET',
      headers: new Headers(init.headers),
      redirect: init.redirect,
      cache: init.cache,
      body: null,
    };
    outbound.push(call);
    if (init.body != null) call.body = await new Response(init.body).text();
    const reply = replies.shift();
    if (!reply) throw new Error(`unexpected outbound request: ${call.method} ${call.url}`);
    return reply(call);
  };
});

afterEach(() => {
  globalThis.fetch = realFetch;
});

function respond(status, body, headers = {}) {
  return () => new Response(typeof body === 'string' ? body : JSON.stringify(body), {
    status,
    headers: { 'content-type': 'application/json; charset=utf-8', ...headers },
  });
}

const tokenGrant = respond(200, {
  access_token: GITHUB_TOKEN,
  token_type: 'bearer',
  scope: '',
  expires_in: 28800,
  refresh_token: GITHUB_REFRESH_TOKEN,
  refresh_token_expires_in: 15811200,
});
const githubUser = (id) => respond(200, { login: 'someone', id, type: 'User' });

// --- Bindings --------------------------------------------------------------------

/** Workers KV in memory: what the gateway stored, and every read with its options. */
function memoryKv() {
  const values = new Map();
  const reads = [];
  return {
    values,
    reads,
    async get(key, options) {
      reads.push({ key, options });
      return values.get(key) ?? null;
    },
    async put(key, value) {
      assert.equal(typeof value, 'string');
      values.set(key, value);
    },
  };
}

/** The Workers rate limiter, `limit` calls per key, with no window to wait out. */
function memoryLimiter(limit) {
  const counts = new Map();
  return {
    keys: [],
    async limit({ key }) {
      this.keys.push(key);
      counts.set(key, (counts.get(key) ?? 0) + 1);
      return { success: counts.get(key) <= limit };
    },
  };
}

// --- Client side -----------------------------------------------------------------

function makeEnv(overrides = {}) {
  return {
    GITHUB_CLIENT_ID: CLIENT_ID,
    GITHUB_CLIENT_SECRET: CLIENT_SECRET,
    GITHUB_OWNER_ID: String(OWNER_ID),
    RUNPOD_ENDPOINT_ID: ENDPOINT_ID,
    RUNPOD_API_KEY: RUNPOD_KEY,
    OCR_SESSION_KEY: Buffer.from(crypto.getRandomValues(new Uint8Array(32))).toString('base64url'),
    SESSION_STATE: memoryKv(),
    ...overrides,
  };
}

function send(env, method, path, { headers = {}, body } = {}) {
  const init = { method, headers };
  if (body !== undefined) {
    init.body = body;
    if (body instanceof ReadableStream) init.duplex = 'half';
  }
  return worker.fetch(new Request(ORIGIN + path, init), env);
}

const bearer = (token) => ({ authorization: `Bearer ${token}` });

function exchange(env, body, headers = {}) {
  return send(env, 'POST', '/auth/token', {
    headers: { ...JSON_TYPE, ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
}

/** A complete sign-in as GitHub user `userId`; returns the `/auth/token` answer. */
async function signIn(env, userId = OWNER_ID) {
  replies.push(tokenGrant, githubUser(userId));
  const response = await exchange(env, { code: GITHUB_CODE, code_verifier: CODE_VERIFIER });
  assert.equal(response.status, 200);
  const answer = await response.json();
  outbound.length = 0;
  return answer;
}

const health = (env, token) => send(env, 'GET', '/v2/ocr/health', { headers: bearer(token) });

/** What RunPod receives for a `/run` body of `input`. */
const forwarded = (input) => JSON.stringify({ input, policy: RUN_POLICY });

function freezeClock(t, at = START) {
  const clock = { now: at };
  t.mock.method(Date, 'now', () => clock.now);
  return clock;
}

/** Changes one character in the middle of `text`, where every base64 bit counts. */
function flip(text) {
  const index = Math.floor(text.length / 2);
  return text.slice(0, index) + (text[index] === 'A' ? 'B' : 'A') + text.slice(index + 1);
}

/** `bytes`, streamed without a declared length. */
function streamOf(bytes, chunkBytes = 64 * 1024) {
  let sent = 0;
  return new ReadableStream({
    pull(controller) {
      if (sent >= bytes.length) {
        controller.close();
        return;
      }
      controller.enqueue(bytes.slice(sent, sent + chunkBytes));
      sent += chunkBytes;
    },
  });
}

/** A `/run` body of exactly `totalBytes`: one image, padded. */
function jobOf(totalBytes) {
  const envelope = JSON.stringify({ input: { images: ['data:image/jpeg;base64,'] } });
  const image = `data:image/jpeg;base64,${'A'.repeat(totalBytes - envelope.length)}`;
  return { images: [image] };
}

function inlineBlock(html, tag) {
  return new RegExp(`<${tag}>([\\s\\S]*?)</${tag}>`).exec(html)[1];
}

const sha256Source = (text) => `'sha256-${createHash('sha256').update(text).digest('base64')}'`;

/** Runs the callback page's script against a browser address with `search`. */
function runCallbackScript(script, search) {
  const link = { hidden: true, href: '' };
  const replaced = [];
  new Function('location', 'document', 'history', script)(
    { search, pathname: '/auth/callback' },
    { getElementById: (id) => (id === 'open' ? link : null) },
    { replaceState: (...args) => replaced.push(args) },
  );
  return { link, replaced };
}

// --- Tests -----------------------------------------------------------------------

test('the public root reports readiness and calls nothing', async () => {
  const response = await send(makeEnv(), 'GET', '/');
  assert.equal(response.status, 200);
  assert.deepEqual(await response.json(), { status: 'ok' });
  assert.equal(response.headers.get('cache-control'), 'no-store');
  assert.equal(outbound.length, 0);
});

test('the owner signs in with a GitHub code and its PKCE verifier', async (t) => {
  freezeClock(t);
  const env = makeEnv();

  replies.push(tokenGrant, githubUser(OWNER_ID));
  const authorized = await exchange(
    env,
    { code: GITHUB_CODE, code_verifier: CODE_VERIFIER },
    { cookie: 'a=b', 'x-forwarded-for': '203.0.113.7', 'user-agent': 'okhttp/4.12.0' },
  );
  assert.equal(authorized.status, 200);
  assert.equal(authorized.headers.get('cache-control'), 'no-store');
  const text = await authorized.text();
  assert.ok(!text.includes(GITHUB_TOKEN), 'the GitHub token is never relayed');
  assert.ok(!text.includes(GITHUB_REFRESH_TOKEN), 'the GitHub refresh token is never relayed');
  const session = JSON.parse(text);
  assert.deepEqual(Object.keys(session).sort(), ['expires_at', 'status', 'token']);
  assert.equal(session.status, 'authorized');
  assert.equal(session.expires_at, START / 1000 + SEVEN_DAYS);

  const [tokenRequest, userRequest] = outbound;
  assert.equal(tokenRequest.url, 'https://github.com/login/oauth/access_token');
  assert.equal(tokenRequest.method, 'POST');
  assert.equal(tokenRequest.redirect, 'manual');
  assert.deepEqual([...tokenRequest.headers.keys()].sort(), ['accept', 'content-type', 'user-agent']);
  assert.equal(tokenRequest.headers.get('user-agent'), 'ocr-app-gateway');
  assert.deepEqual(Object.fromEntries(new URLSearchParams(tokenRequest.body)), {
    client_id: CLIENT_ID,
    client_secret: CLIENT_SECRET,
    code: GITHUB_CODE,
    code_verifier: CODE_VERIFIER,
    redirect_uri: CALLBACK_URL,
  });
  assert.equal(userRequest.url, 'https://api.github.com/user');
  assert.equal(userRequest.method, 'GET');
  assert.equal(userRequest.redirect, 'manual');
  assert.equal(userRequest.headers.get('authorization'), `Bearer ${GITHUB_TOKEN}`);
  assert.deepEqual(
    [...userRequest.headers.keys()].sort(),
    ['accept', 'authorization', 'user-agent', 'x-github-api-version'],
  );
  // The new session voids every earlier one.
  assert.equal(env.SESSION_STATE.values.get('not_before'), String(START / 1000));

  outbound.length = 0;
  replies.push(respond(200, HEALTH));
  const reply = await health(env, session.token);
  assert.equal(reply.status, 200);
  assert.deepEqual(await reply.json(), HEALTH);
  assert.equal(outbound[0].url, `${RUNPOD}/health`);
});

test('a code GitHub refuses is invalid, and GitHub failures are errors', async () => {
  const env = makeEnv();
  const attempt = { code: GITHUB_CODE, code_verifier: CODE_VERIFIER };

  // Wrong, expired, already used, or issued for another verifier.
  for (const refusal of [
    respond(200, { error: 'bad_verification_code', error_description: 'The code passed is incorrect or expired.' }),
    respond(400, { error: 'invalid_grant' }),
  ]) {
    replies.push(refusal);
    const response = await exchange(env, attempt);
    assert.equal(response.status, 200);
    assert.deepEqual(await response.json(), { status: 'invalid' });
  }

  // Misconfiguration of the GitHub App, and outages, are errors rather than statuses.
  const failures = [
    respond(200, { error: 'incorrect_client_credentials' }),
    respond(200, { error: 'redirect_uri_mismatch' }),
    respond(401, { error: 'unauthorized_client' }),
    respond(503, 'unavailable', { 'content-type': 'text/plain' }),
    respond(200, '<html></html>', { 'content-type': 'text/html' }),
    () => {
      throw new TypeError('network down');
    },
  ];
  for (const failure of failures) {
    replies.push(failure);
    const response = await exchange(env, attempt);
    assert.equal(response.status, 502);
    assert.equal(typeof (await response.json()).error, 'string');
  }
  assert.equal(replies.length, 0);
  assert.equal(env.SESSION_STATE.values.size, 0, 'no session was issued');
});

test('a GitHub account other than the owner is refused', async () => {
  const env = makeEnv();
  assert.deepEqual(await signIn(env, 1), { status: 'forbidden' });

  // Only GitHub's numeric id counts: an id in any other shape admits no one.
  replies.push(tokenGrant, githubUser(String(OWNER_ID)));
  const response = await exchange(env, { code: GITHUB_CODE, code_verifier: CODE_VERIFIER });
  assert.equal(response.status, 502);
  assert.equal((await response.json()).token, undefined);
  assert.equal(env.SESSION_STATE.values.size, 0, 'a refused account voids no session');
});

test('only a well-formed code and verifier reach GitHub', async () => {
  const env = makeEnv();
  const malformed = [
    [JSON_TYPE, JSON.stringify({ code: GITHUB_CODE }), 400],
    [JSON_TYPE, JSON.stringify({ code_verifier: CODE_VERIFIER }), 400],
    [JSON_TYPE, JSON.stringify({ code: GITHUB_CODE, code_verifier: 'too-short' }), 400],
    [JSON_TYPE, JSON.stringify({ code: GITHUB_CODE, code_verifier: 'A'.repeat(129) }), 400],
    [JSON_TYPE, JSON.stringify({ code: GITHUB_CODE, code_verifier: `${CODE_VERIFIER}+/=` }), 400],
    [JSON_TYPE, JSON.stringify({ code: '', code_verifier: CODE_VERIFIER }), 400],
    [JSON_TYPE, JSON.stringify({ code: 'a b', code_verifier: CODE_VERIFIER }), 400],
    [JSON_TYPE, JSON.stringify({ code: 42, code_verifier: CODE_VERIFIER }), 400],
    [JSON_TYPE, JSON.stringify([GITHUB_CODE, CODE_VERIFIER]), 400],
    [JSON_TYPE, 'not json', 400],
    [JSON_TYPE, undefined, 400],
    [{ 'content-type': 'application/x-www-form-urlencoded' }, `code=${GITHUB_CODE}&code_verifier=${CODE_VERIFIER}`, 415],
    [JSON_TYPE, JSON.stringify({ code: GITHUB_CODE, code_verifier: CODE_VERIFIER, padding: 'x'.repeat(8192) }), 413],
  ];
  for (const [headers, body, status] of malformed) {
    const response = await send(env, 'POST', '/auth/token', { headers, body });
    assert.equal(response.status, status, String(body).slice(0, 60));
  }
  assert.equal(outbound.length, 0);
});

test('assetlinks.json ties the callback to the app and its two signing keys', async () => {
  const response = await send(makeEnv(), 'GET', '/.well-known/assetlinks.json');
  assert.equal(response.status, 200);
  assert.match(response.headers.get('content-type'), /^application\/json/);
  assert.deepEqual(await response.json(), [{
    relation: ['delegate_permission/common.handle_all_urls'],
    target: {
      namespace: 'android_app',
      package_name: 'com.ocrapp',
      sha256_cert_fingerprints: [
        'D9:55:E3:37:CA:10:6C:D4:00:52:C5:5A:20:41:71:E8:75:77:42:D7:A1:62:A5:68:8A:D8:24:7C:9B:6F:CB:CE',
        '3F:9C:A8:EE:70:BB:5C:C4:A9:6E:C7:B2:48:92:78:56:76:9D:80:A1:6B:F0:4E:EE:36:C2:D1:E1:2C:EE:C6:E2',
      ],
    },
  }]);
  assert.equal(outbound.length, 0);
});

test('the callback page never echoes the query and runs only its pinned script', async () => {
  const env = makeEnv();
  const query = `code=${GITHUB_CODE}&state=st4te_Value-123`;
  const withQuery = await send(env, 'GET', `/auth/callback?${query}`);
  const bare = await send(env, 'GET', '/auth/callback');
  assert.equal(withQuery.status, 200);
  assert.match(withQuery.headers.get('content-type'), /^text\/html; charset=utf-8/);
  assert.equal(withQuery.headers.get('cache-control'), 'no-store');
  assert.equal(withQuery.headers.get('referrer-policy'), 'no-referrer');
  const html = await withQuery.text();
  assert.ok(!html.includes(GITHUB_CODE) && !html.includes('st4te_Value'), 'the query stays in the browser');
  assert.equal(html, await bare.text());

  const script = inlineBlock(html, 'script');
  const policy = withQuery.headers.get('content-security-policy').split('; ');
  assert.deepEqual(policy, [
    "default-src 'none'",
    `script-src ${sha256Source(script)}`,
    `style-src ${sha256Source(inlineBlock(html, 'style'))}`,
    "base-uri 'none'",
    "form-action 'none'",
    "frame-ancestors 'none'",
  ]);

  // In the browser: a link that hands the same query to the app, then the code leaves the address bar.
  const back = runCallbackScript(script, `?${query}`);
  assert.equal(back.link.hidden, false);
  assert.equal(
    back.link.href,
    `intent://ocr.robinluiz.fr/auth/callback?${query}#Intent;scheme=https;package=com.ocrapp;end`,
  );
  assert.deepEqual(back.replaced, [[null, '', '/auth/callback']]);
  // GitHub's refusal carries the state too, so the app can end the sign-in.
  assert.equal(runCallbackScript(script, '?error=access_denied&state=abc').link.hidden, false);
  // Without a state there is nothing to hand back.
  const stray = runCallbackScript(script, '');
  assert.equal(stray.link.hidden, true);
  assert.deepEqual(stray.replaced, []);

  assert.equal(outbound.length, 0);
});

test('only the newest session is accepted, and signing out voids it', async (t) => {
  const clock = freezeClock(t);
  const env = makeEnv();
  const kv = env.SESSION_STATE;
  const { token: first } = await signIn(env);
  clock.now += 1000;
  const { token: second } = await signIn(env);

  assert.equal((await health(env, first)).status, 401);
  replies.push(respond(200, HEALTH));
  assert.equal((await health(env, second)).status, 200);
  assert.ok(kv.reads.length > 0);
  for (const read of kv.reads) assert.deepEqual(read, { key: 'not_before', options: { cacheTtl: 60 } });

  const signedOut = await send(env, 'POST', '/auth/signout', { headers: bearer(second) });
  assert.equal(signedOut.status, 200);
  assert.deepEqual(await signedOut.json(), { status: 'signed_out' });
  assert.equal(kv.values.get('not_before'), String(START / 1000 + 2));
  assert.equal((await health(env, second)).status, 401);
  for (const headers of [bearer(second), bearer(first), {}]) {
    const response = await send(env, 'POST', '/auth/signout', { headers });
    assert.equal(response.status, 401);
    assert.match(response.headers.get('www-authenticate'), /^Bearer/);
  }
  assert.equal(kv.values.get('not_before'), String(START / 1000 + 2), 'a refused sign-out writes nothing');

  clock.now += 60 * 1000;
  const { token: third } = await signIn(env);
  replies.push(respond(200, HEALTH));
  assert.equal((await health(env, third)).status, 200);

  // A stored value that is not a time admits no session at all.
  kv.values.set('not_before', 'garbage');
  assert.equal((await health(env, third)).status, 401);
  assert.equal(outbound.length, 1, 'only the third session reached RunPod since it was issued');
});

test('a session store failure is a gateway error, not a sign-out', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const broken = {
    ...env,
    SESSION_STATE: {
      get: async () => {
        throw new Error('KV unavailable');
      },
      put: async () => {
        throw new Error('KV unavailable');
      },
    },
  };
  assert.equal((await health(broken, token)).status, 500);
  assert.equal((await send(broken, 'POST', '/auth/signout', { headers: bearer(token) })).status, 500);

  replies.push(tokenGrant, githubUser(OWNER_ID));
  const response = await exchange(broken, { code: GITHUB_CODE, code_verifier: CODE_VERIFIER });
  assert.equal(response.status, 500);
  assert.equal((await response.json()).token, undefined, 'no session that could not void the older ones');
});

test('sign-in calls are rate limited per route and client address', async () => {
  const limiter = memoryLimiter(30);
  const env = makeEnv({ AUTH_LIMITER: limiter });
  const from = (ip) => ({ 'cf-connecting-ip': ip });
  const attempt = (ip) => exchange(env, { code: GITHUB_CODE }, from(ip));

  for (let i = 0; i < 30; i++) assert.equal((await attempt('203.0.113.7')).status, 400);
  const refused = await attempt('203.0.113.7');
  assert.equal(refused.status, 429);
  assert.equal(refused.headers.get('retry-after'), '60');
  assert.equal(typeof (await refused.json()).error, 'string');

  // Another address, and the other route, have counts of their own.
  assert.equal((await attempt('198.51.100.4')).status, 400);
  assert.equal((await send(env, 'POST', '/auth/signout', { headers: from('203.0.113.7') })).status, 401);
  // The OCR routes and the public pages are not counted.
  await send(env, 'GET', '/v2/ocr/health');
  await send(env, 'GET', '/auth/callback');
  assert.deepEqual(new Set(limiter.keys), new Set([
    '/auth/token:203.0.113.7',
    '/auth/token:198.51.100.4',
    '/auth/signout:203.0.113.7',
  ]));
  assert.equal(outbound.length, 0);

  // Without the binding there is no limit, rather than no sign-in.
  const unlimited = makeEnv();
  for (let i = 0; i < 40; i++) {
    assert.equal((await exchange(unlimited, { code: GITHUB_CODE }, from('203.0.113.7'))).status, 400);
  }
});

test('anonymous OCR calls are refused without reaching RunPod', async () => {
  const env = makeEnv();
  const credentials = [
    {},
    { authorization: 'Bearer' },
    { authorization: `Basic ${btoa('owner:password')}` },
    bearer(RUNPOD_KEY),
    bearer(GITHUB_TOKEN),
    bearer(`ocr1.${'A'.repeat(32)}.${'A'.repeat(43)}`),
  ];
  for (const [method, path, body] of OCR_ROUTES) {
    for (const authorization of credentials) {
      const response = await send(env, method, path, { headers: { ...authorization, ...JSON_TYPE }, body });
      assert.equal(response.status, 401, `${method} ${path}`);
      assert.match(response.headers.get('www-authenticate'), /^Bearer/);
    }
  }
  assert.equal(outbound.length, 0);
});

test('expired, tampered, foreign and former-owner sessions are refused', async (t) => {
  const clock = freezeClock(t);
  const env = makeEnv();
  const { token, expires_at: expiresAt } = await signIn(env);
  const { token: foreign } = await signIn(makeEnv());
  // Same signing key, but issued while another account was the configured owner.
  const { token: formerOwner } = await signIn({ ...env, GITHUB_OWNER_ID: '1' }, 1);

  const [prefix, payload, signature] = token.split('.');
  const extended = Buffer.from(JSON.stringify({
    sub: OWNER_ID, scp: 'ocr', iat: expiresAt, exp: expiresAt + SEVEN_DAYS,
  })).toString('base64url');
  const refused = [
    `${prefix}.${flip(payload)}.${signature}`,
    `${prefix}.${payload}.${flip(signature)}`,
    `${prefix}.${extended}.${signature}`,
    `${prefix}.${payload}.${signature}A`,
    `ocr2.${payload}.${signature}`,
    foreign,
    formerOwner,
  ];
  for (const candidate of refused) {
    assert.equal((await health(env, candidate)).status, 401, candidate);
  }
  // A session is read from the Authorization header only, never from a URL or a cookie.
  for (const [path, headers] of [
    [`/v2/ocr/health?token=${token}`, {}],
    [`/v2/ocr/health?access_token=${token}`, {}],
    ['/v2/ocr/health', { cookie: `token=${token}` }],
  ]) {
    assert.equal((await send(env, 'GET', path, { headers })).status, 401);
  }
  assert.equal(outbound.length, 0);

  // Valid for seven days, then no longer.
  clock.now = (expiresAt - 1) * 1000;
  replies.push(respond(200, HEALTH));
  assert.equal((await health(env, token)).status, 200);
  clock.now = expiresAt * 1000;
  assert.equal((await health(env, token)).status, 401);
  assert.equal(outbound.length, 1);
});

test('a session relays run, status, cancel and health to the fixed endpoint', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const auth = bearer(token);

  const queued = { id: 'job-1', status: 'IN_QUEUE' };
  const completed = { id: 'job-1', status: 'COMPLETED', output: { markdown: '# Page', pages: [] } };
  const cancelled = { id: 'job-1', status: 'CANCELLED' };

  replies.push(respond(200, queued));
  const run = await send(env, 'POST', '/v2/ocr/run', { headers: { ...auth, ...JSON_TYPE }, body: RUN_BODY });
  assert.equal(run.status, 200);
  assert.deepEqual(await run.json(), queued);

  // The same, with the length declared as the app's HTTP client does.
  replies.push(respond(200, queued));
  const declared = await send(env, 'POST', '/v2/ocr/run', {
    headers: { ...auth, ...JSON_TYPE, 'content-length': String(Buffer.byteLength(RUN_BODY)) },
    body: RUN_BODY,
  });
  assert.equal(declared.status, 200);
  assert.deepEqual(await declared.json(), queued);

  replies.push(respond(200, completed));
  const status = await send(env, 'GET', '/v2/ocr/status/job-1', { headers: auth });
  assert.equal(status.status, 200);
  assert.deepEqual(await status.json(), completed);

  replies.push(respond(200, cancelled));
  const cancel = await send(env, 'POST', '/v2/ocr/cancel/job-1', { headers: auth });
  assert.equal(cancel.status, 200);
  assert.deepEqual(await cancel.json(), cancelled);

  replies.push(respond(200, HEALTH));
  const healthReply = await send(env, 'GET', '/v2/ocr/health', { headers: auth });
  assert.equal(healthReply.status, 200);
  assert.deepEqual(await healthReply.json(), HEALTH);

  assert.deepEqual(outbound.map((call) => [call.method, call.url]), [
    ['POST', `${RUNPOD}/run`],
    ['POST', `${RUNPOD}/run`],
    ['GET', `${RUNPOD}/status/job-1`],
    ['POST', `${RUNPOD}/cancel/job-1`],
    ['GET', `${RUNPOD}/health`],
  ]);
  for (const call of outbound) {
    assert.equal(call.headers.get('authorization'), `Bearer ${RUNPOD_KEY}`);
    assert.equal(call.redirect, 'manual');
    assert.equal(call.cache, 'no-store');
  }
  assert.equal(outbound[0].body, forwarded(RUN_INPUT));
  assert.equal(outbound[1].body, forwarded(RUN_INPUT));
  assert.equal(outbound[3].body, '');
  for (const response of [run, status, cancel, healthReply]) {
    assert.equal(response.headers.get('cache-control'), 'no-store');
  }
});

test('a job carries only its input, under the gateway\'s own policy', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const headers = { ...bearer(token), ...JSON_TYPE };

  const refused = [
    JSON.stringify({ input: RUN_INPUT, policy: { executionTimeout: 7 * 24 * 3600 * 1000, ttl: 7 * 24 * 3600 * 1000 } }),
    JSON.stringify({ input: RUN_INPUT, webhook: 'https://collector.example/hook' }),
    JSON.stringify({ input: RUN_INPUT, s3Config: { bucketName: 'elsewhere' } }),
    `{"__proto__": {}, "input": ${JSON.stringify(RUN_INPUT)}}`,
    JSON.stringify({}),
    JSON.stringify({ images: RUN_INPUT.images }),
    JSON.stringify({ input: [RUN_INPUT] }),
    JSON.stringify({ input: 'images' }),
    JSON.stringify({ input: null }),
    JSON.stringify([{ input: RUN_INPUT }]),
    '"text"',
    'not json',
  ];
  for (const body of refused) {
    const response = await send(env, 'POST', '/v2/ocr/run', { headers, body });
    assert.equal(response.status, 400, body.slice(0, 60));
    assert.equal(typeof (await response.json()).error, 'string');
  }
  assert.equal(outbound.length, 0);

  // What `input` holds is the worker's business: it is passed on as sent.
  const input = { images: ['data:image/png;base64,iVBORw0KGgo='], prompt: 'anything', nested: { a: [1, 2] } };
  replies.push(respond(200, { id: 'job-3', status: 'IN_QUEUE' }));
  const accepted = await send(env, 'POST', '/v2/ocr/run', { headers, body: JSON.stringify({ input }) });
  assert.equal(accepted.status, 200);
  assert.equal(outbound[0].body, forwarded(input));
  assert.deepEqual(JSON.parse(outbound[0].body).policy, { executionTimeout: 900000, ttl: 3600000 });
});

test('RunPod sees only its key and the protocol headers', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);

  replies.push(respond(200, { id: 'job-2', status: 'IN_QUEUE' }, {
    'set-cookie': 'tracker=1',
    'x-runpod-internal': 'worker-7',
    'access-control-allow-origin': '*',
  }));
  const response = await send(env, 'POST', '/v2/ocr/run?endpoint=other&key=leak', {
    headers: {
      ...bearer(token),
      ...JSON_TYPE,
      cookie: 'session=abc',
      'user-agent': 'okhttp/4.12.0',
      'x-forwarded-for': '203.0.113.7',
      'cf-connecting-ip': '203.0.113.7',
      'x-device-id': 'pixel-8',
      'accept-language': 'fr-FR',
    },
    body: RUN_BODY,
  });
  assert.equal(response.status, 200);

  const [call] = outbound;
  assert.equal(call.url, `${RUNPOD}/run`, 'no query string is forwarded');
  assert.deepEqual([...call.headers.keys()].sort(), ['accept', 'authorization', 'content-type']);
  assert.equal(call.headers.get('authorization'), `Bearer ${RUNPOD_KEY}`, 'the session token stays at the gateway');

  assert.equal(response.headers.get('set-cookie'), null);
  assert.equal(response.headers.get('x-runpod-internal'), null);
  assert.equal(response.headers.get('access-control-allow-origin'), null);
  const text = await response.text();
  assert.ok(!text.includes(RUNPOD_KEY));
});

test('only the four OCR routes and the sign-in routes exist', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const cases = [
    ['POST', '/v2/ocr/runsync', 404],
    ['POST', '/v2/ocr/purge-queue', 404],
    ['GET', '/v2/ocr/stream/job-1', 404],
    ['GET', `/v2/${ENDPOINT_ID}/health`, 404],
    ['POST', `/v2/${ENDPOINT_ID}/run`, 404],
    ['GET', '/v2/ocr/status/job-1/extra', 404],
    ['GET', '/v2/ocr/status/a%2Fb', 404],
    ['GET', '/v2/ocr/status/', 404],
    ['POST', '/v2/ocr/run/', 404],
    ['GET', '/v2/ocr/run', 405],
    ['PUT', '/v2/ocr/run', 405],
    ['POST', '/v2/ocr/health', 405],
    ['GET', '/v2/ocr/cancel/job-1', 405],
    ['POST', '/v2/ocr/status/job-1', 405],
    ['POST', '/auth/device', 404],
    ['GET', '/auth/device', 404],
    ['GET', '/auth/token', 405],
    ['GET', '/auth/signout', 405],
    ['POST', '/auth/callback', 405],
    ['POST', '/.well-known/assetlinks.json', 405],
    ['POST', '/', 405],
  ];
  for (const [method, path, expected] of cases) {
    const body = method === 'GET' ? undefined : RUN_BODY;
    const response = await send(env, method, path, { headers: { ...bearer(token), ...JSON_TYPE }, body });
    assert.equal(response.status, expected, `${method} ${path}`);
  }
  assert.equal(outbound.length, 0);
});

test('upstream redirects are refused, not followed', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const redirect = () => new Response(null, {
    status: 307,
    headers: { location: 'https://collector.example/steal' },
  });

  for (const [method, path, body] of OCR_ROUTES) {
    outbound.length = 0;
    replies.push(redirect);
    const response = await send(env, method, path, { headers: { ...bearer(token), ...JSON_TYPE }, body });
    assert.equal(response.status, 502, `${method} ${path}`);
    assert.equal(response.headers.get('location'), null);
    assert.equal(outbound.length, 1);
    assert.equal(outbound[0].redirect, 'manual');
  }

  outbound.length = 0;
  replies.push(redirect);
  const codeExchange = await exchange(env, { code: GITHUB_CODE, code_verifier: CODE_VERIFIER });
  assert.equal(codeExchange.status, 502);
  assert.equal(codeExchange.headers.get('location'), null);
  assert.equal(outbound.length, 1);
});

test('RunPod statuses and payloads come back as RunPod sent them', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);

  // [RunPod's answer, status the app gets, body the app gets (null: a gateway error)]
  const cases = [
    [respond(404, { error: 'job not found' }), 404, { error: 'job not found' }],
    [respond(400, { error: 'invalid input' }), 400, { error: 'invalid input' }],
    [respond(429, { error: 'rate limited' }, { 'retry-after': '7' }), 429, { error: 'rate limited' }],
    [respond(500, '<html>oops</html>', { 'content-type': 'text/html' }), 500, null],
    // The gateway's RunPod key is refused: that must not read as a dead session.
    [respond(401, { error: 'Unauthorized' }), 502, null],
    [respond(403, 'Forbidden', { 'content-type': 'text/plain' }), 502, null],
    [respond(200, 'ok', { 'content-type': 'text/plain' }), 502, null],
    [() => {
      throw new TypeError('connection reset');
    }, 502, null],
  ];
  for (const [answer, expectedStatus, expectedBody] of cases) {
    replies.push(answer);
    const response = await send(env, 'GET', '/v2/ocr/status/job-1', { headers: bearer(token) });
    assert.equal(response.status, expectedStatus);
    assert.match(response.headers.get('content-type'), /^application\/json/);
    const body = await response.json();
    if (expectedBody) assert.deepEqual(body, expectedBody);
    else assert.equal(typeof body.error, 'string');
    if (expectedStatus === 429) assert.equal(response.headers.get('retry-after'), '7');
  }
});

test('scan uploads are typed and bounded before any job starts', async () => {
  const env = makeEnv();
  const { token } = await signIn(env);
  const auth = bearer(token);

  const tooLong = await send(env, 'POST', '/v2/ocr/run', {
    headers: { ...auth, ...JSON_TYPE, 'content-length': String(MAX_RUN_BODY_BYTES + 1) },
    body: RUN_BODY,
  });
  assert.equal(tooLong.status, 413);
  const notJson = await send(env, 'POST', '/v2/ocr/run', {
    headers: { ...auth, 'content-type': 'text/plain' },
    body: RUN_BODY,
  });
  assert.equal(notJson.status, 415);
  const empty = await send(env, 'POST', '/v2/ocr/run', { headers: { ...auth, ...JSON_TYPE } });
  assert.equal(empty.status, 400);

  // No declared length: the body is cut off as soon as it passes the limit.
  const overflow = await send(env, 'POST', '/v2/ocr/run', {
    headers: { ...auth, ...JSON_TYPE },
    body: streamOf(new Uint8Array(MAX_RUN_BODY_BYTES + 1).fill(0x61)),
  });
  assert.equal(overflow.status, 413);
  assert.equal(outbound.length, 0, 'RunPod receives nothing');

  // A scan upload at the limit goes through, with or without a declared length.
  const largest = MAX_RUN_BODY_BYTES;
  const input = jobOf(largest);
  const body = new TextEncoder().encode(JSON.stringify({ input }));
  assert.equal(body.length, largest);
  for (const headers of [{}, { 'content-length': String(largest) }]) {
    outbound.length = 0;
    replies.push(respond(200, { id: 'job-big', status: 'IN_QUEUE' }));
    const response = await send(env, 'POST', '/v2/ocr/run', {
      headers: { ...auth, ...JSON_TYPE, ...headers },
      body: streamOf(body),
    });
    assert.equal(response.status, 200);
    assert.equal(outbound[0].body, forwarded(input));
  }
});

test('missing or malformed configuration fails shut', async () => {
  const good = makeEnv();
  const { token } = await signIn(good);
  const broken = [
    { GITHUB_CLIENT_ID: '' },
    { GITHUB_CLIENT_ID: undefined },
    { GITHUB_CLIENT_ID: 'Iv23 li&scope=repo' },
    { GITHUB_CLIENT_SECRET: undefined },
    { GITHUB_CLIENT_SECRET: 'short' },
    { GITHUB_CLIENT_SECRET: `${CLIENT_SECRET}&redirect_uri=x` },
    { GITHUB_OWNER_ID: undefined },
    { GITHUB_OWNER_ID: 'octocat' },
    { GITHUB_OWNER_ID: '0' },
    { RUNPOD_ENDPOINT_ID: undefined },
    { RUNPOD_ENDPOINT_ID: '../2k90gse2jm67e1' },
    { RUNPOD_API_KEY: undefined },
    { RUNPOD_API_KEY: 'key with spaces in it' },
    { OCR_SESSION_KEY: undefined },
    { OCR_SESSION_KEY: 'too-short' },
    { OCR_SESSION_KEY: Buffer.alloc(16, 1).toString('base64url') },
    { SESSION_STATE: undefined },
    { SESSION_STATE: { get: good.SESSION_STATE.get } },
  ];
  for (const override of broken) {
    const env = { ...good, ...override };
    const label = JSON.stringify(override);
    assert.equal((await send(env, 'GET', '/')).status, 503, label);
    const attempt = await exchange(env, { code: GITHUB_CODE, code_verifier: CODE_VERIFIER });
    assert.equal(attempt.status, 503, label);
    assert.equal((await send(env, 'POST', '/auth/signout', { headers: bearer(token) })).status, 503, label);
    for (const [method, path, body] of OCR_ROUTES) {
      const response = await send(env, method, path, { headers: { ...bearer(token), ...JSON_TYPE }, body });
      assert.equal(response.status, 503, `${label} ${method} ${path}`);
    }
  }
  assert.equal(outbound.length, 0);
});

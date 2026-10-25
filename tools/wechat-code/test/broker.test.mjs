import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createBroker } from '../server.mjs';
import { APP_ID } from '../cdp.mjs';

const token = 'a'.repeat(43);
const code = 'b'.repeat(32);
async function withBroker(options, run) {
  const server = createBroker({ token, provider: async () => code, cooldownMs: 0, ...options });
  server.listen(0, '127.0.0.1');
  await once(server, 'listening');
  const base = `http://127.0.0.1:${server.address().port}`;
  const post = (body = { appId: APP_ID }, headers = {}, endpoint = '/v1/code') => fetch(base + endpoint, {
    method: 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json', ...headers },
    body: typeof body === 'string' ? body : JSON.stringify(body),
  });
  try { await run({ base, post }); }
  finally { server.closeAllConnections(); await new Promise(resolve => server.close(resolve)); }
}

test('authorized code request returns only app identity and code, never caches', async () => {
  await withBroker({}, async ({ post }) => {
    const response = await post();
    assert.equal(response.status, 200);
    assert.equal(response.headers.get('cache-control'), 'no-store');
    assert.deepEqual(await response.json(), { appId: APP_ID, code });
  });
});

test('rejects missing or incorrect bearer token before invoking provider', async () => {
  let calls = 0;
  await withBroker({ provider: async () => { calls++; return code; } }, async ({ post }) => {
    for (const authorization of ['', 'Bearer incorrect']) {
      assert.equal((await post(undefined, { Authorization: authorization })).status, 401);
    }
    assert.equal(calls, 0);
  });
});

test('rejects browser origins, unsupported app IDs, extra fields and query tokens', async () => {
  let calls = 0;
  await withBroker({ provider: async () => { calls++; return code; } }, async ({ post }) => {
    assert.equal((await post(undefined, { Origin: 'https://example.com' })).status, 403);
    assert.equal((await post({ appId: 'other-app' })).status, 400);
    assert.equal((await post({ appId: APP_ID, session: 'do-not-send' })).status, 400);
    assert.equal((await post(undefined, {}, '/v1/code?token=secret')).status, 404);
    assert.equal(calls, 0);
  });
});

test('rejects malformed, oversized and non-JSON bodies', async () => {
  await withBroker({}, async ({ post }) => {
    assert.equal((await post('{invalid')).status, 400);
    assert.equal((await post('x'.repeat(1025))).status, 413);
    assert.equal((await post(undefined, { 'Content-Type': 'text/plain' })).status, 415);
  });
});

test('provider errors and malformed codes cannot leak secrets', async () => {
  for (const provider of [async () => { throw new Error('secret-cookie'); }, async () => 'bad-code']) {
    await withBroker({ provider }, async ({ post }) => {
      const response = await post();
      assert.equal(response.status, 503);
      assert.doesNotMatch(await response.text(), /secret-cookie|bad-code/);
    });
  }
});

test('serializes minting and applies a cooldown', async () => {
  let release;
  let started;
  const began = new Promise(resolve => { started = resolve; });
  const blocked = new Promise(resolve => { release = resolve; });
  await withBroker({ cooldownMs: 3000, provider: async () => { started(); await blocked; return code; } }, async ({ post }) => {
    const first = post();
    await began;
    assert.equal((await post()).status, 429);
    release();
    assert.equal((await first).status, 200);
    assert.equal((await post()).status, 429);
  });
});

test('health requires pairing but does not call wx.login', async () => {
  await withBroker({ provider: async () => { throw new Error('should-not-run'); } }, async ({ base }) => {
    assert.equal((await fetch(base + '/health')).status, 401);
    const response = await fetch(base + '/health', { headers: { Authorization: `Bearer ${token}` } });
    assert.deepEqual(await response.json(), { ok: true, appId: APP_ID });
  });
});

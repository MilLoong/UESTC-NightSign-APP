import { test } from 'node:test';
import assert from 'node:assert/strict';
import { APP_ID, CdpSession, getLoginCode } from '../cdp.mjs';

function fakeSocket({ targetAppId = APP_ID } = {}) {
  const commands = [];
  class Socket extends EventTarget {
    constructor(url) {
      super();
      assert.equal(url, 'ws://127.0.0.1:62000');
      queueMicrotask(() => this.dispatchEvent(new Event('open')));
    }
    send(raw) {
      const request = JSON.parse(raw);
      commands.push(request);
      queueMicrotask(() => {
        const send = value => this.dispatchEvent(new MessageEvent('message', { data: JSON.stringify(value) }));
        if (request.method === 'Runtime.enable') {
          send({ method: 'Runtime.executionContextCreated', params: { context: { id: 1 } } });
          send({ method: 'Runtime.executionContextCreated', params: { context: { id: 2 } } });
        }
        let result = {};
        if (request.method === 'Runtime.evaluate') {
          const value = request.params.expression.includes('new Promise') ? 'c'.repeat(32) :
            { appId: request.params.contextId === 1 ? 'unrelated-app' : targetAppId, login: true };
          result = { result: { value } };
        }
        send({ id: request.id, result });
      });
    }
    close() { this.dispatchEvent(new Event('close')); }
  }
  return { Socket, commands };
}

test('identifies app before calling wx.login and refreshes contexts on reconnect', async () => {
  const { Socket, commands } = fakeSocket();
  assert.equal(await getLoginCode({ Socket }), 'c'.repeat(32));
  assert.deepEqual(commands.slice(0, 2).map(item => item.method), ['Runtime.disable', 'Runtime.enable']);
  const logins = commands.filter(item => item.params?.expression?.includes('new Promise'));
  assert.equal(logins.length, 1);
  assert.equal(logins[0].params.contextId, 2);
  assert.equal(commands.some(item => /getStorageSync|wx.request/.test(item.params?.expression || '')), false);
});

test('never calls wx.login in an unrelated app', async () => {
  const { Socket, commands } = fakeSocket({ targetAppId: 'different-app' });
  await assert.rejects(getLoginCode({ Socket }), /TARGET_NOT_OPEN/);
  assert.equal(commands.some(item => item.params?.expression?.includes('new Promise')), false);
});

test('timeouts release pending calls without exposing protocol data', async () => {
  const socket = new EventTarget();
  socket.send = () => {};
  socket.close = () => {};
  const session = new CdpSession(socket, 5);
  await assert.rejects(session.command('Runtime.enable'), /CDP_TIMEOUT/);
  assert.equal(session.pending.size, 0);
  session.close();
});

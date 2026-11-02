'use strict';
// End-to-end check of the relay protocol with a fake phone and a fake helper.
const assert = require('assert');
const { WebSocket } = require('ws');
const { server } = require('../server');

function open(url) {
  return new Promise((res, rej) => {
    const ws = new WebSocket(url);
    ws.queue = [];
    ws.waiters = [];
    ws.on('message', (data, isBinary) => {
      const m = isBinary ? { binary: data } : JSON.parse(data.toString());
      const w = ws.waiters.shift();
      if (w) w(m); else ws.queue.push(m);
    });
    ws.once('open', () => res(ws));
    ws.once('error', rej);
  });
}
function next(ws, ms = 2000) {
  if (ws.queue.length) return Promise.resolve(ws.queue.shift());
  return new Promise((res, rej) => {
    const t = setTimeout(() => rej(new Error('timeout')), ms);
    ws.waiters.push((m) => { clearTimeout(t); res(m); });
  });
}
const send = (ws, o) => ws.send(JSON.stringify(o));

(async () => {
  await new Promise((r) => server.listen(0, r));
  const base = `ws://127.0.0.1:${server.address().port}`;

  // Phone registers.
  let phone = await open(`${base}/ws/device`);
  send(phone, { type: 'hello', name: 'Test Phone' });
  const reg = await next(phone);
  assert.strictEqual(reg.type, 'registered');
  assert.match(reg.code, /^\d{6}$/);

  // Helper with a wrong code.
  const helper = await open(`${base}/ws/viewer`);
  send(helper, { type: 'join', code: reg.code === '000000' ? '111111' : '000000' });
  assert.strictEqual((await next(helper)).error, 'bad-code');

  // Helper with the right code.
  send(helper, { type: 'join', code: reg.code });
  const joined = await next(helper);
  assert.strictEqual(joined.type, 'joined');
  assert.strictEqual(joined.name, 'Test Phone');
  assert.strictEqual((await next(phone)).type, 'viewer-joined');

  // Frame phone → helper, command helper → phone.
  phone.send(Buffer.from([0xff, 0xd8, 0xff, 0xd9]));
  assert.ok((await next(helper)).binary);
  send(helper, { type: 'tap', x: 0.5, y: 0.5 });
  assert.deepStrictEqual(await next(phone), { type: 'tap', x: 0.5, y: 0.5 });

  // Phone drops and resumes the same session.
  phone.terminate();
  assert.strictEqual((await next(helper)).type, 'device-offline');
  phone = await open(`${base}/ws/device`);
  send(phone, { type: 'hello', name: 'Test Phone', code: reg.code, secret: 'wrong' });
  const other = await next(phone);
  assert.notStrictEqual(other.code, reg.code, 'wrong secret must not hijack a session');
  phone.close();
  phone = await open(`${base}/ws/device`);
  send(phone, { type: 'hello', name: 'Test Phone', code: reg.code, secret: reg.secret });
  const resumed = await next(phone);
  assert.strictEqual(resumed.code, reg.code);
  assert.strictEqual(resumed.resumed, true);
  assert.strictEqual((await next(helper)).type, 'device-online');
  assert.strictEqual((await next(phone)).type, 'viewer-joined');

  // Helper ends session → phone told to stop; phone says bye → helper notified.
  send(helper, { type: 'end-session' });
  assert.strictEqual((await next(phone)).type, 'stop');
  send(phone, { type: 'bye' });
  const ended = await next(helper);
  assert.strictEqual(ended.type, 'session-ended');
  assert.strictEqual(ended.reason, 'stopped');

  console.log('smoke test passed');
  process.exit(0);
})().catch((e) => {
  console.error(e);
  process.exit(1);
});

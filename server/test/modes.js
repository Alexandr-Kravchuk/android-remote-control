'use strict';
// Checks the two run modes as real processes.
const assert = require('assert');
const { spawn } = require('child_process');
const http = require('http');
const path = require('path');
const { WebSocket } = require('ws');

const SERVER = path.join(__dirname, '..', 'server.js');

function run(env) {
  const p = spawn(process.execPath, [SERVER], { env: { ...process.env, ...env }, stdio: ['ignore', 'pipe', 'pipe'] });
  p.out = '';
  p.stdout.on('data', (d) => (p.out += d));
  p.stderr.on('data', (d) => (p.out += d));
  return p;
}
const wait = (ms) => new Promise((r) => setTimeout(r, ms));
const get = (port, p, host) => new Promise((res, rej) =>
  http.get({ host: '127.0.0.1', port, path: p, headers: host ? { host } : {} }, (r) => {
    let b = ''; r.on('data', (d) => (b += d)); r.on('end', () => res({ status: r.statusCode, body: b }));
  }).on('error', rej));
const wsOpens = (url, origin) => new Promise((res) => {
  const ws = new WebSocket(url, origin ? { origin } : {});
  ws.on('open', () => { ws.close(); res(true); });
  ws.on('error', () => res(false));
});

(async () => {
  // VPS mode without a token must refuse to start.
  const bad = run({ PORT: '18090', CONSOLE_PORT: '', ACCESS_TOKEN: '', ALLOW_INSECURE: '' });
  const code = await new Promise((r) => bad.on('exit', r));
  assert.strictEqual(code, 1, 'must refuse to start without ACCESS_TOKEN');
  assert.match(bad.out, /ACCESS_TOKEN/);

  // A short token is refused (too easy to guess).
  const weak = run({ PORT: '18093', CONSOLE_PORT: '', ACCESS_TOKEN: 'short', ALLOW_INSECURE: '' });
  assert.strictEqual(await new Promise((r) => weak.on('exit', r)), 1, 'must refuse a short ACCESS_TOKEN');
  assert.match(weak.out, /at least 16/);

  // Laptop mode: console only on localhost:CONSOLE_PORT; public port = phones only.
  const lap = run({ PORT: '18091', CONSOLE_PORT: '18092', ACCESS_TOKEN: '' });
  await wait(500);
  try {
    assert.strictEqual(await wsOpens('ws://127.0.0.1:18091/ws/device'), true, 'phones connect on public port');
    assert.strictEqual(await wsOpens('ws://127.0.0.1:18091/ws/viewer'), false, 'no viewer on public port');
    const pub = await get(18091, '/index.html');
    assert.ok(!pub.body.includes('<html'), 'console must not be served publicly');
    assert.strictEqual((await get(18091, '/%E0%A4%A')).status, 200, 'bad URL must not crash the server');
    assert.strictEqual(await wsOpens('ws://127.0.0.1:18092/ws/viewer'), true, 'viewer on console port');
    const local = await get(18092, '/');
    assert.ok(local.body.includes('<html'), 'console served locally');
    assert.strictEqual((await get(18092, '/%E0%A4%A')).status, 400);
    // Another website open in the helper's browser must not reach the console.
    assert.strictEqual(await wsOpens('ws://127.0.0.1:18092/ws/viewer', 'https://evil.example'), false, 'foreign Origin rejected');
    assert.strictEqual(await wsOpens('ws://localhost:18092/ws/viewer', 'http://localhost:18092'), true, 'own Origin accepted');
    assert.strictEqual((await get(18092, '/', 'evil.example:18092')).status, 403, 'DNS rebinding blocked');
    assert.match(lap.out, /localhost:18092/);
  } finally {
    lap.kill();
  }
  console.log('modes test passed');
})().catch((e) => { console.error(e); process.exit(1); });

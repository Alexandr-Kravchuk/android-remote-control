'use strict';
/**
 * Remote Help relay server.
 *
 *  - Serves the web console (./public) for the helper.
 *  - /ws/device  : the Android phone connects here, gets a 6-digit session code.
 *  - /ws/viewer  : the helper's browser connects here and joins by that code.
 *
 * The server never stores or inspects screen frames — it only relays
 * messages between exactly one phone and one helper.
 *
 * Env:
 *   PORT          listen port (default 8080)
 *   ACCESS_TOKEN  optional; if set, the helper must enter it to join a session
 *   TRUST_PROXY   "1" to take client IP from X-Forwarded-For (behind nginx/Render/Fly)
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
const ACCESS_TOKEN = process.env.ACCESS_TOKEN || '';
const TRUST_PROXY = process.env.TRUST_PROXY === '1';
const PUBLIC_DIR = path.join(__dirname, 'public');

const DEVICE_GRACE_MS = 2 * 60 * 1000; // keep session while phone reconnects
const JOIN_FAIL_LIMIT = 20;            // failed joins per IP ...
const JOIN_FAIL_WINDOW_MS = 10 * 60 * 1000; // ... per 10 minutes
const MAX_VIEWER_BUFFER = 4 * 1024 * 1024;

/** code -> { code, secret, name, device, viewer, graceTimer } */
const sessions = new Map();
/** ip -> { count, resetAt } */
const joinFailures = new Map();

const log = (...a) => console.log(new Date().toISOString(), ...a);

// ---------------------------------------------------------------- HTTP

const MIME = {
  '.html': 'text/html; charset=utf-8',
  '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8',
  '.json': 'application/json; charset=utf-8',
  '.webmanifest': 'application/manifest+json',
  '.svg': 'image/svg+xml',
  '.png': 'image/png',
  '.ico': 'image/x-icon',
};

const server = http.createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/healthz') {
    res.writeHead(200, { 'content-type': 'application/json' });
    res.end(JSON.stringify({ ok: true, sessions: sessions.size }));
    return;
  }
  let rel = decodeURIComponent(url.pathname);
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR)) {
    res.writeHead(403).end();
    return;
  }
  fs.readFile(file, (err, data) => {
    if (err) {
      res.writeHead(404, { 'content-type': 'text/plain' }).end('Not found');
      return;
    }
    res.writeHead(200, {
      'content-type': MIME[path.extname(file)] || 'application/octet-stream',
      'cache-control': 'no-cache',
      'x-content-type-options': 'nosniff',
    });
    res.end(data);
  });
});

// ---------------------------------------------------------------- helpers

function clientIp(req) {
  if (TRUST_PROXY && req.headers['x-forwarded-for']) {
    return String(req.headers['x-forwarded-for']).split(',')[0].trim();
  }
  return req.socket.remoteAddress || 'unknown';
}

function send(ws, obj) {
  if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
}

function newCode() {
  for (let i = 0; i < 1000; i++) {
    const code = String(crypto.randomInt(0, 1_000_000)).padStart(6, '0');
    if (!sessions.has(code)) return code;
  }
  throw new Error('no free codes');
}

function safeEqual(a, b) {
  const ba = Buffer.from(String(a));
  const bb = Buffer.from(String(b));
  return ba.length === bb.length && crypto.timingSafeEqual(ba, bb);
}

function isRateLimited(ip) {
  const e = joinFailures.get(ip);
  if (!e) return false;
  if (Date.now() > e.resetAt) {
    joinFailures.delete(ip);
    return false;
  }
  return e.count >= JOIN_FAIL_LIMIT;
}

function recordJoinFailure(ip) {
  const now = Date.now();
  const e = joinFailures.get(ip);
  if (!e || now > e.resetAt) joinFailures.set(ip, { count: 1, resetAt: now + JOIN_FAIL_WINDOW_MS });
  else e.count++;
}

function endSession(s, reason) {
  if (s.graceTimer) clearTimeout(s.graceTimer);
  sessions.delete(s.code);
  if (s.viewer) {
    send(s.viewer, { type: 'session-ended', reason });
    s.viewer.close(1000, 'session ended');
  }
  if (s.device) s.device.close(1000, 'session ended');
  log(`session ${s.code} ended: ${reason}`);
}

function parse(data, isBinary) {
  if (isBinary) return null;
  try {
    const m = JSON.parse(data.toString());
    return m && typeof m === 'object' && typeof m.type === 'string' ? m : null;
  } catch {
    return null;
  }
}

// ---------------------------------------------------------------- device

function handleDevice(ws) {
  let session = null;

  ws.on('message', (data, isBinary) => {
    // After registration everything from the phone is relayed to the helper.
    if (session) {
      if (!isBinary) {
        const m = parse(data, false);
        if (m && m.type === 'bye') { endSession(session, 'stopped'); return; }
      }
      const v = session.viewer;
      if (!v || v.readyState !== WebSocket.OPEN) return;
      if (isBinary) {
        if (v.bufferedAmount > MAX_VIEWER_BUFFER) return; // drop frame, helper is slow
        v.send(data, { binary: true });
      } else {
        v.send(data.toString());
      }
      return;
    }

    const msg = parse(data, isBinary);
    if (!msg || msg.type !== 'hello') return;
    const name = String(msg.name || 'Android').slice(0, 80);

    // Reconnect to an existing session (phone lost network for a moment).
    if (msg.code && msg.secret) {
      const s = sessions.get(String(msg.code));
      if (s && safeEqual(s.secret, msg.secret)) {
        if (s.device && s.device !== ws) s.device.terminate();
        if (s.graceTimer) clearTimeout(s.graceTimer);
        s.graceTimer = null;
        s.device = ws;
        s.name = name;
        session = s;
        send(ws, { type: 'registered', code: s.code, secret: s.secret, resumed: true });
        if (s.viewer) {
          send(s.viewer, { type: 'device-online', name });
          send(ws, { type: 'viewer-joined' });
        }
        log(`device resumed ${s.code}`);
        return;
      }
    }

    const code = newCode();
    session = {
      code,
      secret: crypto.randomBytes(16).toString('hex'),
      name,
      device: ws,
      viewer: null,
      graceTimer: null,
    };
    sessions.set(code, session);
    send(ws, { type: 'registered', code, secret: session.secret, resumed: false });
    log(`device registered ${code} (${name})`);
  });

  ws.on('close', () => {
    if (!session || session.device !== ws) return;
    const s = session;
    s.device = null;
    if (s.viewer) send(s.viewer, { type: 'device-offline' });
    s.graceTimer = setTimeout(() => endSession(s, 'phone disconnected'), DEVICE_GRACE_MS);
  });
}

// ---------------------------------------------------------------- viewer

function handleViewer(ws, ip) {
  let session = null;

  ws.on('message', (data, isBinary) => {
    if (session) {
      if (isBinary) return;
      const msg = parse(data, false);
      if (!msg) return;
      if (msg.type === 'end-session') {
        if (session.device) send(session.device, { type: 'stop' });
        return;
      }
      if (session.device && session.device.readyState === WebSocket.OPEN) {
        session.device.send(data.toString());
      }
      return;
    }

    const msg = parse(data, isBinary);
    if (!msg || msg.type !== 'join') return;

    if (isRateLimited(ip)) {
      send(ws, { type: 'error', error: 'rate-limited', message: 'Забагато невдалих спроб. Спробуйте пізніше.' });
      return;
    }
    if (ACCESS_TOKEN && !safeEqual(msg.token || '', ACCESS_TOKEN)) {
      recordJoinFailure(ip);
      send(ws, { type: 'error', error: 'bad-token', message: 'Невірний ключ доступу.' });
      return;
    }
    const s = sessions.get(String(msg.code || '').replace(/\D/g, ''));
    if (!s) {
      recordJoinFailure(ip);
      send(ws, { type: 'error', error: 'bad-code', message: 'Сесію з таким кодом не знайдено.' });
      return;
    }

    // Only one helper at a time: a new join replaces the previous tab.
    if (s.viewer && s.viewer !== ws) {
      send(s.viewer, { type: 'session-ended', reason: 'Підключились з іншого вікна' });
      s.viewer.close(1000, 'replaced');
    }
    s.viewer = ws;
    session = s;
    send(ws, { type: 'joined', code: s.code, name: s.name, online: !!s.device, accessRequired: !!ACCESS_TOKEN });
    if (s.device) send(s.device, { type: 'viewer-joined' });
    log(`viewer joined ${s.code} from ${ip}`);
  });

  ws.on('close', () => {
    if (!session || session.viewer !== ws) return;
    session.viewer = null;
    if (session.device) send(session.device, { type: 'viewer-left' });
    log(`viewer left ${session.code}`);
  });
}

// ---------------------------------------------------------------- websockets

const wss = new WebSocketServer({ noServer: true, maxPayload: 8 * 1024 * 1024 });

server.on('upgrade', (req, socket, head) => {
  const { pathname } = new URL(req.url, 'http://localhost');
  if (pathname !== '/ws/device' && pathname !== '/ws/viewer') {
    socket.destroy();
    return;
  }
  wss.handleUpgrade(req, socket, head, (ws) => {
    ws.isAlive = true;
    ws.on('pong', () => (ws.isAlive = true));
    ws.on('error', () => {});
    if (pathname === '/ws/device') handleDevice(ws);
    else handleViewer(ws, clientIp(req));
  });
});

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, 20000);

wss.on('close', () => clearInterval(heartbeat));

if (require.main === module) {
  server.listen(PORT, () => {
    log(`Remote Help server on :${PORT}${ACCESS_TOKEN ? ' (access token required)' : ''}`);
  });
}

module.exports = { server, sessions };

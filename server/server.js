'use strict';
/**
 * Remote Help relay server.
 *
 *  - /ws/device  : the Android phone connects here and gets a 6-digit session code.
 *  - /ws/viewer  : the helper's browser connects here and joins by that code.
 *  - static      : the helper's web console (./public).
 *
 * Two ways to run it:
 *
 *  1. "Laptop" mode (CONSOLE_PORT set): the web console and /ws/viewer listen ONLY on
 *     127.0.0.1:CONSOLE_PORT, so they are unreachable from the internet. The public
 *     PORT (exposed through Tailscale Funnel / Cloudflare Tunnel) serves only /ws/device.
 *
 *  2. "VPS" mode (no CONSOLE_PORT): everything on PORT; ACCESS_TOKEN is then required.
 *
 * The server never stores or inspects screen frames or audio — it only relays
 * messages between exactly one phone and one helper. Voice goes peer-to-peer
 * (WebRTC); this server only forwards the signalling messages.
 *
 * Env:
 *   PORT            public port (default 8080)
 *   CONSOLE_PORT    enables laptop mode, e.g. 8081 → console at http://localhost:8081
 *   ACCESS_TOKEN    helper must enter it to join (required in VPS mode)
 *   ALLOW_INSECURE  "1" to run VPS mode without ACCESS_TOKEN (local testing only)
 *   TRUST_PROXY     "1" to take client IP from X-Forwarded-For (behind nginx/Caddy)
 *   STUN_URLS       comma-separated, default Google public STUN
 *   TURN_URLS, TURN_USERNAME, TURN_CREDENTIAL   optional TURN relay for voice
 */
const http = require('http');
const fs = require('fs');
const path = require('path');
const crypto = require('crypto');
const { WebSocketServer, WebSocket } = require('ws');

const PORT = parseInt(process.env.PORT || '8080', 10);
const CONSOLE_PORT = process.env.CONSOLE_PORT ? parseInt(process.env.CONSOLE_PORT, 10) : 0;
const ACCESS_TOKEN = process.env.ACCESS_TOKEN || '';
const ALLOW_INSECURE = process.env.ALLOW_INSECURE === '1';
const TRUST_PROXY = process.env.TRUST_PROXY === '1';
const PUBLIC_DIR = path.join(__dirname, 'public');

const DEVICE_GRACE_MS = 3 * 60 * 1000;      // keep the session while the phone reconnects
const JOIN_FAIL_LIMIT_IP = 20;               // failed joins per IP (or IPv6 /64) ...
const JOIN_FAIL_LIMIT_GLOBAL = 200;          // ... and in total ...
const JOIN_FAIL_WINDOW_MS = 10 * 60 * 1000;  // ... per 10 minutes
const TOKEN_FAIL_SLOWDOWN = 100;             // wrong tokens in total → answer slowly (tarpit)
const TOKEN_SLOW_MS = 2000;
const MIN_TOKEN_LENGTH = 16;
const MAX_VIEWER_BUFFER = 4 * 1024 * 1024;
const HEARTBEAT_MS = 30 * 1000;              // generous: mobile links stall for a while

function iceServers() {
  const list = [];
  const stun = (process.env.STUN_URLS || 'stun:stun.l.google.com:19302,stun:stun1.l.google.com:19302')
    .split(',').map((s) => s.trim()).filter(Boolean);
  if (stun.length) list.push({ urls: stun });
  if (process.env.TURN_URLS) {
    list.push({
      urls: process.env.TURN_URLS.split(',').map((s) => s.trim()).filter(Boolean),
      username: process.env.TURN_USERNAME || '',
      credential: process.env.TURN_CREDENTIAL || '',
    });
  }
  return list;
}

/** code -> { code, secret, name, device, viewer, graceTimer } */
const sessions = new Map();
/** key -> { count, resetAt } ; key is an IP or '*' for the global counter */
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
};

function serveStatic(req, res) {
  let rel;
  try {
    rel = decodeURIComponent(new URL(req.url, 'http://localhost').pathname);
  } catch {
    res.writeHead(400).end();
    return;
  }
  if (rel === '/' || rel === '') rel = '/index.html';
  const file = path.normalize(path.join(PUBLIC_DIR, rel));
  if (!file.startsWith(PUBLIC_DIR + path.sep)) {
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
      'referrer-policy': 'no-referrer',
    });
    res.end(data);
  });
}

const LOCAL_HOSTS = (port) => new Set([`localhost:${port}`, `127.0.0.1:${port}`, `[::1]:${port}`]);

/** Laptop console must be addressed as localhost — blocks DNS-rebinding attacks. */
function isLocalHost(req) {
  return LOCAL_HOSTS(CONSOLE_PORT).has(String(req.headers.host || '').toLowerCase());
}

/**
 * Browsers always send Origin on WebSocket upgrades. A page from another site
 * (open in the helper's browser) must not be able to talk to /ws/viewer.
 * Non-browser clients send no Origin; CSWSH doesn't apply to them.
 */
function originAllowed(req, localOnly) {
  const origin = req.headers.origin;
  if (!origin) return true;
  let host;
  try { host = new URL(origin).host.toLowerCase(); } catch { return false; }
  if (localOnly) return LOCAL_HOSTS(CONSOLE_PORT).has(host) && isLocalHost(req);
  return host === String(req.headers.host || '').toLowerCase();
}

function makeHttpHandler({ console: withConsole, localOnly = false }) {
  return (req, res) => {
    if (localOnly && !isLocalHost(req)) {
      res.writeHead(403, { 'content-type': 'text/plain' }).end('Open the console via http://localhost');
      return;
    }
    const pathname = (req.url || '/').split('?')[0];
    if (pathname === '/healthz') {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ ok: true }));
      return;
    }
    if (withConsole) return serveStatic(req, res);
    res.writeHead(200, { 'content-type': 'text/plain; charset=utf-8' });
    res.end('Remote Help relay is running.\n');
  };
}

// ---------------------------------------------------------------- helpers

function clientIp(req) {
  let ip = req.socket.remoteAddress || 'unknown';
  if (TRUST_PROXY && req.headers['x-forwarded-for']) {
    // The right-most entry is the one appended by our own (trusted) proxy;
    // anything to the left is supplied by the client and can be forged.
    const parts = String(req.headers['x-forwarded-for']).split(',').map((s) => s.trim()).filter(Boolean);
    if (parts.length) ip = parts[parts.length - 1];
  }
  ip = ip.replace(/^::ffff:(?=\d+\.)/i, '');
  // Group IPv6 addresses by /64 — one client usually owns the whole prefix.
  if (ip.includes(':')) {
    const full = expandIPv6(ip);
    ip = full ? full.slice(0, 4).join(':') + '::/64' : 'invalid-ipv6';
  }
  return ip;
}

/** '2001:db8::1' → ['2001','0db8','0000',…,'0001'] (8 groups), or null if malformed. */
function expandIPv6(addr) {
  let a = addr.toLowerCase().replace(/^\[|\]$/g, '').split('%')[0];
  // Embedded IPv4 tail (::ffff:1.2.3.4) → two hex groups.
  const v4 = a.match(/(\d+)\.(\d+)\.(\d+)\.(\d+)$/);
  if (v4) {
    const b = v4.slice(1).map(Number);
    if (b.some((x) => x > 255)) return null;
    a = a.slice(0, -v4[0].length) + ((b[0] << 8) | b[1]).toString(16) + ':' + ((b[2] << 8) | b[3]).toString(16);
  }
  const halves = a.split('::');
  if (halves.length > 2) return null;
  const head = halves[0] ? halves[0].split(':') : [];
  const tail = halves.length === 2 && halves[1] ? halves[1].split(':') : [];
  const missing = 8 - head.length - tail.length;
  if (halves.length === 1 ? missing !== 0 : missing < 1) return null;
  const groups = [...head, ...Array(halves.length === 2 ? missing : 0).fill('0'), ...tail];
  if (!groups.every((g) => /^[0-9a-f]{1,4}$/.test(g))) return null;
  return groups.map((g) => g.padStart(4, '0'));
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
  const ha = crypto.createHash('sha256').update(String(a)).digest();
  const hb = crypto.createHash('sha256').update(String(b)).digest();
  return crypto.timingSafeEqual(ha, hb);
}

function failures(key) {
  const e = joinFailures.get(key);
  if (!e) return 0;
  if (Date.now() > e.resetAt) {
    joinFailures.delete(key);
    return 0;
  }
  return e.count;
}

function recordJoinFailure(ip, { global = false } = {}) {
  const now = Date.now();
  for (const key of global ? [ip, '*'] : [ip]) {
    const e = joinFailures.get(key);
    if (!e || now > e.resetAt) joinFailures.set(key, { count: 1, resetAt: now + JOIN_FAIL_WINDOW_MS });
    else e.count++;
  }
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
        if (m && m.type === 'bye') {
          endSession(session, 'stopped');
          return;
        }
      }
      const v = session.viewer;
      if (!v || v.readyState !== WebSocket.OPEN) return;
      if (isBinary) {
        if (v.bufferedAmount > MAX_VIEWER_BUFFER) return; // helper is slow; phone recovers via acks
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
        send(ws, { type: 'registered', code: s.code, secret: s.secret, resumed: true, iceServers: iceServers() });
        if (s.viewer) {
          send(s.viewer, { type: 'device-online', name });
          send(ws, { type: 'viewer-joined', resumed: true });
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
    send(ws, { type: 'registered', code, secret: session.secret, resumed: false, iceServers: iceServers() });
    log(`device registered ${code} (${name})`);
  });

  ws.on('close', () => {
    if (!session || session.device !== ws) return;
    const s = session;
    s.device = null;
    if (!sessions.has(s.code)) return;
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

    const limited = () =>
      send(ws, { type: 'error', error: 'rate-limited', message: 'Забагато невдалих спроб. Спробуйте пізніше.' });
    if (failures(ip) >= JOIN_FAIL_LIMIT_IP) return limited();
    if (ACCESS_TOKEN && !safeEqual(msg.token || '', ACCESS_TOKEN)) {
      // Wrong token: blocked per IP. Globally we only *slow down* (never block),
      // so a distributed attack crawls while the real helper can still get in.
      // The token itself is ≥16 chars, so guessing it is infeasible anyway.
      recordJoinFailure(ip);
      recordJoinFailure('*token');
      const reply = () => send(ws, { type: 'error', error: 'bad-token', message: 'Невірний ключ доступу.' });
      if (failures('*token') >= TOKEN_FAIL_SLOWDOWN) setTimeout(reply, TOKEN_SLOW_MS);
      else reply();
      return;
    }
    // Past this point the client is trusted (valid token, or local console in laptop mode).
    // The global limit still caps code guessing if a token ever leaks.
    if (failures('*') >= JOIN_FAIL_LIMIT_GLOBAL) return limited();
    const s = sessions.get(String(msg.code || '').replace(/\D/g, ''));
    if (!s) {
      recordJoinFailure(ip, { global: true });
      send(ws, { type: 'error', error: 'bad-code', message: 'Сесію з таким кодом не знайдено.' });
      return;
    }

    // One helper at a time. A new join (e.g. the helper reloaded the page) replaces
    // the old tab, and the phone owner is told about it.
    const replaced = !!(s.viewer && s.viewer !== ws);
    if (replaced) {
      send(s.viewer, { type: 'session-ended', reason: 'Підключились з іншого вікна' });
      s.viewer.close(1000, 'replaced');
    }
    s.viewer = ws;
    session = s;
    send(ws, { type: 'joined', code: s.code, name: s.name, online: !!s.device, iceServers: iceServers() });
    if (s.device) send(s.device, { type: 'viewer-joined', replaced });
    log(`viewer joined ${s.code} from ${ip}${replaced ? ' (replaced previous)' : ''}`);
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

function attachUpgrade(server, { device, viewer, localOnly = false }) {
  server.on('upgrade', (req, socket, head) => {
    const pathname = (req.url || '/').split('?')[0];
    const isDevice = pathname === '/ws/device' && device;
    const isViewer = pathname === '/ws/viewer' && viewer && originAllowed(req, localOnly);
    if (!isDevice && !isViewer) {
      socket.destroy();
      return;
    }
    wss.handleUpgrade(req, socket, head, (ws) => {
      ws.isAlive = true;
      ws.on('pong', () => (ws.isAlive = true));
      ws.on('error', () => {});
      if (isDevice) handleDevice(ws);
      else handleViewer(ws, clientIp(req));
    });
  });
}

const heartbeat = setInterval(() => {
  for (const ws of wss.clients) {
    if (!ws.isAlive) {
      ws.terminate();
      continue;
    }
    ws.isAlive = false;
    ws.ping();
  }
}, HEARTBEAT_MS);
heartbeat.unref();

// Public server. In laptop mode it only accepts phones.
const server = http.createServer(makeHttpHandler({ console: !CONSOLE_PORT }));
attachUpgrade(server, { device: true, viewer: !CONSOLE_PORT });

// Local-only console server (laptop mode).
const consoleServer = CONSOLE_PORT ? http.createServer(makeHttpHandler({ console: true, localOnly: true })) : null;
if (consoleServer) attachUpgrade(consoleServer, { device: true, viewer: true, localOnly: true });

if (require.main === module) {
  if (ACCESS_TOKEN && ACCESS_TOKEN.length < MIN_TOKEN_LENGTH) {
    console.error(`Refusing to start: ACCESS_TOKEN must be at least ${MIN_TOKEN_LENGTH} characters ` +
      '(e.g. openssl rand -hex 16).');
    process.exit(1);
  }
  if (!CONSOLE_PORT && !ACCESS_TOKEN && !ALLOW_INSECURE) {
    console.error(
      'Refusing to start: the helper console is public but ACCESS_TOKEN is not set.\n' +
      '  • On a laptop use CONSOLE_PORT=8081 (console only on localhost), or\n' +
      '  • set ACCESS_TOKEN=<long random string>, or\n' +
      '  • ALLOW_INSECURE=1 for a quick local test.'
    );
    process.exit(1);
  }
  server.listen(PORT, () => log(`phones connect to :${PORT}${CONSOLE_PORT ? ' (phones only)' : ''}`));
  if (consoleServer) {
    consoleServer.listen(CONSOLE_PORT, '127.0.0.1', () =>
      log(`helper console: http://localhost:${CONSOLE_PORT}  (local only)`));
  } else {
    log(`helper console: http://localhost:${PORT}${ACCESS_TOKEN ? ' (access token required)' : ''}`);
  }
}

module.exports = { server, consoleServer, sessions, expandIPv6 };

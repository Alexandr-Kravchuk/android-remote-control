'use strict';
/* Remote Help — helper web console. */
(() => {
  const $ = (id) => document.getElementById(id);
  const joinView = $('join');
  const sessionView = $('session');
  const canvas = $('screen');
  const ctx = canvas.getContext('2d');
  const screenBox = $('screenBox');
  const overlay = $('overlay');

  const store = {
    get(k) { try { return localStorage.getItem(k) || ''; } catch { return ''; } },
    set(k, v) { try { localStorage.setItem(k, v); } catch { /* private mode */ } },
  };

  let ws = null;
  let code = '';
  let ended = false;          // session finished — don't reconnect
  let reconnectTimer = null;
  let frames = 0, bytes = 0, lastFrameAt = 0;
  let decoding = false, pendingFrame = null;

  $('token').value = store.get('rh.token');
  if (store.get('rh.token')) $('tokenBox').open = true;

  // ------------------------------------------------------------ join

  $('code').addEventListener('input', (e) => {
    const d = e.target.value.replace(/\D/g, '').slice(0, 6);
    e.target.value = d.length > 3 ? d.slice(0, 3) + ' ' + d.slice(3) : d;
  });

  $('joinForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const c = $('code').value.replace(/\D/g, '');
    if (c.length !== 6) {
      $('joinError').textContent = 'Код має містити 6 цифр.';
      return;
    }
    store.set('rh.token', $('token').value);
    code = c;
    ended = false;
    connect(true);
  });

  function wsUrl() {
    const proto = location.protocol === 'https:' ? 'wss:' : 'ws:';
    return `${proto}//${location.host}/ws/viewer`;
  }

  function connect(fromJoinForm) {
    clearTimeout(reconnectTimer);
    if (ws) { ws.onclose = null; ws.close(); }
    $('joinBtn').disabled = true;
    $('joinError').textContent = '';
    ws = new WebSocket(wsUrl());
    ws.binaryType = 'arraybuffer';

    ws.onopen = () => {
      ws.send(JSON.stringify({ type: 'join', code, token: $('token').value }));
    };

    ws.onmessage = (ev) => {
      if (typeof ev.data !== 'string') { onFrame(ev.data); return; }
      let msg;
      try { msg = JSON.parse(ev.data); } catch { return; }
      onMessage(msg, fromJoinForm);
    };

    ws.onclose = () => {
      $('joinBtn').disabled = false;
      if (!sessionView.hidden && !ended) {
        setStatus('bad', 'Немає з’єднання з сервером. Перепідключення…');
        reconnectTimer = setTimeout(() => connect(false), 2000);
      }
    };
  }

  function onMessage(msg, fromJoinForm) {
    switch (msg.type) {
      case 'error':
        $('joinBtn').disabled = false;
        if (msg.error === 'bad-token') $('tokenBox').open = true;
        if (sessionView.hidden || fromJoinForm) {
          $('joinError').textContent = msg.message || 'Помилка';
        } else {
          leave(msg.message);
        }
        ws.onclose = null;
        ws.close();
        break;
      case 'joined':
        joinView.hidden = true;
        sessionView.hidden = false;
        $('deviceName').textContent = msg.name || 'Телефон';
        document.title = `${msg.name || 'Телефон'} — Віддалена допомога`;
        setStatus(msg.online ? 'ok' : 'bad', msg.online ? 'Очікування зображення…' : 'Телефон не в мережі. Очікуємо…');
        sendQuality();
        focusScreen();
        break;
      case 'device-online':
        setStatus('ok', 'Телефон знову на зв’язку');
        sendQuality();
        break;
      case 'device-offline':
        setStatus('bad', 'Телефон втратив зв’язок. Очікуємо…');
        break;
      case 'session-ended':
        leave({
          'phone disconnected': 'Телефон довго не виходив на зв’язок — сесію завершено.',
          stopped: 'Людина завершила сесію на телефоні.',
        }[msg.reason] || msg.reason || 'Сесію завершено.');
        break;
      case 'screen':
        // Phone reports its real resolution / orientation change.
        break;
      case 'info': {
        const parts = [];
        if (msg.model && msg.model !== $('deviceName').textContent) parts.push(msg.model);
        if (msg.android) parts.push(`Android ${msg.android}`);
        if (msg.battery != null) parts.push(`🔋 ${msg.battery}%${msg.charging ? ' ⚡' : ''}`);
        $('deviceInfo').textContent = parts.join(' · ');
        break;
      }
      case 'notice':
        toast(msg.text);
        break;
      case 'a11y':
        if (!msg.enabled) {
          toast('На телефоні вимкнено «Службу керування» — можна лише дивитися. Увімкніть її в Налаштуваннях → Спеціальні можливості.', 8000);
        }
        break;
    }
  }

  function leave(reason) {
    ended = true;
    clearTimeout(reconnectTimer);
    if (ws) { ws.onclose = null; ws.close(); ws = null; }
    sessionView.hidden = true;
    joinView.hidden = false;
    $('joinBtn').disabled = false;
    $('joinError').textContent = reason || '';
    $('code').value = '';
    document.title = 'Віддалена допомога';
    ctx.clearRect(0, 0, canvas.width, canvas.height);
  }

  function setStatus(kind, text) {
    $('statusDot').className = 'dot ' + (kind || '');
    overlay.hidden = !text;
    overlay.textContent = text || '';
  }

  // ------------------------------------------------------------ frames

  function onFrame(buf) {
    frames++;
    bytes += buf.byteLength;
    // Tell the phone we received it, so it can send the next one (flow control).
    send({ type: 'ack' });
    if (decoding) { pendingFrame = buf; return; }
    draw(buf);
  }

  async function draw(buf) {
    decoding = true;
    try {
      const bmp = await createImageBitmap(new Blob([buf], { type: 'image/jpeg' }));
      if (canvas.width !== bmp.width || canvas.height !== bmp.height) {
        canvas.width = bmp.width;
        canvas.height = bmp.height;
      }
      ctx.drawImage(bmp, 0, 0);
      bmp.close();
      lastFrameAt = Date.now();
      if (!overlay.hidden) setStatus('ok', '');
    } catch { /* corrupt frame */ }
    decoding = false;
    if (pendingFrame) {
      const next = pendingFrame;
      pendingFrame = null;
      draw(next);
    }
  }

  setInterval(() => {
    if (sessionView.hidden) return;
    const kb = (bytes / 1024).toFixed(0);
    const idle = lastFrameAt ? Math.round((Date.now() - lastFrameAt) / 1000) : null;
    $('stats').textContent = `${frames} кадр/с · ${kb} КБ/с` + (idle && idle > 5 ? ` · екран не змінювався ${idle} с` : '');
    frames = 0;
    bytes = 0;
  }, 1000);

  // ------------------------------------------------------------ input

  function send(obj) {
    if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
  }

  function norm(ev) {
    const r = canvas.getBoundingClientRect();
    return [
      Math.min(1, Math.max(0, (ev.clientX - r.left) / r.width)),
      Math.min(1, Math.max(0, (ev.clientY - r.top) / r.height)),
    ];
  }

  function ripple(ev) {
    const r = screenBox.getBoundingClientRect();
    const el = document.createElement('div');
    el.className = 'ripple';
    el.style.left = ev.clientX - r.left + 'px';
    el.style.top = ev.clientY - r.top + 'px';
    screenBox.appendChild(el);
    setTimeout(() => el.remove(), 600);
  }

  const LONG_PRESS_MS = 600;
  const MOVE_THRESHOLD = 0.012; // fraction of screen
  let gesture = null;

  canvas.addEventListener('pointerdown', (ev) => {
    if (ev.button !== 0) return;
    ev.preventDefault();
    focusScreen();
    canvas.setPointerCapture(ev.pointerId);
    const p = norm(ev);
    gesture = { start: performance.now(), points: [p], moved: false, done: false };
    gesture.timer = setTimeout(() => {
      if (gesture && !gesture.moved) {
        gesture.done = true;
        send({ type: 'longpress', x: p[0], y: p[1] });
        ripple(ev);
      }
    }, LONG_PRESS_MS);
  });

  canvas.addEventListener('pointermove', (ev) => {
    if (!gesture || gesture.done) return;
    const p = norm(ev);
    const s = gesture.points[0];
    if (!gesture.moved && Math.hypot(p[0] - s[0], p[1] - s[1]) > MOVE_THRESHOLD) {
      gesture.moved = true;
      clearTimeout(gesture.timer);
    }
    if (gesture.moved) gesture.points.push(p);
  });

  function finishGesture(ev) {
    if (!gesture) return;
    clearTimeout(gesture.timer);
    const g = gesture;
    gesture = null;
    if (g.done) return;
    const dur = Math.round(performance.now() - g.start);
    if (!g.moved) {
      const p = g.points[0];
      send({ type: 'tap', x: p[0], y: p[1] });
      ripple(ev);
      return;
    }
    g.points.push(norm(ev));
    // Downsample the path to at most ~24 points.
    const step = Math.max(1, Math.ceil(g.points.length / 24));
    const pts = g.points.filter((_, i) => i % step === 0 || i === g.points.length - 1);
    send({ type: 'swipe', points: pts, duration: Math.min(3000, Math.max(80, dur)) });
  }
  canvas.addEventListener('pointerup', finishGesture);
  canvas.addEventListener('pointercancel', () => { if (gesture) clearTimeout(gesture.timer); gesture = null; });
  canvas.addEventListener('contextmenu', (e) => e.preventDefault());

  // Mouse wheel / trackpad → scroll gesture.
  let wheel = null;
  canvas.addEventListener('wheel', (ev) => {
    ev.preventDefault();
    const p = norm(ev);
    if (!wheel) wheel = { x: p[0], y: p[1], dy: 0, dx: 0 };
    wheel.dy += ev.deltaY;
    wheel.dx += ev.deltaX;
    clearTimeout(wheel.timer);
    wheel.timer = setTimeout(() => {
      const r = canvas.getBoundingClientRect();
      const dy = Math.max(-0.6, Math.min(0.6, wheel.dy / r.height));
      const dx = Math.max(-0.6, Math.min(0.6, wheel.dx / r.width));
      if (Math.abs(dy) > 0.01 || Math.abs(dx) > 0.01) send({ type: 'scroll', x: wheel.x, y: wheel.y, dx, dy });
      wheel = null;
    }, 120);
  }, { passive: false });

  // Typing directly while the phone screen is focused. A hidden textarea receives
  // the keystrokes so any keyboard layout, IME and dead keys work.
  const kbd = $('kbd');
  const focusScreen = () => kbd.focus({ preventScroll: true });
  let textBuf = '';
  let textTimer = null;
  function flushText() {
    clearTimeout(textTimer);
    if (textBuf) send({ type: 'text', text: textBuf });
    textBuf = '';
  }
  function takeInput() {
    if (!kbd.value) return;
    textBuf += kbd.value;
    kbd.value = '';
    clearTimeout(textTimer);
    textTimer = setTimeout(flushText, 250);
  }
  kbd.addEventListener('keydown', (ev) => {
    if (ev.isComposing || ev.metaKey || ev.ctrlKey || ev.altKey) return;
    const keys = { Escape: ['global', 'back'], Backspace: ['key', 'backspace'], Enter: ['key', 'enter'] };
    const k = keys[ev.key];
    if (!k) return;
    ev.preventDefault();
    takeInput();
    flushText();
    send(k[0] === 'global' ? { type: 'global', action: k[1] } : { type: 'key', key: k[1] });
  });
  kbd.addEventListener('input', (ev) => { if (!ev.isComposing) takeInput(); });
  kbd.addEventListener('compositionend', takeInput);
  kbd.addEventListener('paste', (ev) => {
    const t = ev.clipboardData && ev.clipboardData.getData('text');
    ev.preventDefault();
    if (t) { takeInput(); flushText(); send({ type: 'text', text: t }); toast('Текст вставлено'); }
  });

  // ------------------------------------------------------------ panel buttons

  document.querySelectorAll('[data-action]').forEach((b) =>
    b.addEventListener('click', () => {
      send({ type: 'global', action: b.dataset.action });
      focusScreen();
    }));
  document.querySelectorAll('[data-key]').forEach((b) =>
    b.addEventListener('click', () => send({ type: 'key', key: b.dataset.key })));

  $('textForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const t = $('textInput').value;
    if (!t) return;
    send({ type: 'text', text: t });
    $('textInput').value = '';
  });

  $('msgForm').addEventListener('submit', (e) => {
    e.preventDefault();
    const t = $('msgInput').value.trim();
    if (!t) return;
    send({ type: 'message', text: t });
    $('msgInput').value = '';
    toast('Повідомлення показано на телефоні');
  });

  function sendQuality() { send({ type: 'quality', level: $('quality').value }); }
  $('quality').value = store.get('rh.quality') || 'medium';
  $('quality').addEventListener('change', () => { store.set('rh.quality', $('quality').value); sendQuality(); });

  $('saveShotBtn').addEventListener('click', () => {
    if (!canvas.width) return;
    const a = document.createElement('a');
    a.download = `phone-${new Date().toISOString().replace(/[:.]/g, '-')}.png`;
    a.href = canvas.toDataURL('image/png');
    a.click();
  });

  $('disconnectBtn').addEventListener('click', () => leave('Ви відключились. Телефон чекає — можна підключитися знову з тим самим кодом.'));
  $('endBtn').addEventListener('click', () => {
    if (!confirm('Зупинити трансляцію екрана на телефоні?')) return;
    send({ type: 'end-session' });
    setTimeout(() => leave('Сесію завершено.'), 300);
  });

  let toastTimer = null;
  function toast(text, ms = 3000) {
    const t = $('toast');
    t.textContent = text;
    t.hidden = false;
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => (t.hidden = true), ms);
  }

  // Allow ?code=123456 links.
  const qc = new URLSearchParams(location.search).get('code');
  if (qc) { $('code').value = qc; $('code').dispatchEvent(new Event('input')); }
})();

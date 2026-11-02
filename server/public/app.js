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
  let frames = 0, bytes = 0, lastFrameAt = 0, lastStatsAt = 0;
  let decoding = false, pendingFrame = null;
  let iceServers = [];

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
        if (Array.isArray(msg.iceServers)) iceServers = msg.iceServers;
        resetDecoder(false);
        sendQuality();
        sendCaps();
        focusScreen();
        // We were in a call and our own connection dropped — the phone ended it; call again.
        if (callWanted) { closePc(); startCall(); }
        break;
      case 'device-online':
        setStatus('ok', 'Телефон знову на зв’язку');
        resetDecoder(false);
        sendQuality();
        sendCaps();
        break;
      case 'device-offline':
        setStatus('bad', 'Слабкий зв’язок: телефон тимчасово недоступний. Чекаємо…');
        setLink('poor', 'Телефон не на зв’язку');
        break;
      case 'stats':
        onStats(msg);
        break;
      case 'stream':
        streamMode = msg.mode;
        break;
      case 'rtc-answer':
        onAnswer(msg.sdp);
        break;
      case 'rtc-ice':
        onRemoteIce(msg);
        break;
      case 'call-state':
        onPhoneCallState(msg);
        break;
      case 'call-unavailable':
        callWanted = false;
        closePc();
        setCall('idle', msg.reason === 'no-permission'
          ? 'На телефоні не дозволено мікрофон. Подзвоніть звичайним дзвінком або дозвольте мікрофон для «Допомога».'
          : 'Розмова недоступна на цьому телефоні. Подзвоніть звичайним дзвінком.');
        break;
      case 'voice-available':
        if (!msg.available) $('callStatus').textContent = 'На телефоні не дозволено мікрофон — розмова в додатку не працюватиме.';
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
    endCall(false);
    resetDecoder(false);
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
  //
  // Binary message from the phone: [kind:1][id:4][payload]
  //   kind 1 = H.264 key frame (Annex B, with SPS/PPS), 2 = H.264 delta, 3 = JPEG.
  // Every frame is acked with its id: the phone measures round-trip time from that and
  // adapts bitrate, and stops sending when too much is in flight (bad connection).

  let streamMode = '';
  let decoder = null, decoderCodec = "", needKey = true, lastKeyReq = -Infinity, chunkTs = 0;
  const h264Supported = (async () => {
    try {
      return 'VideoDecoder' in window &&
        (await VideoDecoder.isConfigSupported({ codec: 'avc1.42E01F' })).supported === true;
    } catch { return false; }
  })();

  async function sendCaps() {
    send({ type: 'caps', h264: await h264Supported, jpeg: true });
  }

  function onFrame(buf) {
    if (buf.byteLength < 6) return;
    frames++;
    bytes += buf.byteLength;
    const dv = new DataView(buf);
    const kind = dv.getUint8(0);
    const id = dv.getUint32(1);
    send({ type: 'ack', id });
    const data = new Uint8Array(buf, 5);
    if (kind === 3) drawJpeg(data);
    else if (kind === 1 || kind === 2) decodeH264(kind === 1, data);
  }

  function frameShown(w, h) {
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; }
    lastFrameAt = Date.now();
    if (!overlay.hidden && !$('statusDot').classList.contains('bad')) setStatus('ok', '');
  }

  // --- JPEG
  function drawJpeg(data) {
    if (decoding) { pendingFrame = data; return; }
    drawJpegNow(data);
  }
  async function drawJpegNow(data) {
    decoding = true;
    try {
      const bmp = await createImageBitmap(new Blob([data], { type: 'image/jpeg' }));
      frameShown(bmp.width, bmp.height);
      ctx.drawImage(bmp, 0, 0);
      bmp.close();
    } catch { /* corrupt frame */ }
    decoding = false;
    if (pendingFrame) { const n = pendingFrame; pendingFrame = null; drawJpegNow(n); }
  }

  // --- H.264 via WebCodecs
  function codecFromAnnexB(d) {
    for (let i = 0; i + 4 < d.length; i++) {
      if (d[i] === 0 && d[i + 1] === 0 && d[i + 2] === 1) {
        if ((d[i + 3] & 0x1f) === 7 && i + 6 < d.length) {
          const hex = (v) => v.toString(16).padStart(2, '0');
          return 'avc1.' + hex(d[i + 4]) + hex(d[i + 5]) + hex(d[i + 6]);
        }
        i += 2;
      }
    }
    return null;
  }

  function setupDecoder(codec) {
    try { if (decoder && decoder.state !== 'closed') decoder.close(); } catch { /* ignore */ }
    decoder = new VideoDecoder({
      output: (f) => {
        frameShown(f.displayWidth, f.displayHeight);
        ctx.drawImage(f, 0, 0, f.displayWidth, f.displayHeight);
        f.close();
      },
      error: (e) => { console.warn('decoder', e); resetDecoder(true); },
    });
    decoder.configure({ codec, optimizeForLatency: true });
    decoderCodec = codec;
  }

  function resetDecoder(askKey) {
    try { if (decoder && decoder.state !== 'closed') decoder.close(); } catch { /* ignore */ }
    decoder = null;
    decoderCodec = '';
    needKey = true;
    if (askKey) requestKey();
  }

  function requestKey() {
    const now = performance.now();
    if (now - lastKeyReq < 1000) return;
    lastKeyReq = now;
    send({ type: 'keyframe' });
  }

  function decodeH264(isKey, data) {
    if (isKey) {
      const c = codecFromAnnexB(data) || 'avc1.42E01F';
      if (!decoder || decoder.state === 'closed' || c !== decoderCodec) {
        try { setupDecoder(c); } catch (e) { console.warn(e); resetDecoder(true); return; }
      }
      needKey = false;
    }
    if (!decoder || needKey) { requestKey(); return; }
    if (decoder.decodeQueueSize > 8) { needKey = true; requestKey(); return; } // can't keep up
    try {
      decoder.decode(new EncodedVideoChunk({ type: isKey ? 'key' : 'delta', timestamp: (chunkTs++) * 33333, data }));
    } catch (e) {
      console.warn(e);
      resetDecoder(true);
    }
  }

  // --- connection quality
  function setLink(kind, text) {
    $('link').className = 'link ' + kind;
    $('linkText').textContent = text;
  }

  let lastStats = null;
  function onStats(m) {
    lastStatsAt = Date.now();
    lastStats = m;
    const rtt = m.rtt || 0;
    if (m.drops > 2 || rtt > 1200) setLink('poor', `Поганий зв’язок · ${rtt} мс`);
    else if (m.drops > 0 || rtt > 450) setLink('fair', `Середній зв’язок · ${rtt} мс`);
    else setLink('good', `Добрий зв’язок${rtt ? ' · ' + rtt + ' мс' : ''}`);
  }

  setInterval(() => {
    if (sessionView.hidden) return;
    const kbps = Math.round((bytes * 8) / 1000);
    const idle = lastFrameAt ? Math.round((Date.now() - lastFrameAt) / 1000) : null;
    const fmt = streamMode === 'h264' ? 'H.264' : streamMode === 'jpeg' ? 'JPEG' : '';
    $('stats').textContent = [`${frames} кадр/с`, `${kbps} кбіт/с`, fmt,
      idle && idle > 5 ? `екран не змінювався ${idle} с` : ''].filter(Boolean).join(' · ');
    if (lastStatsAt && Date.now() - lastStatsAt > 5000 && !$('statusDot').classList.contains('bad')) {
      setLink('poor', 'Немає відповіді від телефону');
    }
    frames = 0;
    bytes = 0;
  }, 1000);

  // ------------------------------------------------------------ voice (WebRTC)
  //
  // The browser offers, the phone answers automatically. Audio goes peer-to-peer
  // (or through TURN if configured); the server only relays the signalling.

  let pc = null, localStream = null, callWanted = false, iceRestarts = 0, pendingIce = [];
  let disconnectTimer = null;

  function setCall(state, text) {
    const btn = $('callBtn');
    const inCall = state !== 'idle';
    btn.textContent = inCall ? '📵 Завершити розмову' : '📞 Почати розмову';
    btn.classList.toggle('active', inCall);
    btn.classList.toggle('primary', !inCall);
    $('micBtn').hidden = !inCall;
    if (text != null) $('callStatus').textContent = text;
  }

  // Opus with in-band FEC + DTX at a modest bitrate survives packet loss much better.
  function tuneOpus(sdp) {
    const m = sdp.match(/a=rtpmap:(\d+) opus\/48000/i);
    if (!m) return sdp;
    const pt = m[1];
    const re = new RegExp(`a=fmtp:${pt} ([^\\r\\n]*)`);
    const extra = { useinbandfec: '1', usedtx: '1', maxaveragebitrate: '32000' };
    if (!re.test(sdp)) return sdp;
    return sdp.replace(re, (_, params) => {
      const map = {};
      params.split(';').map((x) => x.trim()).filter(Boolean).forEach((kv) => {
        const [k, v = ''] = kv.split('='); map[k] = v;
      });
      Object.assign(map, extra);
      return `a=fmtp:${pt} ` + Object.entries(map).map(([k, v]) => `${k}=${v}`).join(';');
    });
  }

  async function startCall() {
    callWanted = true;
    iceRestarts = 0;
    setCall('connecting', 'З’єднуємо розмову…');
    try {
      if (!localStream) {
        localStream = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        });
      }
    } catch {
      callWanted = false;
      setCall('idle', 'Браузер не дав доступу до мікрофона. Дозвольте мікрофон для цієї сторінки і спробуйте ще раз.');
      return;
    }
    pc = new RTCPeerConnection({ iceServers });
    pendingIce = [];
    localStream.getTracks().forEach((t) => pc.addTrack(t, localStream));
    pc.ontrack = (e) => {
      const audio = $('remoteAudio');
      audio.srcObject = e.streams[0] || new MediaStream([e.track]);
      audio.play().catch(() => {});
    };
    pc.onicecandidate = (e) => {
      if (e.candidate) {
        send({ type: 'rtc-ice', candidate: e.candidate.candidate, sdpMid: e.candidate.sdpMid, sdpMLineIndex: e.candidate.sdpMLineIndex });
      }
    };
    const thisPc = pc;
    pc.oniceconnectionstatechange = () => {
      if (pc !== thisPc) return;
      const st = pc.iceConnectionState;
      clearTimeout(disconnectTimer);
      if (st === 'connected' || st === 'completed') {
        iceRestarts = 0;
        setCall('active', 'Розмова йде. Голос передається напряму.');
      } else if (st === 'disconnected') {
        setCall('connecting', 'Зв’язок погіршився, відновлюємо розмову…');
        disconnectTimer = setTimeout(() => { if (pc === thisPc && pc.iceConnectionState === 'disconnected') restartIce(); }, 4000);
      } else if (st === 'failed') {
        restartIce();
      }
    };
    await makeOffer(false);
  }

  async function makeOffer(iceRestart) {
    if (!pc) return;
    const offer = await pc.createOffer({ iceRestart });
    const tuned = { type: 'offer', sdp: tuneOpus(offer.sdp) };
    await pc.setLocalDescription(tuned);
    send({ type: 'rtc-offer', sdp: tuned.sdp });
  }

  function restartIce() {
    if (!pc || !callWanted) return;
    if (iceRestarts >= 3) {
      setCall('connecting', 'Не вдається з’єднати голос напряму. Подзвоніть звичайним дзвінком' +
        ' (або налаштуйте TURN на сервері — див. README). Керування працює як і раніше.');
      return;
    }
    iceRestarts++;
    setCall('connecting', `Відновлюємо розмову (спроба ${iceRestarts})…`);
    makeOffer(true).catch((e) => console.warn(e));
  }

  async function onAnswer(sdp) {
    if (!pc) return;
    try {
      await pc.setRemoteDescription({ type: 'answer', sdp });
      for (const c of pendingIce) await pc.addIceCandidate(c).catch(() => {});
      pendingIce = [];
    } catch (e) { console.warn('answer', e); }
  }

  function onRemoteIce(m) {
    if (!pc) return;
    const c = { candidate: m.candidate, sdpMid: m.sdpMid, sdpMLineIndex: m.sdpMLineIndex };
    if (!pc.remoteDescription) pendingIce.push(c);
    else pc.addIceCandidate(c).catch(() => {});
  }

  function onPhoneCallState(m) {
    if (!callWanted) return;
    const note = m.muted ? ' На телефоні вимкнено мікрофон.' : '';
    if (m.state === 'connected') setCall('active', 'Розмова йде. Голос передається напряму.' + note);
    else if (m.state === 'ended') { closePc(); callWanted = false; setCall('idle', 'Розмову завершено на телефоні.'); }
    else if (note) $('callStatus').textContent = $('callStatus').textContent.replace(/ На телефоні.*$/, '') + note;
  }

  function closePc() {
    clearTimeout(disconnectTimer);
    if (pc) { try { pc.close(); } catch { /* ignore */ } }
    pc = null;
    pendingIce = [];
  }

  function endCall(notifyPhone = true) {
    if (notifyPhone && callWanted) send({ type: 'call-end' });
    callWanted = false;
    closePc();
    if (localStream) { localStream.getTracks().forEach((t) => t.stop()); localStream = null; }
    $('micBtn').classList.remove('off');
    $('micBtn').textContent = '🎤 Вимкнути мій мікрофон';
    setCall('idle', 'Голос іде напряму між вами і телефоном, поряд із керуванням.');
  }

  $('callBtn').addEventListener('click', () => {
    if (callWanted) endCall(true);
    else startCall().catch((e) => { console.warn(e); endCall(true); });
  });
  $('micBtn').addEventListener('click', () => {
    if (!localStream) return;
    const track = localStream.getAudioTracks()[0];
    if (!track) return;
    track.enabled = !track.enabled;
    $('micBtn').classList.toggle('off', !track.enabled);
    $('micBtn').textContent = track.enabled ? '🎤 Вимкнути мій мікрофон' : '🔇 Мій мікрофон вимкнено';
  });

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
  $('quality').value = ['auto', 'low', 'high'].includes(store.get('rh.quality')) ? store.get('rh.quality') : 'auto';
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

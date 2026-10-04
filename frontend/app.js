(() => {
  'use strict';
  const MODES = ['blitz', 'bullet', 'rapid'];
  const LABEL = { blitz: 'Blitz', bullet: 'Bullet', rapid: 'Rapid' };
  const COLORS = ['#FF2E93', '#FF8A00', '#FFD60A', '#B6FF3B', '#9B30FF'];
  const $ = (s) => document.querySelector(s);

  const state = { mode: 'blitz', data: {}, leader: {}, me: null };
  const rowsEl = $('#rows');
  const pods = [...document.querySelectorAll('.pod')];
  const rowEls = new Map();

  /* ---------- rendering ---------- */
  function fmt(n) { return Number(n).toLocaleString(); }

  function updatePodium(entries) {
    pods.forEach((card) => {
      const e = entries[Number(card.dataset.i)];
      const nameEl = card.querySelector('.pname');
      const rateEl = card.querySelector('.prate');
      if (!e) { nameEl.textContent = '-'; rateEl.textContent = ''; delete card.dataset.name; return; }
      const prevName = card.dataset.name;
      const prevRating = card.dataset.rating === undefined ? null : Number(card.dataset.rating);
      nameEl.textContent = e.username;
      rateEl.textContent = fmt(e.rating);
      card.dataset.name = e.username;
      card.dataset.rating = e.rating;
      card.classList.toggle('me', e.username === state.me);
      if (prevName && prevName !== e.username) animate(card, 'swap');
      else if (prevRating !== null && prevRating !== e.rating) animate(card, e.rating > prevRating ? 'pulse-up' : 'pulse-down');
    });
  }

  function animate(el, cls) {
    el.classList.remove('pulse-up', 'pulse-down', 'swap');
    void el.offsetWidth;
    el.classList.add(cls);
  }

  function makeRow(name, rating) {
    const el = document.createElement('div');
    el.className = 'row enter';
    el.innerHTML = '<span class="rk"></span><span class="nm"></span><span class="rt"><i class="delta"></i><b></b></span>';
    el.querySelector('.nm').textContent = name;
    el.dataset.rating = rating;
    el.addEventListener('animationend', (ev) => { if (ev.animationName === 'enter') el.classList.remove('enter'); });
    return el;
  }

  function flash(el, diff) {
    el.classList.remove('up', 'down');
    void el.offsetWidth;
    el.classList.add(diff > 0 ? 'up' : 'down');
    const d = el.querySelector('.delta');
    d.textContent = (diff > 0 ? '+' : '') + diff;
    d.className = 'delta show ' + (diff > 0 ? 'up' : 'down');
    clearTimeout(el._t);
    el._t = setTimeout(() => d.classList.remove('show'), 2500);
  }

  function updateRows(entries) {
    const list = entries.slice(3);
    $('#empty')?.remove();

    // FLIP: remember where every row is before reordering
    const first = new Map();
    rowEls.forEach((el, name) => first.set(name, el.getBoundingClientRect().top));

    const seen = new Set();
    for (const e of list) {
      let el = rowEls.get(e.username);
      if (!el) {
        el = makeRow(e.username, e.rating);
        rowEls.set(e.username, el);
      } else {
        const old = Number(el.dataset.rating);
        if (old !== e.rating) { flash(el, e.rating - old); el.dataset.rating = e.rating; }
      }
      el.querySelector('.rk').textContent = e.rank;
      el.querySelector('.rt b').textContent = fmt(e.rating);
      el.classList.toggle('me', e.username === state.me);
      rowsEl.appendChild(el); // appending an existing node moves it into rank order
      seen.add(e.username);
    }
    [...rowEls.keys()].forEach((name) => {
      if (!seen.has(name)) { rowEls.get(name).remove(); rowEls.delete(name); }
    });

    const moved = [];
    seen.forEach((name) => {
      if (!first.has(name)) return;
      const el = rowEls.get(name);
      const dy = first.get(name) - el.getBoundingClientRect().top;
      if (Math.abs(dy) > 1) {
        el.style.transition = 'none';
        el.style.transform = `translateY(${dy}px)`;
        moved.push(el);
      }
    });
    if (moved.length) {
      void rowsEl.offsetWidth;
      requestAnimationFrame(() => moved.forEach((el) => {
        el.style.transition = 'transform .65s cubic-bezier(.2,.9,.25,1)';
        el.style.transform = '';
      }));
    }
  }

  function render() {
    const entries = state.data[state.mode] || [];
    updatePodium(entries);
    updateRows(entries);
  }

  /* ---------- data in ---------- */
  function onSnapshot(m) {
    if (!MODES.includes(m.mode)) return;
    const entries = m.entries || [];
    state.data[m.mode] = entries;
    if (m.instance) $('#instance').textContent = m.instance;

    const before = state.leader[m.mode];
    const now = entries[0] && entries[0].username;
    state.leader[m.mode] = now;

    if (m.mode !== state.mode) return;
    render();
    if (before && now && before !== now) {
      confetti();
      toast(`${now} takes the top spot in ${LABEL[m.mode]}`);
    }
  }

  async function loadInitial() {
    for (const mode of MODES) {
      try {
        const r = await fetch(`/api/leaderboard/${mode}?limit=50`);
        if (r.ok && !state.data[mode]) onSnapshot(await r.json());
      } catch (_) { /* websocket will fill in */ }
    }
  }

  /* ---------- tabs ---------- */
  document.querySelectorAll('.tab').forEach((btn) => btn.addEventListener('click', () => {
    if (btn.dataset.mode === state.mode) return;
    state.mode = btn.dataset.mode;
    document.querySelectorAll('.tab').forEach((b) => b.classList.toggle('active', b === btn));
    $('#board-title').textContent = `${LABEL[state.mode]} ranking`;
    rowsEl.innerHTML = '';
    rowEls.clear();
    pods.forEach((p) => { delete p.dataset.name; delete p.dataset.rating; });
    render();
    if (state.me) findMe(state.me, true);
  }));

  /* ---------- find my rank ---------- */
  async function findMe(name, quiet) {
    const box = $('#find'), title = $('#find-title'), list = $('#find-list');
    try {
      const r = await fetch(`/api/leaderboard/${state.mode}/player/${encodeURIComponent(name)}?radius=3`);
      if (r.status === 404) {
        if (!quiet) state.me = null;
        box.hidden = false;
        title.className = 'find-title miss';
        title.textContent = `"${name}" is not on the ${LABEL[state.mode]} board`;
        list.innerHTML = '';
        return;
      }
      const d = await r.json();
      state.me = d.username;
      box.hidden = false;
      title.className = 'find-title';
      title.textContent = `${d.username} is ranked ${fmt(d.rank)} of ${fmt(d.total)} in ${LABEL[state.mode]}`;
      list.innerHTML = '';
      d.entries.forEach((e) => {
        const line = document.createElement('div');
        line.className = 'line' + (e.username === d.username ? ' self' : '');
        const a = document.createElement('span'); a.textContent = e.rank;
        const b = document.createElement('span'); b.textContent = e.username;
        const c = document.createElement('span'); c.textContent = fmt(e.rating);
        line.append(a, b, c);
        list.appendChild(line);
      });
      render();
    } catch (_) {
      title.className = 'find-title miss';
      title.textContent = 'Could not reach the server. Try again in a moment.';
      box.hidden = false;
    }
  }
  $('#find-form').addEventListener('submit', (ev) => {
    ev.preventDefault();
    const v = $('#find-input').value.trim();
    if (v) findMe(v, false);
  });
  setInterval(() => { if (state.me) findMe(state.me, true); }, 4000);

  /* ---------- toast + confetti ---------- */
  let toastTimer;
  function toast(text) {
    const t = $('#toast');
    t.textContent = text;
    t.classList.add('show');
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => t.classList.remove('show'), 3200);
  }

  const canvas = $('#confetti');
  const ctx = canvas.getContext('2d');
  let parts = [];
  let raf = null;
  function resize() { canvas.width = innerWidth; canvas.height = innerHeight; }
  addEventListener('resize', resize);
  resize();

  function confetti() {
    if (matchMedia('(prefers-reduced-motion: reduce)').matches) return;
    for (let i = 0; i < 170; i++) {
      parts.push({
        x: canvas.width / 2 + (Math.random() - .5) * 240, y: canvas.height * .28,
        vx: (Math.random() - .5) * 15, vy: -Math.random() * 13 - 3,
        w: 6 + Math.random() * 7, h: 4 + Math.random() * 6,
        rot: Math.random() * 6.28, vr: (Math.random() - .5) * .4,
        color: COLORS[(Math.random() * COLORS.length) | 0], life: 0,
      });
    }
    if (!raf) raf = requestAnimationFrame(tick);
  }
  function tick() {
    ctx.clearRect(0, 0, canvas.width, canvas.height);
    parts.forEach((p) => {
      p.life++; p.vy += .32; p.vx *= .992; p.x += p.vx; p.y += p.vy; p.rot += p.vr;
      ctx.save();
      ctx.translate(p.x, p.y); ctx.rotate(p.rot);
      ctx.globalAlpha = Math.max(0, 1 - p.life / 170);
      ctx.fillStyle = p.color;
      ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
      ctx.restore();
    });
    parts = parts.filter((p) => p.life < 170 && p.y < canvas.height + 30);
    raf = parts.length ? requestAnimationFrame(tick) : null;
    if (!raf) ctx.clearRect(0, 0, canvas.width, canvas.height);
  }

  /* ---------- websocket + pulse ---------- */
  const pulse = $('#pulse'), pulseText = $('#pulse-text');
  let ws = null, attempts = 0, lastMsg = 0;

  function setStatus(cls, text) {
    pulse.className = 'pulse ' + cls;
    pulseText.textContent = text;
  }

  function connect() {
    setStatus('connecting', 'Connecting');
    const proto = location.protocol === 'https:' ? 'wss' : 'ws';
    ws = new WebSocket(`${proto}://${location.host}/ws`);
    ws.onopen = () => { attempts = 0; lastMsg = Date.now(); setStatus('live', 'Live'); };
    ws.onmessage = (ev) => {
      lastMsg = Date.now();
      let m; try { m = JSON.parse(ev.data); } catch (_) { return; }
      if (m.type === 'snapshot') onSnapshot(m);
    };
    ws.onclose = () => {
      setStatus('offline', 'Reconnecting');
      setTimeout(connect, Math.min(10000, 500 * 2 ** attempts++));
    };
    ws.onerror = () => ws.close();
  }

  setInterval(() => {
    if (!ws || ws.readyState !== WebSocket.OPEN) return;
    const age = Math.round((Date.now() - lastMsg) / 1000);
    if (age > 35) { ws.close(); return; }
    pulseText.textContent = age < 2 ? 'Live' : `Live, last message ${age}s ago`;
  }, 1000);

  loadInitial();
  connect();
})();

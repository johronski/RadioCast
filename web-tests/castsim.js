/*
 * A pretend Google Cast SDK for the tests (loaded into the page before the app).
 * Speakers are simulated with what they play; Chrome's speaker list is answered by the test.
 *
 *   window.sim.devices          { name: { playing: url|null, state, sessionId, mode } }
 *   window.sim.pick             next answer of Chrome's list: a device name, 'cancel' or 'refuse'
 *   device.mode                 how loadMedia behaves: 'ok' | 'hang' | 'fail' | 'silent-ok'
 *                               ('silent-ok': plays, but the answer to the app never comes, like a slow group)
 *   device.lag                  Chrome's remote player reports the old channel for 2.5 s after a change
 *   device.stale                Chrome's remote player never notices a change (seen with a group, 26 Oct):
 *                               it keeps reporting the channel that played when the app connected
 *   window.sim.desktop          a computer: the full list while casting ('stop' = Sluta casta)
 *   sim.wrongDialogs            how often Chrome showed the old speaker's controls instead of the list
 *   window.sim.takeOver(name)   another app takes the speaker (our session ends)
 *
 * It behaves like Chrome on Android: while connected, the list only shows the current speaker
 * (closing it = 'cancel'); starting a session on a speaker restarts what played there; and
 * starting one on a new speaker stops our radio on the previous one.
 */
(() => {
  const L = {};                      // listeners by event name
  const on = (t, f) => { (L[t] = L[t] || []).push(f); };
  const emit = (t, arg) => (L[t] || []).slice().forEach((f) => { try { f(arg); } catch (e) { console.error(e); } });
  let nextId = 1;
  let current = null;                // { id, device }
  let releasedAt = 0;                // Chrome has really let go of the last speaker at this time
  let lastTap = 0;
  addEventListener('click', () => { lastTap = Date.now(); }, true);
  const later = (f, ms) => setTimeout(f, ms);

  const sim = window.sim = {
    devices: {
      'Kök': { playing: null, state: 'IDLE', sessionId: null, mode: 'ok', title: null },
      'Vardagsrum/Hall': { playing: null, state: 'IDLE', sessionId: null, mode: 'ok', title: null },
    },
    pick: 'Kök',
    loads: [],
    takeOver(name) {
      const d = sim.devices[name];
      d.playing = null; d.state = 'IDLE'; d.sessionId = null;
      if (current && current.device === name) { current = null; emit('session', 'SESSION_ENDED'); }
      emit('remote');
    },
  };

  // ---- media session (one object per load, like Chrome) ----
  function mediaFor(d) {
    const m = {
      media: { contentId: d.playing }, playerState: d.state, listeners: [],
      addUpdateListener(f) { this.listeners.push(f); },
      getStatus(req, ok) { this.playerState = d.state; later(() => ok && ok(), 10); },
    };
    d.media = m;
    return m;
  }
  function update(d) {
    if (d.media) { d.media.playerState = d.state; d.media.media = { contentId: d.playing }; d.media.listeners.forEach((f) => f(true)); }
    emit('remote');
  }

  // ---- session ----
  function makeSession(s) {
    const d = () => sim.devices[s.device];
    return {
      getSessionId: () => s.id,
      getCastDevice: () => ({ friendlyName: s.device }),
      getMediaSession: () => (d().playing ? (d().media || mediaFor(d())) : null),
      sendMessage: () => Promise.resolve(),
      loadMedia(req) {
        const dev = d();
        const url = req.media.contentId;
        sim.loads.push(s.device + ':' + url);
        if (dev.mode === 'fail') return Promise.reject('LOAD_FAILED');
        if (dev.mode === 'hang') return new Promise(() => {});
        return new Promise((resolve) => {
          // 'lag': Chrome's remote player keeps reporting the old channel for a while
          if (dev.lag) { dev.previous = dev.playing; dev.lagUntil = Date.now() + 2500; later(() => emit('remote'), 2600); }
          if (dev.stale && dev.frozen === undefined) dev.frozen = dev.playing;
          dev.playing = url; dev.title = req.media.metadata && req.media.metadata.title; dev.state = 'BUFFERING';
          mediaFor(dev);
          update(dev);
          later(() => { dev.state = 'PLAYING'; update(dev); if (dev.mode === 'ok') resolve(); }, 300);
        });
      },
    };
  }
  function startOn(name, resumed) {
    // Our radio on another speaker stops when a new one starts (as seen on Android).
    for (const [n, x] of Object.entries(sim.devices)) {
      if (n !== name && x.sessionId && x.playing) { x.playing = null; x.state = 'IDLE'; x.sessionId = null; x.media = null; }
    }
    const d = sim.devices[name];
    if (!resumed) { d.playing = null; d.state = 'IDLE'; d.media = null; d.sessionId = 'S' + nextId++; }
    current = { id: d.sessionId, device: name };
    emit('session', 'SESSION_STARTING');
    later(() => { emit('session', resumed ? 'SESSION_RESUMED' : 'SESSION_STARTED'); emit('remote'); }, 50);
  }

  const ctx = {
    setOptions() {},
    addEventListener(t, f) { on(t === 'sessionstatechanged' ? 'sessionRaw' : t, f); },
    getCastState: () => (current || Date.now() < releasedAt ? 'CONNECTED' : 'NOT_CONNECTED'), // Chrome's own view
    getCurrentSession: () => (current ? makeSession(current) : null),
    requestSession() {
      const pick = sim.pick;
      // Chrome only opens its list right after a tap (about 5 s).
      if (Date.now() - lastTap > 5000) return Promise.reject('activation_required');
      // Asked before Chrome has let go of the old speaker (seen 26 Oct): it shows that speaker's
      // controls instead of the list, and you close them.
      if (Date.now() < releasedAt) { sim.wrongDialogs = (sim.wrongDialogs || 0) + 1; return new Promise((res, rej) => later(() => rej('cancel'), 3000)); }
      if (pick === 'refuse') { sim.pick = sim.afterRefuse || 'cancel'; return Promise.reject('session_error'); }
      if (pick === 'cancel') return new Promise((res, rej) => later(() => rej('cancel'), 2000));
      if (current && sim.desktop) {
        // A computer shows the full list while casting: choosing another speaker ends this session.
        return new Promise((res) => later(() => {
          ctx.endCurrentSession(pick === 'stop');
          if (pick !== 'stop') later(() => startOn(pick, false), 100);
          res(null);
        }, 1500));
      }
      // Android: while connected Chrome only shows the current speaker's controls.
      if (current) return new Promise((res, rej) => later(() => rej('cancel'), 2000));
      return new Promise((res) => later(() => { startOn(pick, false); res(null); }, 1500)); // you look at the list for a while
    },
    endCurrentSession(stopCasting) {
      if (!current) return;
      const d = sim.devices[current.device];
      if (stopCasting) { d.playing = null; d.state = 'IDLE'; d.sessionId = null; d.media = null; }
      current = null;
      releasedAt = Date.now() + 700; // the event comes at once, but Chrome needs a moment more
      later(() => { emit('session', 'SESSION_ENDED'); emit('remote'); }, 30);
    },
  };

  // ---- remote player: mirrors the current speaker ----
  const player = {
    get isConnected() { return !!current; },
    get isMediaLoaded() { return !!(current && sim.devices[current.device].playing); },
    get playerState() { return current ? sim.devices[current.device].state : 'IDLE'; },
    get mediaInfo() {
      const d = current && sim.devices[current.device];
      if (d && d.lagUntil > Date.now() && d.previous) return { contentId: d.previous, metadata: { title: 'Gammal' } };
      if (d && d.stale && d.frozen) return { contentId: d.frozen, metadata: { title: 'Gammal' } };
      return d && d.playing ? { contentId: d.playing, metadata: { title: d.title || 'Radio' } } : null;
    },
    volumeLevel: 0.5,
  };

  window.chrome = window.chrome || {};
  window.chrome.cast = {
    media: {
      DEFAULT_MEDIA_RECEIVER_APP_ID: 'CC1AD845', StreamType: { LIVE: 'LIVE' },
      MediaInfo: function (id, type) { this.contentId = id; this.contentType = type; },
      MusicTrackMediaMetadata: function () {}, LoadRequest: function (m) { this.media = m; },
    },
    Image: function (u) { this.url = u; },
    AutoJoinPolicy: { ORIGIN_SCOPED: 'origin_scoped' },
    requestSessionById(id) {
      const name = Object.keys(sim.devices).find((n) => sim.devices[n].sessionId === id);
      if (name && !current) later(() => startOn(name, true), 200);
    },
  };
  window.cast = {
    framework: {
      CastContext: { getInstance: () => ctx },
      RemotePlayer: function () { return player; },
      RemotePlayerController: function () {
        return { addEventListener(t, f) { on('remote', f); }, stop() { if (current) { const d = sim.devices[current.device]; d.playing = null; d.state = 'IDLE'; update(d); } }, setVolumeLevel() {} };
      },
      CastContextEventType: { CAST_STATE_CHANGED: 'caststatechanged', SESSION_STATE_CHANGED: 'sessionstatechanged' },
      CastState: { NO_DEVICES_AVAILABLE: 'NO_DEVICES_AVAILABLE' },
      SessionState: { SESSION_STARTING: 'SESSION_STARTING', SESSION_STARTED: 'SESSION_STARTED', SESSION_RESUMED: 'SESSION_RESUMED', SESSION_ENDED: 'SESSION_ENDED', SESSION_START_FAILED: 'SESSION_START_FAILED' },
      RemotePlayerEventType: { ANY_CHANGE: 'anyChanged' },
    },
  };
  on('session', (state) => emit('sessionRaw', { sessionState: state }));
  // The app asks for the SDK by adding Google's script: answer at once instead.
  const add = Node.prototype.appendChild;
  Node.prototype.appendChild = function (n) {
    if (n && n.tagName === 'SCRIPT' && /cast_sender/.test(n.src || '')) { setTimeout(() => window.__onGCastApiAvailable && window.__onGCastApiAvailable(true), 0); return n; }
    return add.call(this, n);
  };
})();

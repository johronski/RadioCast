/*
 * Chromecast from Chrome (Android and computers): the one place that knows what the speaker does.
 *
 * Two things are kept apart, because they often disagree for a while:
 *   - what you asked for (pending: a channel on its way to the speaker), and
 *   - what the speaker reports (the remote player and the speaker's media session).
 * The screen shows what you asked for until the speaker confirms it, gives up after a while
 * (and says so), and otherwise always shows what the speaker reports.
 *
 * Everything else the app needs (the list, the card, local playback) comes in through `app`:
 *   log, render, snack, nowPlaying(), adopt(url, title), stopLocal(), playingLocally(), shortName
 */

const MEDIA_NS = 'urn:x-cast:com.google.cast.media';
const RESEND_AFTER = 8000;   // a speaker that does not answer a channel change is asked once more…
const GIVE_UP_AFTER = 20000; // …and then the screen goes back to what it really does
const MEMORY_HOURS = 3;

export function createCaster(app) {
  const c = {
    available: false,
    connecting: false,
    connected: false,
    device: '',
    wantPlay: false,       // you started the radio on a speaker (and did not stop it)
    guess: null,           // { device, url, at }: probably still plays there (after a restart)
    joinId: null,          // a shared "control" link: join this cast
  };
  let ctx = null, player = null, controller = null;
  let media = null;        // the speaker's media session we follow
  let pending = null;      // { url, name, timers } – a channel on its way
  let startOnConnect = null; // a station to start as soon as a (new) speaker is connected
  let move = null;         // { oldId, from, tries } – switching speakers (the old one plays on meanwhile)
  let quietUntil = 0;      // a session end we caused ourselves: no "connection lost"
  let known = null;        // the channel the speaker plays, from the newest information we got
  let lastRemote, lastMedia; // what the remote player and the media session said last time"

  const session = () => (ctx ? ctx.getCurrentSession() : null);
  const sessionDevice = (s) => (s && s.getCastDevice() ? s.getCastDevice().friendlyName : 'högtalaren');
  const codeOf = (e) => (typeof e === 'string' ? e : (e && (e.code || e.message)) || String(e));

  // ------------------------------------------------------------ what the speaker reports
  /** The channel the speaker plays now, as far as Chrome knows. */
  function reportedUrl() {
    // The newest information wins. Chrome's remote player can keep reporting a channel long after
    // the speaker changed (a group did that for good, 26 Oct): so a value it has already reported
    // is old news, and only a change it reports counts. The speaker's answer to our own load, and
    // its media session, are news too.
    const r = player && player.mediaInfo ? player.mediaInfo.contentId || null : null;
    if (r !== lastRemote) { lastRemote = r; if (r) known = r; }
    const m = media && media.media ? media.media.contentId || null : null;
    if (m && m !== lastMedia) { lastMedia = m; known = m; }
    return known || r;
  }
  function reportedState() {
    const url = reportedUrl();
    // The speaker's media session answers faster, but only if it is about the same channel
    // (Chrome makes a new one for every channel; an old one stays behind).
    if (media && media.media && url && media.media.contentId === url) return media.playerState || 'IDLE';
    return player && player.isMediaLoaded ? (player.playerState || 'IDLE') : 'IDLE';
  }
  /** PLAYING, BUFFERING, PAUSED or IDLE, as the screen should show it. */
  Object.defineProperty(c, 'state', { get: () => (!c.connected ? 'IDLE' : pending ? 'BUFFERING' : reportedState()) });
  /** Moving the radio between speakers (the chip shows a spinner). */
  Object.defineProperty(c, 'moving', { get: () => !!move });
  Object.defineProperty(c, 'movingFrom', { get: () => (move ? move.from : '') });
  Object.defineProperty(c, 'busy', { get: () => !!move && !move.picking });

  /** Brings the screen in line with what the speaker reports. */
  function reconcile() {
    if (!c.connected) return;
    follow(session()); // a new channel brings a new media session
    const url = reportedUrl();
    if (pending) {
      if (url !== pending.url) return;          // still the old channel: wait
      app.log('speaker plays', pending.name);
      done();                                    // it plays what you asked for (even without an answer)
    }
    if (url) {
      const now = app.nowPlaying();
      if (!now || now.url !== url) app.adopt(url, player && player.mediaInfo && player.mediaInfo.metadata && player.mediaInfo.metadata.title);
    }
    if (['PLAYING', 'BUFFERING'].includes(c.state)) remember();
  }
  function done() {
    if (!pending) return;
    pending.timers.forEach(clearTimeout);
    pending = null;
  }
  function follow(s) {
    const m = s && s.getMediaSession && s.getMediaSession();
    if (!m || m === media) return;
    media = m;
    m.addUpdateListener(() => { if (media === m) { reconcile(); app.render(); } });
  }
  function askStatus(s) {
    try {
      const p = s.sendMessage(MEDIA_NS, { type: 'GET_STATUS', requestId: Math.floor(Math.random() * 1e6) });
      if (p && p.catch) p.catch(() => {});
    } catch (e) { /* the remote player updates by itself */ }
  }

  // ------------------------------------------------------------ memory (for a restart)
  function remember() {
    const now = app.nowPlaying(), s = session();
    if (!c.connected || !now) return;
    c.guess = null;
    app.store.set('castingOn', { device: c.device, url: now.url, at: Date.now(), sessionId: s ? s.getSessionId() : null });
  }
  function forget() { app.store.set('castingOn', null); c.guess = null; }
  function lastPlace() {
    const last = app.store.get('castingOn', null);
    return last && Date.now() - last.at < MEMORY_HOURS * 3600000 ? last : null;
  }

  // ------------------------------------------------------------ sessions
  function onSession(state) {
    const S = window.cast.framework.SessionState;
    const s = session();
    app.log('session', state, s ? sessionDevice(s) : '-', move ? 'move' : '');
    c.connecting = state === S.SESSION_STARTING;
    if (state === S.SESSION_STARTED || state === S.SESSION_RESUMED) {
      const id = s.getSessionId();
      c.connected = true;
      c.device = sessionDevice(s);
      c.guess = null; c.joinId = null; media = null; known = null; lastRemote = undefined; lastMedia = undefined;
      if (move && id === move.oldId) { move = null; startOnConnect = null; } // back where we were
      else if (move) move = null;                                            // moved to a new speaker
      follow(s);
      if (startOnConnect && state === S.SESSION_STARTED) {
        const st = startOnConnect;
        startOnConnect = null;
        app.stopLocal();
        load(st);
      } else {
        startOnConnect = null;
        askStatus(s);                 // join what already plays there
        setTimeout(check, 1200);
      }
      reconcile();
    } else if (state === S.SESSION_ENDED || state === S.SESSION_START_FAILED) {
      const was = c.connected, name = c.device;
      c.connected = false; c.device = ''; media = null;
      done();
      if (state === S.SESSION_START_FAILED) { app.snack('Kunde inte ansluta till högtalaren.'); move = null; startOnConnect = null; }
      else if (move || Date.now() < quietUntil) { /* we let go on purpose */ }
      else if (was) lost(name);
    }
    app.render();
  }
  /** The speaker was taken by another app, stopped elsewhere, or the connection broke. */
  function lost(name) {
    app.log('lost speaker', name);
    forget();
    if (c.wantPlay && app.nowPlaying()) app.snack('Kontakten med ' + (name || 'högtalaren') + ' bröts, kanske tog en annan app över. Tryck på knappen uppe till höger för att casta igen.');
    c.wantPlay = false;
  }
  /** Lets go of the speaker on purpose; stop: also stop the radio there. */
  function leave(stop) {
    if (!session()) return;
    quietUntil = Date.now() + 3000;
    done();
    c.connected = false; c.device = ''; media = null;
    ctx.endCurrentSession(stop);
  }

  // ------------------------------------------------------------ playing
  /** Starts a channel on the speaker; the screen shows it as starting until the speaker confirms. */
  function load(station) {
    const s = session();
    if (!s) {
      c.connected = false;
      app.render();
      app.snack('Inte längre ansluten till högtalaren. Tryck på knappen uppe till höger och välj den igen.');
      return;
    }
    const info = new chrome.cast.media.MediaInfo(station.url, app.contentType(station.url));
    info.streamType = chrome.cast.media.StreamType.LIVE;
    info.metadata = new chrome.cast.media.MusicTrackMediaMetadata();
    info.metadata.title = app.shortName(station.name);
    if (station.logo) info.metadata.images = [new chrome.cast.Image(station.logo)];
    const req = new chrome.cast.media.LoadRequest(info);
    req.autoplay = true;
    done();
    const mine = pending = { url: station.url, name: app.shortName(station.name), timers: [] };
    c.wantPlay = true;
    app.log('load', mine.name, 'on', c.device);
    const send = (attempt) => {
      let p;
      try { p = s.loadMedia(req); } catch (e) { p = Promise.reject(e); }
      p.then(() => {
        if (pending !== mine) return;
        app.log('load ok', mine.name, attempt);
        done();
        known = mine.url; // the speaker said yes: newer than anything Chrome reported before
        follow(s);
        reconcile(); app.render();
      }, (e) => {
        if (pending !== mine) return;
        app.log('load failed', mine.name, codeOf(e));
        done();
        app.fail('Högtalaren kunde inte spela strömmen (' + codeOf(e) + ').');
        reconcile(); app.render();
      });
    };
    send(1);
    mine.timers.push(setTimeout(() => { if (pending === mine) { app.log('no answer, sending again'); send(2); } }, RESEND_AFTER));
    mine.timers.push(setTimeout(() => {
      if (pending !== mine) return;
      app.log('no answer: giving up');
      done();
      reconcile(); app.render();
      app.snack((c.device || 'Högtalaren') + ' svarade inte på kanalbytet. Försök igen.');
    }, GIVE_UP_AFTER));
    app.render();
  }
  function stop() {
    done();
    forget();
    c.wantPlay = false;
    if (controller && player.isMediaLoaded) controller.stop();
  }
  function volume(level) {
    if (!player) return;
    player.volumeLevel = level;
    controller.setVolumeLevel();
  }
  function reportedVolume() { return player ? player.volumeLevel || 0 : 0; }

  // ------------------------------------------------------------ choosing speakers
  /** Chrome's own list of speakers; start: a station to start there once chosen. */
  function openList(start, onError) {
    startOnConnect = start || null;
    ctx.requestSession().catch((e) => {
      startOnConnect = null;
      const code = codeOf(e);
      if (!/cancel/i.test(code) && onError) onError(code);
    });
  }
  /**
   * "Byt högtalare": the radio plays on while Chrome's list is open, and starts on the speaker
   * you choose (Chrome stops it on the old one). Chrome on Android only shows the list when no
   * speaker is connected, so there we let go first, without stopping.
   */
  function switchSpeaker(android) {
    const s = session();
    const now = app.nowPlaying();
    if (!s) return;
    if (!now || !['PLAYING', 'BUFFERING'].includes(c.state)) { leave(true); openList(null); return; }
    const m = move = { oldId: s.getSessionId(), from: c.device, tries: 0, picking: true };
    startOnConnect = now;
    if (android) { quietUntil = Date.now() + 3000; done(); ctx.endCurrentSession(false); c.connected = false; c.device = ''; media = null; }
    app.render();
    const open = () => {
      const t0 = Date.now();
      ctx.requestSession().then(() => {
        // (computer) "Sluta casta" in Chrome's list: no new speaker comes (give a new one time to connect).
        setTimeout(() => {
          if (move === m && !c.connected && !c.connecting) { move = null; startOnConnect = null; forget(); c.wantPlay = false; app.render(); }
        }, 4000);
      }, (e) => {
        if (move !== m) return;
        const quick = Date.now() - t0 < 1500;
        // Chrome sometimes refuses at once while it still lets go of the old speaker: try again.
        if (quick && m.tries < 3) { m.tries++; setTimeout(open, 700); return; }
        if (quick) app.snack('Chrome öppnade inte högtalarlistan (' + codeOf(e) + '). Försök igen.');
        startOnConnect = null;
        m.picking = false;
        if (c.connected) { move = null; app.render(); return; } // (computer) still on the old speaker
        // Closed without choosing: back to the speaker that still plays.
        app.log('back to', m.from);
        join(m.oldId);
        setTimeout(() => {
          if (move !== m) return;
          move = null;
          c.guess = { device: m.from, url: now.url, at: Date.now() };
          app.render();
        }, 8000);
      });
    };
    if (!android) { open(); return; }
    // Chrome on Android shows the old speaker's controls instead of the list if asked before it has
    // really let go of it (seen 26 Oct): wait until Chrome itself says it is no longer connected.
    const t0 = Date.now();
    const whenFree = () => {
      if (move !== m) return;
      const state = ctx.getCastState();
      if ((state === 'CONNECTED' || state === 'CONNECTING') && Date.now() - t0 < 3000) { setTimeout(whenFree, 100); return; }
      setTimeout(open, 300);
    };
    whenFree();
  }
  function join(id) {
    try { if (!session()) chrome.cast.requestSessionById(id); } catch (e) { /* not supported */ }
  }

  /**
   * Asks Chrome and the speaker what is going on now: when the page is shown again (Chrome
   * pauses it in the background) and every 15 s while it is shown.
   */
  function check() {
    if (!ctx) return;
    const s = session();
    if (!s) {
      if (c.connected && !move) { const name = c.device; c.connected = false; c.device = ''; media = null; lost(name); app.render(); }
      return;
    }
    follow(s);
    const m = s.getMediaSession && s.getMediaSession();
    if (!m) { askStatus(s); reconcile(); app.render(); return; }
    m.getStatus(null, () => { reconcile(); app.render(); }, () => {});
  }

  /** Called by Google's script once it has loaded. */
  function init() {
    if (!window.cast || !window.chrome || !chrome.cast) return;
    try {
      const F = window.cast.framework;
      ctx = F.CastContext.getInstance();
      ctx.setOptions({ receiverApplicationId: chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID, autoJoinPolicy: chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED });
      player = new F.RemotePlayer();
      controller = new F.RemotePlayerController(player);
      ctx.addEventListener(F.CastContextEventType.CAST_STATE_CHANGED, (e) => { c.available = e.castState !== F.CastState.NO_DEVICES_AVAILABLE; app.render(); });
      ctx.addEventListener(F.CastContextEventType.SESSION_STATE_CHANGED, (e) => onSession(e.sessionState));
      // The remote player changes often (time, volume): redraw only when what is shown changes.
      let shown = '';
      controller.addEventListener(F.RemotePlayerEventType.ANY_CHANGE, () => {
        reconcile();
        const now = app.nowPlaying();
        const key = [c.connected, c.state, now && now.url, Math.round(reportedVolume() * 100)].join('|');
        if (key !== shown) { shown = key; app.render(); }
      });
      c.available = ctx.getCastState() !== F.CastState.NO_DEVICES_AVAILABLE;
      // After a restart: join the cast this app started (by its id), so the radio keeps playing.
      const last = lastPlace();
      const id = c.joinId || (last && last.sessionId);
      if (id && !session()) join(id);
      app.render();
    } catch (e) { app.log('cast init failed', String(e)); }
  }

  Object.assign(c, {
    ready: () => !!ctx,
    sessionId: () => { const s = session(); return s ? s.getSessionId() : null; },
    init, load, stop, leave, volume, reportedVolume, openList, switchSpeaker, check, remember, forget, lastPlace,
  });
  return c;
}

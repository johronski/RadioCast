/* RadioCast web: web radio in the browser, song info, and casting to Chromecast from Chrome. */
import { $, el, icon, store, enc } from './util.js';
import { countryName, shortName, logoTile, subtitle, guessCountry } from './names.js';
import { stationSearch, freeSearch, mix, resultDetails, toStation } from './catalogue.js';
import { cleanSong, shortTitle, songArtist, songTitle } from './songtext.js';
import { loadPatterns, patternFor, fromPattern, fromIcecast, fetchHistory, coverFor } from './sources.js';
import { addHistory, recent } from './history.js';

const VERSION = 'Webb 4.0';

// ============================================================ stations

/** The phone's own country (from its language), shown first in the list. */
const HOME_CC = /^sv/i.test(navigator.language || '') ? 'SE' : ((navigator.language || '').split('-')[1] || '').toUpperCase();
const SR_LOGO = (id, img) => 'https://static-cdn.sr.se/images/' + id + '/' + img + '.jpg?preset=api-default-square';
const INTERNATIONAL_STATIONS = [
  { name: 'FIP', url: 'https://icecast.radiofrance.fr/fip-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Eklektiskt' },
  { name: 'Radio Nova', url: 'https://novazz.ice.infomaniak.ch/novazz-128.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Eklektiskt' },  { name: "Mouv'", url: 'https://icecast.radiofrance.fr/mouv-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Hiphop' },
];
/** The first list for a new user: Swedish channels on a Swedish phone, otherwise a French mix. */
const DEFAULT_STATIONS = /^sv/i.test(navigator.language || '') ? [
  { name: 'SR P1', url: 'https://live1.sr.se/p1-aac-320', cc: 'SE', lang: 'swedish', br: 320, logo: SR_LOGO(132, '4a5e66b7-c935-415d-a524-c060ea227de2'), genre: 'Nyheter och samhälle' },
  { name: 'SR P2', url: 'https://live1.sr.se/p2-aac-320', cc: 'SE', lang: 'swedish', br: 320, logo: SR_LOGO(163, '684f7bac-3e2e-4a7a-969e-6892b70069d6'), genre: 'Klassiskt' },
  { name: 'SR P3', url: 'https://live1.sr.se/p3-aac-320', cc: 'SE', lang: 'swedish', br: 320, logo: SR_LOGO(164, '1adb95c9-cc4e-452f-b1ed-0602b3e15c46'), genre: 'Pop' },
  { name: 'SR P4 Stockholm', url: 'https://sverigesradio.se/topsy/direkt/srapi/701.mp3', cc: 'SE', lang: 'swedish', br: 96, logo: SR_LOGO(701, '7b2427e5-c4db-4bd9-8db2-5d2a76f1357e'), genre: 'Lokalt' },
  ...INTERNATIONAL_STATIONS,
] : [
  ...INTERNATIONAL_STATIONS,
  { name: 'Nouvo Nova (ny musik)', url: 'https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Ny musik' },
  { name: 'France Inter', url: 'https://icecast.radiofrance.fr/franceinter-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Nyheter och kultur' },
  { name: 'Sveriges Radio P3', url: 'https://live1.sr.se/p3-aac-320', cc: 'SE', lang: 'swedish', br: 320, logo: '', genre: 'Pop' },
];

let stations = store.get('stations', null) || DEFAULT_STATIONS.slice();

// Shared state and what kind of device this is (defined first: used all over below).
const isIOS = /iPhone|iPad|iPod/.test(navigator.userAgent) || (/Macintosh/.test(navigator.userAgent) && navigator.maxTouchPoints > 1);
const isAndroid = /Android/i.test(navigator.userAgent);
const installed = () => matchMedia('(display-mode: standalone)').matches || navigator.standalone === true;
const SHARE_TEXT = 'RadioCast: webbradio i telefonen, och casting till Chromecast från Chrome. Öppna länken, inget behöver installeras.';
const caster = { available: false, connected: false, device: '', state: 'IDLE', ctx: null, player: null, controller: null, loadingUrl: null, replacedUrl: null, media: null, picking: null, handover: null };
const dragging = { volume: false };
const sheetState = { kind: null };
let nowPlaying = (() => {
  const last = store.get('lastPlayed', null);
  return stations.find((s) => s.url === last) || null;
})();
const RETRY_DELAYS = [1000, 2000, 4000, 8000, 15000, 30000];
const retry = { count: 0, timer: 0, stall: 0, startedAt: 0 };

const saveStations = () => store.set('stations', stations);

// ============================================================ song watcher

const songs = { current: null, watchedUrl: null, nextFetch: 0, backfilledAt: 0, misses: 0 };

const recentSongs = (url) => recent(url, songs.current && songs.current.text);

function addAll(list) {
  let added = false;
  for (const s of list || []) added = addHistory(s) || added;
  return added;
}
async function backfill(url, stationName) {
  try { if (addAll(await fetchHistory(url, stationName))) renderSongs(); } catch (e) { /* no history this time */ }
}
function publishSong(s) {
  const old = songs.current;
  if (!s && !old) return;
  if (s && old && s.text === old.text && s.cover === old.cover && s.url === old.url) return;
  songs.current = s;
  if (s) addHistory({ ...s });
  renderSongs();
}
/** Every 3 s: is something playing, and is it time to ask its channel what the song is? */
async function songTick() {
  const s = nowPlaying;
  if (!s || !musicActive()) {
    if (songs.watchedUrl) { songs.watchedUrl = null; publishSong(null); }
    return;
  }
  const url = s.url;
  if (url !== songs.watchedUrl) {
    songs.watchedUrl = url; songs.nextFetch = 0; songs.misses = 0; songs.backfilledAt = 0;
    publishSong(null);
  }
  const now = Date.now();
  const hit = patternFor(url);
  if (hit && now - songs.backfilledAt > 5 * 60000) { songs.backfilledAt = now; backfill(url, s.name); }
  if (now < songs.nextFetch) return;
  // Every 25 s while the page is shown, every 2.5 min in the background (just enough for the history).
  songs.nextFetch = now + (document.visibilityState === 'visible' ? 25000 : 150000);
  let found = null;
  try { found = hit ? await fromPattern(url, s.name) : await fromIcecast(url); } catch (e) { found = undefined; }
  if (songs.watchedUrl !== url) return;
  if (found && found.past && addAll(found.past)) renderSongs();
  if (found && found.text == null && hit) { publishSong(null); return; } // talk
  const text = found ? cleanSong(found.text, s.name) : null;
  if (!text) {
    if (++songs.misses >= 3 || (found && hit)) publishSong(null);
    return;
  }
  songs.misses = 0;
  const cover = await coverFor(text, found.cover);
  if (songs.watchedUrl === url) publishSong({ text, cover, url, at: Date.now() });
}
function pokeSongs() { songs.nextFetch = 0; songTick(); }
setInterval(songTick, 3000);
document.addEventListener('visibilitychange', () => {
  if (document.visibilityState === 'visible') { checkSpeaker(); songs.backfilledAt = 0; pokeSongs(); renderAll(); }
});
// While the page is shown, check the speaker now and then too (Chrome can miss events).
setInterval(() => { if (document.visibilityState === 'visible') checkSpeaker(); }, 15000);

// ============================================================ playback

const audio = $('audio');


let localState = 'stopped';   // stopped | buffering | playing
let playError = null;
let volume = store.get('volume', 100);

/*
 * AirPlay (Safari): the page can open Apple's list of speakers, and is told when the sound
 * goes to one, so the chip can say so.
 */
const airplay = { supported: !!window.WebKitPlaybackTargetAvailabilityEvent && typeof audio.webkitShowPlaybackTargetPicker === 'function', wireless: false, available: null, startOnPick: null };
if (airplay.supported) {
  audio.setAttribute('x-webkit-airplay', 'allow');
  // Safari says whether there is any AirPlay speaker or Apple TV in the network.
  audio.addEventListener('webkitplaybacktargetavailabilitychanged', (e) => { airplay.available = e.availability === 'available'; });
  audio.addEventListener('webkitcurrentplaybacktargetiswirelesschanged', () => {
    airplay.wireless = !!audio.webkitCurrentPlaybackTargetIsWireless;
    // Chosen before anything played: start the channel there now.
    const s = airplay.startOnPick;
    airplay.startOnPick = null;
    if (airplay.wireless && s && localState === 'stopped') playStation(s, stations.some((x) => x.url === s.url));
    renderAll();
  });
}

function contentType(url) {
  const u = url.toLowerCase();
  if (u.includes('.m3u8')) return 'application/x-mpegURL';
  if (u.includes('.aac') || u.includes('-aac')) return 'audio/aac';
  if (u.includes('.ogg')) return 'audio/ogg';
  return 'audio/mpeg';
}

function playStation(s, saved) {
  playError = null;
  nowPlaying = s;
  if (saved) store.set('lastPlayed', s.url);
  if (caster.connected) {
    stopLocal();
    castLoad(s);
  } else {
    clearRetry();
    startLocal(s);
  }
  if (!saved) snack('Provlyssnar ' + shortName(s.name) + '. Den är inte sparad.', 'Lägg till', () => addStation(s, false));
  expandCountry(s.cc);
  pokeSongs();
  renderAll();
}
/** Starts (or restarts) the stream in the phone. */
function startLocal(s, fresh) {
  localState = 'buffering';
  retry.startedAt = Date.now();
  let src = s.url.startsWith('http://') && location.protocol === 'https:' ? s.url.replace('http://', 'https://') : s.url;
  // On a retry, a new address: Chrome otherwise reuses the dead connection for the same link.
  if (fresh) src += (src.includes('?') ? '&' : '?') + 'rc=' + Date.now();
  audio.src = src;
  audio.volume = volume / 100;
  audio.play().catch((e) => {
    if (e && e.name === 'NotAllowedError') { clearRetry(); localState = 'stopped'; renderAll(); return; }
    if (e && e.name === 'AbortError') return; // replaced by a newer start
    lostStream();
  });
}
function stopLocal() {
  clearRetry();
  if (!audio.src) return;
  audio.pause();
  audio.removeAttribute('src');
  audio.load();
  localState = 'stopped';
}
function failLocal() {
  stopLocal();
  playError = 'Strömmen gick inte att spela. Kontrollera länken eller nätet.';
  renderAll();
}

/*
 * Reconnecting: when the network drops (wifi <-> mobile data, a tunnel) the stream errors out or
 * hangs. Try again by itself for about a minute, and at once when the phone is online again.
 * The error is shown only when nothing helped.
 */
function clearRetry() {
  clearTimeout(retry.timer); clearTimeout(retry.stall);
  retry.count = 0; retry.timer = 0; retry.stall = 0;
}
function lostStream() {
  if (localState === 'stopped' || !nowPlaying || retry.timer) return;
  clearTimeout(retry.stall); retry.stall = 0;
  if (retry.count >= RETRY_DELAYS.length) { failLocal(); return; }
  const delay = RETRY_DELAYS[retry.count++];
  localState = 'buffering';
  renderAll();
  retry.timer = setTimeout(() => { retry.timer = 0; if (localState !== 'stopped' && nowPlaying) startLocal(nowPlaying, true); }, delay);
}
/** Buffering for long (the network hangs): treat it as lost. */
function watchStall() {
  clearTimeout(retry.stall);
  retry.stall = setTimeout(() => { retry.stall = 0; if (localState === 'buffering') lostStream(); }, 8000);
}
window.addEventListener('online', () => {
  if (retry.timer) { clearTimeout(retry.timer); retry.timer = 0; if (nowPlaying) startLocal(nowPlaying, true); }
});

audio.addEventListener('playing', () => { localState = 'playing'; clearRetry(); renderAll(); });
// Some browsers (Safari) do not always send "playing" for a live stream: time moving on means it plays.
let lastTime = 0;
audio.addEventListener('timeupdate', () => {
  const t = audio.currentTime;
  if (localState === 'buffering' && !audio.paused && t > lastTime + 0.5) { localState = 'playing'; clearRetry(); renderAll(); }
  lastTime = t;
});
audio.addEventListener('loadstart', () => { lastTime = audio.currentTime; watchStall(); });
audio.addEventListener('waiting', () => { if (audio.src && localState !== 'stopped') { localState = 'buffering'; watchStall(); renderAll(); } });
audio.addEventListener('stalled', () => { if (audio.src && localState !== 'stopped') watchStall(); });
audio.addEventListener('error', () => { if (audio.getAttribute('src')) lostStream(); });
// Paused from outside (headphones, a phone call): stop, unless it is our own restart.
audio.addEventListener('pause', () => {
  // A new src pauses the element by itself for a moment: only a pause from outside stops.
  if (!audio.src || audio.ended || localState === 'stopped' || retry.timer || Date.now() - retry.startedAt < 1500) return;
  localState = 'stopped'; clearRetry(); renderAll();
});
audio.addEventListener('ended', () => { if (localState !== 'stopped') lostStream(); }); // a live stream never ends on purpose

function musicActive() {
  if (caster.handover) return true; // moving between speakers: it plays on
  return caster.connected ? ['PLAYING', 'BUFFERING', 'PAUSED'].includes(caster.state) : localState !== 'stopped';
}
function stopMusic() {
  if (busyMoving()) return;
  if (caster.connected) castStop(); else stopLocal();
  renderAll();
}
function togglePlay() {
  if (musicActive()) { stopMusic(); return; }
  if (!caster.connected && caster.guess && caster.ctx) {
    // It probably still plays there: reconnect (and start it there if it does not).
    caster.wantPlay = true;
    openSpeakerList();
    return;
  }
  if (!nowPlaying) { snack('Tryck på en kanal i listan för att spela den.'); return; }
  playStation(nowPlaying, stations.some((x) => x.url === nowPlaying.url));
}
function setVolume(v) {
  volume = v;
  store.set('volume', v);
  if (caster.connected) castVolume(v / 100); else audio.volume = v / 100;
}

// ============================================================ Chromecast (Chrome on Android and computers)


window.__onGCastApiAvailable = (ok) => {
  if (!ok || !window.cast || !window.chrome || !chrome.cast) return;
  try {
    const ctx = window.cast.framework.CastContext.getInstance();
    ctx.setOptions({
      receiverApplicationId: chrome.cast.media.DEFAULT_MEDIA_RECEIVER_APP_ID,
      autoJoinPolicy: chrome.cast.AutoJoinPolicy.ORIGIN_SCOPED,
    });
    const player = new window.cast.framework.RemotePlayer();
    const controller = new window.cast.framework.RemotePlayerController(player);
    Object.assign(caster, { ctx, player, controller });
    const F = window.cast.framework;
    ctx.addEventListener(F.CastContextEventType.CAST_STATE_CHANGED, (e) => {
      caster.available = e.castState !== F.CastState.NO_DEVICES_AVAILABLE;
      renderOutput();
    });
    ctx.addEventListener(F.CastContextEventType.SESSION_STATE_CHANGED, (e) => onCastSession(e.sessionState));
    controller.addEventListener(F.RemotePlayerEventType.ANY_CHANGE, onRemoteChange);
    caster.available = ctx.getCastState() !== F.CastState.NO_DEVICES_AVAILABLE;
    renderOutput();
    rejoin();
  } catch (e) { /* casting stays unavailable */ }
};
(() => {
  const s = document.createElement('script');
  s.src = 'https://www.gstatic.com/cv/js/sender/v1/cast_sender.js?loadCastFramework=1';
  s.async = true;
  document.head.appendChild(s);
})();

function onCastSession(state) {
  const S = window.cast.framework.SessionState;
  const session = caster.ctx.getCurrentSession();
  caster.connecting = state === S.SESSION_STARTING;
  if (state === S.SESSION_STARTED || state === S.SESSION_RESUMED) {
    caster.connected = true;
    caster.device = session && session.getCastDevice() ? session.getCastDevice().friendlyName : 'högtalaren';
    caster.guess = null;
    caster.joinId = null;
    if (caster.handover && handoverSession(session)) { renderAll(); return; }
    if (state === S.SESSION_STARTED && caster.picking && caster.picking.ended && caster.picking.playing && nowPlaying) {
      castLoad(nowPlaying); // moved from the previous speaker
    } else if (state === S.SESSION_STARTED && localState !== 'stopped' && nowPlaying) {
      stopLocal();
      castLoad(nowPlaying); // what played in the phone moves to the speaker
    } else if (state === S.SESSION_STARTED) {
      // The speaker may already play our radio (started earlier): join it instead of restarting.
      askMediaStatus(session);
      setTimeout(() => {
        checkSpeaker();
        setTimeout(() => {
          if (caster.connected && caster.wantPlay && nowPlaying && !['PLAYING', 'BUFFERING'].includes(caster.state)) castLoad(nowPlaying);
        }, 800);
      }, 1200);
    }
    syncRemote();
    if (state === S.SESSION_RESUMED) {
      // Rejoining a speaker that already plays: ask what it plays, so the card shows it.
      askMediaStatus(session);
      setTimeout(checkSpeaker, 1500);
    }
  } else if (state === S.SESSION_ENDED || state === S.SESSION_START_FAILED) {
    if (state === S.SESSION_START_FAILED) snack('Kunde inte ansluta till högtalaren.');
    else if (caster.handover) { /* letting go on purpose while moving speakers */ }
    else if (caster.picking) caster.picking.ended = true; // switching speaker in Chrome's list
    else if (caster.connected && !caster.switching) lostSpeaker();
    caster.connected = false;
    caster.device = '';
    caster.state = 'IDLE';
    caster.media = null;
  }
  renderAll();
}
/** The speaker was let go of (paused elsewhere, another app took over, or the connection broke). */
function lostSpeaker() {
  const name = caster.device || 'högtalaren';
  if (caster.wantPlay && nowPlaying) {
    snack('Kontakten med ' + name + ' bröts, kanske tog en annan app över. Tryck på knappen uppe till höger för att casta igen.');
  }
}

/**
 * Asks Chrome and the speaker what is going on now. Run when the page is shown again, since
 * Chrome pauses the page in the background and it can miss what happened meanwhile.
 */
function checkSpeaker() {
  if (!caster.ctx) return;
  const session = caster.ctx.getCurrentSession();
  if (!session) {
    if (caster.connected) {
      lostSpeaker();
      caster.connected = false; caster.device = ''; caster.state = 'IDLE';
      renderAll();
    }
    return;
  }
  const media = session.getMediaSession && session.getMediaSession();
  if (!media) {
    // Not joined to what plays yet: use what the remote player knows, and ask the speaker.
    syncRemote();
    renderAll();
    askMediaStatus(session);
    return;
  }
  if (caster.loadingUrl) return; // a channel change is under way
  media.getStatus(null, () => {
    syncRemote();
    if (caster.loadingUrl) { renderAll(); return; }
    caster.state = media.playerState || 'IDLE'; // fresh from the speaker
    watchMedia(session);
    renderAll();
  }, () => { caster.state = 'IDLE'; renderAll(); });
}

/** Asks the speaker's player for its status; Chrome then joins what is playing. */
function askMediaStatus(session) {
  try {
    const p = session.sendMessage('urn:x-cast:com.google.cast.media', { type: 'GET_STATUS', requestId: Math.floor(Math.random() * 1e6) });
    if (p && p.catch) p.catch(() => {});
  } catch (e) { /* older Chrome: the remote player updates by itself */ }
}

function syncRemote() {
  const p = caster.player;
  if (!p) return;
  const id = p.mediaInfo && p.mediaInfo.contentId;
  // Right after a channel change the remote player can still report the old channel: ignore it.
  if (caster.loadingUrl || (caster.replacedUrl && id === caster.replacedUrl)) return volumeFrom(p);
  caster.replacedUrl = null;
  // The speaker's own media session (watched after a load) is fresher than the remote player.
  const m = caster.media;
  caster.state = m && id && m.media && m.media.contentId === id ? (m.playerState || 'IDLE')
    : p.isMediaLoaded ? (p.playerState || 'IDLE') : 'IDLE';
  if (id && (!nowPlaying || nowPlaying.url !== id)) {
    // Started earlier (or from another phone): show it.
    nowPlaying = stations.find((s) => s.url === id) || { name: (p.mediaInfo.metadata && p.mediaInfo.metadata.title) || 'Radio', url: id, cc: '', logo: '' };
    pokeSongs();
  }
  volumeFrom(p);
}
function volumeFrom(p) {
  if (caster.connected && !dragging.volume) $('volume').value = Math.round((p.volumeLevel || 0) * 100);
}
/** Remembers what plays where, so a restarted app can say "probably still playing on Kök". */
function rememberCasting() {
  if (caster.connected && nowPlaying && ['PLAYING', 'BUFFERING'].includes(caster.state)) {
    const session = caster.ctx && caster.ctx.getCurrentSession();
    const sessionId = session && session.getSessionId ? session.getSessionId() : null;
    store.set('castingOn', { device: caster.device, url: nowPlaying.url, at: Date.now(), sessionId });
  }
}
/**
 * After a restart: join the speaker session this app started (by its id), so the radio keeps
 * playing. Choosing the speaker in Chrome's list instead would restart its player (the stream stops).
 */
function rejoin() {
  if (caster.joinId) {
    try { if (!caster.ctx.getCurrentSession()) chrome.cast.requestSessionById(caster.joinId); } catch (e) { /* not supported */ }
    return;
  }
  const last = store.get('castingOn', null);
  if (!last || !last.sessionId || Date.now() - last.at > 12 * 3600000) return;
  try {
    if (!caster.ctx.getCurrentSession() && chrome.cast.requestSessionById) chrome.cast.requestSessionById(last.sessionId);
  } catch (e) { /* not supported: the hint below is shown instead */ }
}
function forgetCasting() { store.set('castingOn', null); caster.guess = null; }

function onRemoteChange() {
  const before = caster.state, was = nowPlaying;
  syncRemote();
  rememberCasting();
  // A new state or a new channel (started elsewhere): the list and the card both show it.
  if (caster.state !== before || nowPlaying !== was) { pokeSongs(); renderAll(); }
}
function castLoad(s) {
  const session = caster.ctx && caster.ctx.getCurrentSession();
  if (!session) {
    // Chrome is no longer connected (the page missed it): say so instead of doing nothing.
    caster.connected = false; caster.device = ''; caster.state = 'IDLE'; caster.wantPlay = true;
    renderAll();
    snack('Inte längre ansluten till högtalaren. Tryck på knappen uppe till höger och välj den igen.');
    return;
  }
  const info = new chrome.cast.media.MediaInfo(s.url, contentType(s.url));
  info.streamType = chrome.cast.media.StreamType.LIVE;
  info.metadata = new chrome.cast.media.MusicTrackMediaMetadata();
  info.metadata.title = shortName(s.name);
  if (s.logo) info.metadata.images = [new chrome.cast.Image(s.logo)];
  const req = new chrome.cast.media.LoadRequest(info);
  req.autoplay = true;
  caster.state = 'BUFFERING';
  caster.wantPlay = true;
  const before = caster.player && caster.player.mediaInfo && caster.player.mediaInfo.contentId;
  caster.replacedUrl = before && before !== s.url ? before : null;
  caster.loadingUrl = s.url;
  const mine = s.url;
  // Never wait for ever: after 20 s, believe what the speaker says again.
  clearTimeout(caster.loadTimer); // an earlier change is replaced by this one
  caster.loadTimer = setTimeout(() => {
    if (caster.loadingUrl === mine) caster.loadingUrl = null;
    caster.replacedUrl = null;
    syncRemote(); renderAll();
  }, 20000);
  session.loadMedia(req).then(() => {
    if (caster.loadingUrl === mine) caster.loadingUrl = null;
    watchMedia(session);
    if (caster.handover && caster.handover.phase === 'loading') setTimeout(stopOldSpeaker, 2500);
    renderAll();
  }, () => {
    if (caster.loadingUrl === mine) caster.loadingUrl = null;
    caster.handover = null;
    caster.state = 'IDLE';
    playError = 'Högtalaren kunde inte spela strömmen. Kontrollera länken.';
    renderAll();
  });
}
/** Follows the speaker's player directly after a load: buffering → playing, or stopped. */
function watchMedia(session) {
  const m = session.getMediaSession && session.getMediaSession();
  if (!m || caster.media === m) return;
  caster.media = m;
  const update = () => {
    if (caster.media !== m || caster.loadingUrl) return;
    caster.state = m.playerState || 'IDLE';
    rememberCasting(); pokeSongs(); renderAll();
  };
  m.addUpdateListener(update);
  update();
}
function castStop() {
  forgetCasting();
  caster.loadingUrl = null; caster.replacedUrl = null;
  if (caster.controller && caster.player.isMediaLoaded) caster.controller.stop();
  caster.state = 'IDLE';
  caster.wantPlay = false;
}
function castVolume(level) {
  if (!caster.player) return;
  caster.player.volumeLevel = level;
  caster.controller.setVolumeLevel();
}
/** Lets go of the speaker on purpose (no "connection lost" message). */
function endCasting() {
  caster.switching = true;
  caster.ctx.endCurrentSession(true); // stops it on that speaker
  caster.connected = false; caster.device = ''; caster.state = 'IDLE';
  setTimeout(() => { caster.switching = false; }, 1500);
}
/** "Telefon": the radio moves to the phone. */
function moveToPhone() {
  if (!caster.connected) {
    if (caster.guess) { forgetCasting(); renderAll(); }
    return;
  }
  const playing = musicActive();
  forgetCasting();
  caster.wantPlay = false;
  endCasting();
  if (playing && nowPlaying) playStation(nowPlaying, stations.some((x) => x.url === nowPlaying.url));
  else renderAll();
}
/**
 * Tapping the speaker that plays: Chrome's list opens and the radio keeps playing meanwhile.
 * Choose another speaker and the radio moves there; close the list and nothing changes;
 * "Sluta casta" in Chrome's list stops it.
 */
function pickWhileCasting() {
  const playing = musicActive();
  caster.picking = { playing, url: nowPlaying && nowPlaying.url };
  const done = () => {
    const pick = caster.picking;
    caster.picking = null;
    if (pick && pick.ended && !caster.connected) {
      // Stopped from Chrome's list ("Sluta casta"), no new speaker chosen.
      forgetCasting();
      caster.wantPlay = false;
      renderAll();
    }
  };
  caster.ctx.requestSession().then(done, done);
}
function stopCasting() {
  forgetCasting();
  caster.wantPlay = false;
  endCasting();
  renderAll();
}

/**
 * Android Chrome: while casting, Chrome's dialog only controls the current speaker (no list),
 * so switching means letting go first, without stopping it: the radio plays on while you choose.
 */
function moveToOtherSpeaker() {
  const session = caster.ctx.getCurrentSession();
  const playing = musicActive();
  if (!playing || !session || !chrome.cast.requestSessionById) {
    // Nothing to keep playing (or no way back): let go and pick.
    caster.wantPlay = playing;
    endCasting();
    renderAll();
    caster.ctx.requestSession().catch(() => {});
    return;
  }
  // Let go WITHOUT stopping: the radio plays on while Chrome's list is open.
  const h = caster.handover = { phase: 'picking', oldId: session.getSessionId(), oldDevice: caster.device, newId: null, newDevice: '' };
  caster.wantPlay = true;
  caster.ctx.endCurrentSession(false);
  caster.connected = false; caster.device = ''; caster.media = null;
  renderAll();
  // Chrome sometimes refuses at once while it is still letting go of the old speaker: then try
  // again a moment later (still counts as your tap). A real "closed without choosing" takes longer.
  const open = () => {
    const t0 = Date.now();
    caster.ctx.requestSession().catch((e) => {
      if (caster.handover !== h || h.phase !== 'picking') return;
      const code = typeof e === 'string' ? e : (e && (e.code || e.message)) || '';
      if (Date.now() - t0 < 1500 && h.tries < 3) { h.tries++; setTimeout(open, 700); return; }
      if (Date.now() - t0 < 1500) snack('Chrome öppnade inte högtalarlistan (' + (code || 'okänt fel') + '). Försök igen.');
      // Closed without choosing: go back to the speaker that still plays.
      h.phase = 'rejoinOld';
      joinById(h.oldId);
      handoverTimeout(h, 'rejoinOld', () => giveUpOn(h.oldDevice));
    });
  };
  h.tries = 0;
  open();
}
/**
 * While moving between speakers: a new choice of yours wins once the new speaker is connected
 * (the move is then simply done); only while not connected to any speaker it has to wait.
 */
function busyMoving() {
  const h = caster.handover;
  if (!h || h.phase === 'picking') return false;
  if (caster.connected && h.phase === 'loading') { caster.handover = null; return false; }
  snack('Byter högtalare, ett ögonblick…');
  return true;
}
/** One step of a speaker move: true when the session event was part of it (and handled). */
function handoverSession(session) {
  const h = caster.handover;
  const id = session && session.getSessionId();
  if (h.phase === 'picking' && id !== h.oldId) {
    h.phase = 'loading'; h.newId = id; h.newDevice = caster.device;
    castLoad(nowPlaying); // then stopOldSpeaker()
    // Never stay "moving" for ever (a speaker group can be slow to answer): show what it does.
    setTimeout(() => { if (caster.handover === h && h.phase === 'loading') { caster.handover = null; syncRemote(); renderAll(); } }, 20000);
    return true;
  }
  if ((h.phase === 'picking' || h.phase === 'rejoinOld') && id === h.oldId) {
    caster.handover = null; // back where we were, still playing
    askMediaStatus(session); setTimeout(checkSpeaker, 1200);
    return true;
  }
  if (h.phase === 'stopping' && id === h.oldId) {
    h.phase = 'back';
    const back = () => setTimeout(() => { joinById(h.newId); handoverTimeout(h, 'back', () => giveUpOn(h.newDevice)); }, 600);
    caster.ctx.endCurrentSession(true); // stops the old speaker
    back();
    return true;
  }
  if (h.phase === 'back' && id === h.newId) {
    caster.handover = null;
    askMediaStatus(session); setTimeout(checkSpeaker, 1200);
    return true;
  }
  return false;
}
/** The new speaker plays: briefly join the old one to stop it, then come back. */
function stopOldSpeaker() {
  const h = caster.handover;
  if (!h || h.phase !== 'loading') return;
  h.phase = 'stopping';
  caster.ctx.endCurrentSession(false); // the new one plays on
  setTimeout(() => joinById(h.oldId), 600);
  handoverTimeout(h, 'stopping', () => {
    // No word from the old speaker (Chrome usually stops it by itself): just go back quietly.
    h.phase = 'back';
    joinById(h.newId);
    handoverTimeout(h, 'back', () => giveUpOn(h.newDevice));
  });
}
function joinById(id) {
  try { chrome.cast.requestSessionById(id); } catch (e) { /* handled by the timeout */ }
}
function handoverTimeout(h, phase, fn) {
  setTimeout(() => { if (caster.handover === h && h.phase === phase && !caster.connected) fn(); }, 8000);
}
/** Could not join again: it most likely plays there; say so (tapping the speaker reconnects). */
function giveUpOn(device) {
  caster.handover = null;
  if (nowPlaying) {
    caster.guess = { device, url: nowPlaying.url, at: Date.now() };
    store.set('castingOn', { device, url: nowPlaying.url, at: Date.now(), sessionId: null });
  }
  renderAll();
}
/** Tapping the speaker chip while casting: move to the phone, switch speaker, or stop. */
function speakerSheet() {
  const body = sheet('Spelar på ' + caster.device, 'output');
  const item = (label, ic, fn) => {
    const b = el('button', 'menu-item');
    b.type = 'button';
    b.append(icon(ic), el('span', null, label));
    b.onclick = () => { closeSheet(); fn(); };
    body.append(b);
  };
  item('Spela i telefonen', 'phone', moveToPhone);
  item('Byt högtalare', 'speaker', isAndroid ? moveToOtherSpeaker : pickWhileCasting);
  item('Sluta casta', 'close', stopCasting);
  body.append(el('p', 'muted', caster.device + ' spelar vidare medan du väljer en annan högtalare.'));
}
/** Tapping the chip when it probably still plays on a speaker: reconnect, or use the phone. */
function guessSheet() {
  const g = caster.guess;
  const body = sheet('Spelar troligen på ' + g.device, 'output');
  const item = (label, ic, fn) => {
    const b = el('button', 'menu-item');
    b.type = 'button';
    b.append(icon(ic), el('span', null, label));
    b.onclick = () => { closeSheet(); fn(); };
    body.append(b);
  };
  item('Anslut till ' + g.device, 'speaker', () => { caster.wantPlay = true; openSpeakerList(); });
  item('Använd telefonen', 'phone', () => { forgetCasting(); renderAll(); });
  body.append(el('p', 'muted', 'Välj ' + g.device + ' i Chromes lista. Radion startar om där efter några sekunder.'));
}
function chooseOutput() {
  if (busyMoving()) return;
  if (caster.ctx && caster.connected && caster.ctx.getCurrentSession()) { speakerSheet(); return; }
  if (caster.guess) { guessSheet(); return; }
  openSpeakerList();
}
/** Chrome's own list of speakers and groups (it searches when opened). */
function openSpeakerList() {
  if (caster.ctx) {
    // Chrome's own list of speakers and groups (it searches when opened); while casting it also has "Sluta casta".
    caster.ctx.requestSession().catch((e) => {
      const code = typeof e === 'string' ? e : (e && (e.code || e.message)) || '';
      if (/cancel/i.test(code)) return; // closed without choosing
      const body = sheet('Hittar ingen Chromecast');
      body.append(el('p', 'muted', 'Chrome hittade inga högtalare. Kontrollera att:'));
      const ul = el('ul', 'muted');
      ['telefonen är på samma wifi som högtalarna (inte mobildata)',
        'Chrome får hitta enheter i närheten: Inställningar → Appar → Chrome → Behörigheter → Enheter i närheten',
        'du använder Chrome, inte Samsung Internet eller Firefox'].forEach((t) => ul.append(el('li', null, t)));
      body.append(ul, el('p', 'muted', 'Felkod: ' + (code || 'okänd')));
    });
    return;
  }
  // iPhone, iPad and Safari on a Mac: Apple's own AirPlay list, right away.
  if (airplay.supported && airplay.available !== false) {
    try {
      if (!audio.getAttribute('src')) {
        // The list needs something to send: load the channel (without playing), start it once a speaker is chosen.
        const s = nowPlaying || stations[0];
        if (s) { airplay.startOnPick = s; audio.preload = 'none'; audio.src = s.url; }
      }
      audio.webkitShowPlaybackTargetPicker();
      return;
    } catch (e) { /* shown below instead */ }
  }
  if (isIOS || airplay.supported) {
    const body = sheet('Spela på en högtalare');
    if (airplay.available === false) {
      body.append(el('p', null, 'Hittar ingen AirPlay-högtalare eller Apple TV i nätverket.'));
      body.append(el('p', 'muted', 'Kontrollera att högtalaren är påslagen och i samma wifi som telefonen. Chromecast-högtalare syns inte på iPhone, de nås bara från Chrome på Android eller en dator.'));
    } else {
      body.append(el('p', 'muted', 'Använd AirPlay i Kontrollcenter:'));
      const ol = el('ol', 'muted welcome-steps');
      ['Svep ner från övre högra hörnet av skärmen.', 'Tryck på AirPlay-symbolen i ljudrutan.', 'Välj högtalare eller Apple TV.']
        .forEach((t) => ol.append(el('li', null, t)));
      body.append(ol);
    }
    return;
  }
  const body = sheet('Spela på en högtalare');
  body.append(el('p', 'muted', 'Casta till Chromecast går i Chrome, på Android eller datorn. I den här webbläsaren spelar radion i enheten.'));
}


// ============================================================ rendering

let grouped = store.get('grouped', true);
let collapsed = new Set(store.get('collapsed', []));
let editing = false;

function renderAll() {
  renderList();
  renderOutput();
  renderNowPlaying();
  renderSession();
}
function renderSongs() { renderNowPlaying(); renderSession(); if (sheetState.kind === 'song') renderSongSheet(); }

function renderOutput() {
  const h = caster.handover;
  const guess = !caster.connected && caster.guess;
  const onSpeaker = caster.connected || !!h;
  const btn = $('outBtn');
  btn.classList.toggle('on', onSpeaker);
  btn.classList.toggle('guess', !!guess);
  const busy = caster.connecting || (h && h.phase !== 'picking');
  $('outSpin').hidden = !busy;
  $('outIcon').hidden = !!busy;
  $('outIcon').firstElementChild.setAttribute('href', onSpeaker || guess ? '#i-speaker' : '#i-phone');
  const air = !onSpeaker && !guess && airplay.wireless; // AirPlay does not tell the speaker's name
  btn.classList.toggle('on', onSpeaker || air);
  if (air) $('outIcon').firstElementChild.setAttribute('href', '#i-speaker');
  const name = h ? (h.newDevice || h.oldDevice) : caster.connecting ? 'Ansluter…' : onSpeaker ? caster.device : guess ? guess.device : air ? 'AirPlay' : 'Telefon';
  $('outText').textContent = name;
  btn.setAttribute('aria-label', onSpeaker || air ? 'Ljudet går till ' + name + '. Tryck för att byta.'
    : guess ? 'Radion spelar troligen på ' + name + '. Tryck för att ansluta igen.'
    : 'Ljudet går till telefonen. Tryck för att välja högtalare.');
}

const coll = new Intl.Collator('sv');
/** Unknown country last, the phone's own country first, then by name. */
function countryOrder(codes) {
  const rank = (cc) => (!cc ? 2 : cc === HOME_CC ? 0 : 1);
  return codes.sort((a, b) => rank(a) - rank(b) || coll.compare(countryName(a), countryName(b)));
}
const byName = (a, b) => coll.compare(shortName(a.name), shortName(b.name));
/** The channels in the order the list shows them (by country, or my own order). */
function listOrder() {
  if (!grouped) return stations.slice();
  const out = [];
  for (const cc of countryOrder([...new Set(stations.map((s) => (s.cc || '').toUpperCase()))])) {
    out.push(...stations.filter((s) => (s.cc || '').toUpperCase() === cc).sort(byName));
  }
  return out;
}
function renderList() {
  const box = $('stations');
  box.textContent = '';
  $('empty').hidden = stations.length > 0;
  $('orderCountry').setAttribute('aria-selected', String(grouped));
  $('orderOwn').setAttribute('aria-selected', String(!grouped));
  $('listhead').hidden = !editing; // sorting and "Klar" only while editing (from the ⋯ menu)
  $('installBtn').hidden = installed() || editing;
  const playingUrl = musicActive() && nowPlaying ? nowPlaying.url : null;
  const rowFor = (s, i) => {
    const row = el('div', 'row' + (editing ? '' : ' tap') + (s.url === playingUrl ? ' playing' : ''));
    row.dataset.index = i;
    const logo = el('div', 'logo-wrap');
    logoTile(logo, s.logo, s.name);
    const txt = el('div', 'txt');
    txt.append(el('div', 'name', shortName(s.name)), el('div', 'sub', subtitle(s)));
    row.append(logo, txt);
    if (s.url === playingUrl && !editing) {
      const bars = el('span', 'bars');
      bars.innerHTML = '<i></i><i></i><i></i><i></i>';
      row.append(bars);
    }
    if (editing) {
      const del = el('button', 'del');
      del.type = 'button';
      del.setAttribute('aria-label', 'Ta bort ' + s.name);
      del.append(icon('trash'));
      del.onclick = () => removeStation(i);
      row.append(del);
      if (!grouped) {
        const grip = el('button', 'grip');
        grip.type = 'button';
        grip.setAttribute('aria-label', 'Håll och dra för att flytta ' + s.name);
        grip.append(icon('grip'));
        grip.addEventListener('pointerdown', (e) => startDrag(e, row));
        row.append(grip);
      }
    } else {
      row.setAttribute('role', 'button');
      row.tabIndex = 0;
      row.setAttribute('aria-label', (s.url === playingUrl ? s.name + ', spelar nu' : 'Spela ' + s.name));
      row.onclick = () => stationTapped(s);
      row.onkeydown = (e) => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); stationTapped(s); } };
    }
    return row;
  };
  if (!grouped) {
    stations.forEach((s, i) => box.append(rowFor(s, i)));
    return;
  }
  const byCountry = new Map();
  stations.forEach((s, i) => {
    const cc = (s.cc || '').toUpperCase();
    if (!byCountry.has(cc)) byCountry.set(cc, []);
    byCountry.get(cc).push([s, i]);
  });
  const keys = countryOrder([...byCountry.keys()]);
  for (const cc of keys) {
    const list = byCountry.get(cc).sort((a, b) => byName(a[0], b[0]));
    const open = !collapsed.has(cc);
    const head = el('button', 'group-head');
    head.type = 'button';
    head.setAttribute('aria-expanded', String(open));
    head.append(el('span', null, cc ? countryName(cc) : 'Okänt land'), el('span', 'count', String(list.length)), icon('chevron'));
    head.onclick = () => { if (open) collapsed.add(cc); else collapsed.delete(cc); store.set('collapsed', [...collapsed]); renderList(); };
    box.append(head);
    if (open) list.forEach(([s, i]) => box.append(rowFor(s, i)));
  }
}
function expandCountry(cc) {
  if (collapsed.delete((cc || '').toUpperCase())) { store.set('collapsed', [...collapsed]); }
}

function statusText() {
  const h = caster.handover;
  if (h && h.phase === 'picking') return 'Spelar på ' + h.oldDevice + ' · välj högtalare';
  if (h && h.phase === 'rejoinOld') return 'Spelar på ' + h.oldDevice;
  if (h && h.phase === 'loading') return 'Startar på ' + h.newDevice + '…';
  if (h && h.phase === 'stopping') return 'Spelar på ' + h.newDevice;
  if (h && h.phase === 'back') return 'Spelar på ' + h.newDevice;
  if (!caster.connected && caster.guess) return 'Spelar troligen på ' + caster.guess.device + ' · tryck på högtalaren uppe till höger';
  if (caster.connected) {
    const st = caster.state;
    if (st === 'PLAYING') return 'Spelar på ' + caster.device;
    if (st === 'BUFFERING') return 'Startar på ' + caster.device + '…';
    if (st === 'PAUSED') return 'Pausad på ' + caster.device;
    if (caster.wantPlay) return 'Tystnade på ' + caster.device + ' · tryck Spela';
    return 'Redo att spela på ' + caster.device;
  }
  if (localState === 'playing') return airplay.wireless ? 'Spelar via AirPlay' : 'Spelar' + (isIOS ? '' : ' i telefonen');
  if (localState === 'buffering') return retry.count || audio.currentTime > 0 ? 'Återansluter…' : 'Startar…';
  return 'Stoppad';
}

function renderNowPlaying() {
  const s = nowPlaying;
  $('np').hidden = !s;
  $('snack').classList.toggle('low', !s);
  if (!s) return;
  const active = musicActive();
  const playing = caster.connected ? caster.state === 'PLAYING' : localState === 'playing';
  const buffering = active && !playing;
  let status = statusText();
  const error = !active && playError;
  if (error) status = playError;

  const song = active ? songs.current : null;
  let last = null;
  if (active && !song) {
    const r = recentSongs(s.url)[0];
    if (r && Date.now() - r.at < 20 * 60000) last = r;
  }
  const shown = song || last;
  logoTile($('npLogo'), song && song.cover ? song.cover : s.logo, s.name, !!(song && song.cover));
  const channel = $('npName');
  channel.textContent = shortName(s.name);
  channel.parentElement.classList.toggle('small', !!shown);
  $('npLive').hidden = !playing;
  const title = $('npTitle'), artist = $('npArtist');
  title.hidden = !shown;
  artist.hidden = !shown || !songArtist(shown);
  if (shown) {
    title.querySelector('span').textContent = (song ? '' : 'Senast: ') + shortTitle(songTitle(shown));
    artist.querySelector('span').textContent = songArtist(shown);
    title.classList.toggle('past', !song);
    artist.classList.toggle('past', !song);
  }
  // iPhone and iPad: web pages cannot set the volume (use the phone's buttons), so no slider there.
  $('np').querySelector('.vol').hidden = isIOS && !caster.connected;
  const st = $('npStatus');
  st.textContent = status;
  st.classList.toggle('error', !!error);
  $('npText').setAttribute('aria-label', shortName(s.name) + '. '
    + (shown ? (song ? 'Låt: ' : 'Senast spelade låt: ') + songTitle(shown) + (songArtist(shown) ? '. Artist: ' + songArtist(shown) : '') + '. ' : '')
    + status + '. Tryck för mer.');
  const btn = $('npButton');
  btn.classList.toggle('buffering', buffering);
  btn.querySelector('.ring').hidden = !buffering;
  $('npIcon').querySelector('use').setAttribute('href', active ? '#i-stop' : '#i-play');
  btn.setAttribute('aria-label', active ? 'Stoppa uppspelning' : (error ? 'Försök igen' : 'Spela'));
  $('volume').setAttribute('aria-label', caster.connected ? 'Volym på ' + caster.device : 'Volym');
  if (!caster.connected && !dragging.volume) $('volume').value = volume;
}

/** Lock screen, notification and headset controls (Media Session). */
function renderSession() {
  if (!('mediaSession' in navigator)) return;
  const s = nowPlaying;
  if (!s) return;
  const song = musicActive() ? songs.current : null;
  const art = song && song.cover ? song.cover : (s.logo || new URL('icons/icon-512.png', location.href).href);
  try {
    navigator.mediaSession.metadata = new MediaMetadata({
      title: song ? shortTitle(songTitle(song)) : shortName(s.name),
      // Android shows only title + artist and cuts the end, so the station goes first.
      artist: song
        ? (songArtist(song) ? shortName(s.name) + ' · ' + songArtist(song) : shortName(s.name))
        : shortName(s.name) + ' · ' + statusText(),
      album: shortName(s.name),
      artwork: [{ src: art }],
    });
    navigator.mediaSession.playbackState = musicActive() ? 'playing' : 'paused';
  } catch (e) { /* not supported */ }
}
if ('mediaSession' in navigator) {
  const h = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* unsupported action */ } };
  h('play', () => { if (!musicActive()) togglePlay(); });
  h('pause', () => { if (musicActive()) stopMusic(); });
  h('stop', () => { if (musicActive()) stopMusic(); });
  // Live radio cannot be wound: no ±10 s buttons. Back/forward (lock screen, headphones, car) change channel.
  h('seekbackward', null);
  h('seekforward', null);
  h('seekto', null);
  h('previoustrack', () => stepChannel(-1));
  h('nexttrack', () => stepChannel(1));
}
/** The next or previous channel, in the order the list shows them. */
function stepChannel(dir) {
  const list = listOrder();
  if (!list.length) return;
  const i = nowPlaying ? list.findIndex((x) => x.url === nowPlaying.url) : -1;
  const next = list[(i + dir + list.length) % list.length];
  if (busyMoving()) return;
  playStation(next, true);
}

// ============================================================ list actions

function stationTapped(s) {
  if (busyMoving()) return;
  if (nowPlaying && nowPlaying.url === s.url && musicActive()) { snack(shortName(s.name) + ' spelar redan.'); return; }
  playStation(s, true);
}
function addStation(s, playNow) {
  let i = stations.findIndex((x) => x.url === s.url);
  if (i < 0) {
    stations.push({ name: s.name, url: s.url, cc: s.cc || guessCountry(s.url), lang: s.lang || '', br: s.br || 0, logo: s.logo || '', genre: s.genre || '' });
    saveStations();
    i = stations.length - 1;
    if (nowPlaying && nowPlaying.url === s.url) { nowPlaying = stations[i]; store.set('lastPlayed', s.url); }
    snack(shortName(s.name) + ' är tillagd i din lista.');
  } else {
    snack(shortName(s.name) + ' finns redan i din lista.');
  }
  expandCountry(stations[i].cc);
  renderAll();
  if (playNow) playStation(stations[i], true);
}
function removeStation(i) {
  const [removed] = stations.splice(i, 1);
  saveStations();
  renderAll();
  snack(shortName(removed.name) + ' är borttagen.', 'Ångra', () => {
    stations.splice(Math.min(i, stations.length), 0, removed);
    saveStations();
    renderAll();
  });
}
function startDrag(e, row) {
  e.preventDefault();
  const box = $('stations');
  const from = +row.dataset.index;
  const rows = [...box.querySelectorAll('.row')];
  const startY = e.clientY;
  const h = row.getBoundingClientRect().height;
  let to = from;
  row.classList.add('dragging');
  row.setPointerCapture(e.pointerId);
  const move = (ev) => {
    const dy = ev.clientY - startY;
    row.style.transform = 'translateY(' + dy + 'px)';
    to = Math.max(0, Math.min(rows.length - 1, from + Math.round(dy / h)));
    rows.forEach((r, k) => {
      if (r === row) return;
      const shift = (k > from && k <= to) ? -h : (k < from && k >= to) ? h : 0;
      r.style.transform = shift ? 'translateY(' + shift + 'px)' : '';
      r.style.transition = 'transform .12s';
    });
  };
  const end = () => {
    row.removeEventListener('pointermove', move);
    row.removeEventListener('pointerup', end);
    row.removeEventListener('pointercancel', end);
    if (to !== from) {
      const [s] = stations.splice(from, 1);
      stations.splice(to, 0, s);
      saveStations();
    }
    renderList();
  };
  row.addEventListener('pointermove', move);
  row.addEventListener('pointerup', end);
  row.addEventListener('pointercancel', end);
}

// ============================================================ sheets (song view, menu, forms)

// ============================================================ home screen

let installPrompt = null; // Android Chrome: lets a button add the app to the home screen
window.addEventListener('beforeinstallprompt', (e) => { e.preventDefault(); installPrompt = e; });

/** The button under the list: Chrome's own install prompt when it has one, else how to do it. */
function addToHomeScreen() {
  if (installPrompt) {
    installPrompt.prompt();
    installPrompt.userChoice.finally(() => { installPrompt = null; renderList(); });
    return;
  }
  const body = sheet('Lägg på hemskärmen');
  const ol = el('ol', 'muted welcome-steps');
  (isIOS
    ? ['Öppna sidan i Safari.', 'Tryck på Dela-knappen (fyrkanten med pil uppåt).', 'Välj "Lägg till på hemskärmen".']
    : ['Tryck på webbläsarens meny (⋮).', 'Välj "Lägg till på startskärmen" eller "Installera app".']
  ).forEach((t) => ol.append(el('li', null, t)));
  body.append(ol, el('p', 'muted', 'Då öppnas RadioCast som en vanlig app, utan webbläsarens adressfält.'));
}

function sheet(title, kind) {
  sheetState.kind = kind || 'info';
  const body = $('sheetBody');
  body.textContent = '';
  if (title) body.append(el('h2', null, title));
  $('scrim').hidden = false;
  const sh = $('sheet');
  sh.hidden = false;
  sh.style.transform = 'translateX(-50%) translateY(100%)';
  requestAnimationFrame(() => { sh.style.transform = ''; });
  return body;
}
function closeSheet() {
  const sh = $('sheet');
  if (sh.hidden) return;
  sh.style.transform = 'translateX(-50%) translateY(100%)';
  setTimeout(() => {
    sh.hidden = true; sh.style.transform = ''; $('scrim').hidden = true; sheetState.kind = null;
  }, 180);
}
$('scrim').onclick = closeSheet;
$('sheetClose').onclick = closeSheet;
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape') return;
  if (!$('sheet').hidden) closeSheet(); else if (!$('search').hidden) closeSearch();
});
// Drag the sheet down to close it (from the top bar, or anywhere when its content is scrolled to the top).
(() => {
  const sh = $('sheet');
  let y0 = null, dy = 0, t0 = 0;
  sh.addEventListener('touchstart', (e) => {
    const body = $('sheetBody');
    const onHead = e.target.closest('.sheet-head');
    if (!onHead && body.scrollTop > 0) { y0 = null; return; }
    if (e.target.closest('button, input, a') && !onHead) { y0 = e.touches[0].clientY; dy = 0; t0 = Date.now(); return; }
    y0 = e.touches[0].clientY; dy = 0; t0 = Date.now();
  }, { passive: true });
  sh.addEventListener('touchmove', (e) => {
    if (y0 == null) return;
    dy = e.touches[0].clientY - y0;
    if (dy > 0) { sh.style.transition = 'none'; sh.style.transform = 'translateX(-50%) translateY(' + dy + 'px)'; }
  }, { passive: true });
  sh.addEventListener('touchend', () => {
    if (y0 == null) return;
    sh.style.transition = '';
    const fast = dy > 40 && Date.now() - t0 < 250;
    if (dy > sh.offsetHeight * 0.25 || fast) closeSheet(); else sh.style.transform = '';
    y0 = null;
  });
})();

function showSongSheet() {
  if (!nowPlaying) return;
  sheet(null, 'song');
  renderSongSheet();
}
function renderSongSheet() {
  const s = nowPlaying;
  const body = $('sheetBody');
  if (!s) return;
  body.textContent = '';
  const song = musicActive() ? songs.current : null;
  const box = el('div', 'center');
  box.append(el('div', 'big-artist', shortName(s.name)));
  const cover = el('div', 'logo-wrap cover-big');
  logoTile(cover, song && song.cover ? song.cover : s.logo, s.name, !!(song && song.cover));
  box.append(cover);
  if (song) {
    box.append(el('div', 'label', 'LÅT'), el('div', 'big-title', songTitle(song)));
    if (songArtist(song)) box.append(el('div', 'label', 'ARTIST'), el('div', 'big-artist', songArtist(song)));
  }
  const where = caster.connected ? 'Spelar på ' + caster.device : (musicActive() ? 'Spelar i telefonen' : 'Stoppad');
  box.append(el('p', 'muted', song || !musicActive() ? where : 'Kanalen berättar inte vilken låt som spelas just nu. ' + where));
  if (song) {
    const sp = el('a', 'btn accent', 'Sök i Spotify');
    sp.href = 'https://open.spotify.com/search/' + enc(song.text.replace(' – ', ' '));
    sp.target = '_blank';
    sp.rel = 'noopener';
    box.append(sp);
  }
  body.append(box);
  const recent = recentSongs(s.url);
  if (recent.length) {
    body.append(el('div', 'label', 'TIDIGARE'));
    const ul = el('ul', 'hist');
    const fmt = new Intl.DateTimeFormat('sv', { hour: '2-digit', minute: '2-digit' });
    for (const r of recent) {
      const li = el('li');
      const b = el('a');
      b.href = 'https://open.spotify.com/search/' + enc(r.text.replace(' – ', ' '));
      b.target = '_blank';
      b.rel = 'noopener';
      b.style.display = 'block';
      b.style.color = 'inherit';
      b.style.textDecoration = 'none';
      b.style.padding = '8px 4px';
      const t = el('div', 't');
      t.append(icon('note', 'sm accent'), el('span', null, shortTitle(songTitle(r))));
      b.append(t, el('div', 'a', [songArtist(r), fmt.format(new Date(r.at))].filter(Boolean).join('  ·  ')));
      b.setAttribute('aria-label', 'Låt: ' + songTitle(r) + (songArtist(r) ? '. Artist: ' + songArtist(r) : '') + '. Sök i Spotify.');
      li.append(b);
      ul.append(li);
    }
    body.append(ul);
  }
}

function showMenu() {
  const body = sheet('RadioCast', 'menu');
  const item = (label, ic, fn) => {
    const b = el('button', 'menu-item');
    b.type = 'button';
    b.append(icon(ic), el('span', null, label));
    b.onclick = () => { closeSheet(); setTimeout(fn, 200); };
    body.append(b);
  };
  if (caster.connected) {
    item('Dela kontrollen över ' + caster.device, 'speaker', shareControl);
    item('Sluta casta till ' + caster.device, 'close', stopCasting);
  }
  if (nowPlaying) item('Dela kanalen ' + shortName(nowPlaying.name), 'note', shareListen);
  if (stations.length) item('Redigera listan', 'grip', () => { editing = true; renderList(); window.scrollTo({ top: 0, behavior: 'smooth' }); });
  item('Dela appen med en vän', 'plus', shareApp);
  item('Lägg till med egen länk', 'plus', askForStation);
  item('Om RadioCast', 'note', () => {
    const b = sheet('Om RadioCast');
    b.append(el('p', null, VERSION + '. Webbradio i telefonen, med låtinformation.'));
    b.append(el('p', 'muted', 'I Chrome (Android och dator) kan du casta till Chromecast. På iPhone: lägg till på hemskärmen via Dela-knappen i Safari, och använd AirPlay för högtalare.'));
    b.append(el('p', 'muted', 'Kanaler och logotyper från radio-browser.info. Låtinformation från kanalernas egna listor.'));
  });
}
/** The app's address, for friends: with my channels, or just the app (they start with the basic list). */
function shareApp() {
  const body = sheet('Dela appen');
  body.append(el('p', 'muted', 'Din vän öppnar länken i webbläsaren, inget behöver installeras. Kanallistan sparas sedan i deras telefon, och de kan ändra den fritt.'));
  const item = (label, sub, fn) => {
    const b = el('button', 'menu-item');
    b.type = 'button';
    const t = el('span'); t.append(el('span', null, label), el('br'), el('small', 'muted', sub));
    b.append(icon('plus'), t);
    b.onclick = () => { closeSheet(); setTimeout(fn, 200); };
    body.append(b);
  };
  item('Med mina kanaler', stations.length + ' kanaler följer med', shareList);
  item('Bara appen', 'De börjar med grundlistan', () => shareLink('RadioCast', appLink(),
    'Länken till appen är kopierad.', SHARE_TEXT));
}
function appLink() { return location.origin + location.pathname; }
/** The app with my channel list in the link (it opens with a question before anything is replaced). */
function shareList() {
  const data = btoa(unescape(encodeURIComponent(JSON.stringify(stations.map((s) => [s.name, s.url, s.cc, s.br, s.logo, s.genre])))));
  shareLink('RadioCast', appLink() + '#lista=' + data, 'Länken med appen och din lista är kopierad.', SHARE_TEXT);
}
function shareLink(title, link, done, text) {
  if (navigator.share) navigator.share(text ? { title, text, url: link } : { title, url: link }).catch(() => {});
  else if (navigator.clipboard) navigator.clipboard.writeText(link).then(() => snack(done), () => prompt('Kopiera länken:', link));
  else prompt('Kopiera länken:', link);
}
/**
 * A link that lets another phone or computer at home take over the control of this cast:
 * it joins by the cast's id, so the radio keeps playing.
 */
function shareControl() {
  const session = caster.ctx && caster.ctx.getCurrentSession();
  const id = session && session.getSessionId ? session.getSessionId() : null;
  if (!id || !nowPlaying) { snack('Hittar ingen pågående castning att dela.'); return; }
  const q = new URLSearchParams({ styr: id, url: nowPlaying.url, namn: nowPlaying.name, hogtalare: caster.device });
  shareLink('Styr radion på ' + caster.device, location.origin + location.pathname + '#' + q,
    'Länken är kopierad. Öppna den i Chrome på en annan enhet i samma wifi.');
}
/** A link that starts the same channel for anyone, anywhere (in their phone). */
function shareListen() {
  const q = new URLSearchParams({ lyssna: nowPlaying.url, namn: nowPlaying.name });
  shareLink('Lyssna på ' + shortName(nowPlaying.name), location.origin + location.pathname + '#' + q, 'Länken till kanalen är kopierad.');
}
/** Opening a shared link: join a cast, or offer to listen to a channel. */
function openSharedLink() {
  const q = new URLSearchParams(location.hash.substring(1));
  const name = q.get('namn') || 'Radio';
  const find = (url) => stations.find((s) => s.url === url) || { name, url, cc: guessCountry(url), lang: '', br: 0, logo: '', genre: '' };
  if (q.get('styr') && q.get('url')) {
    history.replaceState(null, '', location.pathname);
    nowPlaying = find(q.get('url'));
    caster.joinId = q.get('styr');
    caster.guess = { device: q.get('hogtalare') || 'högtalaren', url: nowPlaying.url, at: Date.now() };
    renderAll();
    snack('Ansluter till radion på ' + caster.guess.device + '…');
    setTimeout(() => {
      if (!caster.connected && caster.joinId) {
        snack('Kunde inte ansluta. Öppna länken i Chrome på en enhet i samma wifi som högtalaren.');
        caster.joinId = null;
      }
    }, 8000);
    return true;
  }
  if (q.get('lyssna')) {
    history.replaceState(null, '', location.pathname);
    const s = find(q.get('lyssna'));
    nowPlaying = s;
    renderAll();
    snack('Delad kanal: ' + shortName(s.name), 'Lyssna', () => playStation(s, stations.some((x) => x.url === s.url)));
    return true;
  }
  return false;
}
function importFromLink() {
  if (openSharedLink()) return;
  const m = /#lista=([A-Za-z0-9+/=]+)/.exec(location.hash);
  if (!m) return;
  history.replaceState(null, '', location.pathname);
  try {
    const list = JSON.parse(decodeURIComponent(escape(atob(m[1])))).map(([name, url, cc, br, logo, genre]) => ({ name, url, cc, lang: '', br, logo, genre }));
    const body = sheet('Använda den delade listan?');
    body.append(el('p', 'muted', list.length + ' kanaler. Din nuvarande lista med ' + stations.length + ' kanaler ersätts.'));
    const row = el('div', 'actions');
    const no = el('button', 'btn outline', 'Avbryt'); no.onclick = closeSheet;
    const yes = el('button', 'btn accent', 'Använd listan');
    yes.onclick = () => { stations = list; saveStations(); closeSheet(); renderAll(); snack('Listan är inläst.'); };
    row.append(no, yes);
    body.append(row);
  } catch (e) { snack('Länken med listan gick inte att läsa.'); }
}
function askForStation() {
  const body = sheet('Lägg till med egen länk');
  body.append(el('p', 'muted', 'Klistra in en direktlänk till strömmen. Den slutar ofta på .mp3, .aac eller .m3u8.'));
  const name = el('input', 'field'); name.placeholder = 'Namn, t.ex. Radio Nova';
  const url = el('input', 'field'); url.placeholder = 'Strömlänk, https://…mp3'; url.type = 'url';
  const row = el('div', 'actions');
  const add = el('button', 'btn accent', 'Lägg till');
  add.onclick = () => {
    const n = name.value.trim(), u = url.value.trim();
    if (!n || !/^https?:\/\//.test(u)) { snack('Fyll i ett namn och en länk som börjar med http.'); return; }
    closeSheet();
    addStation({ name: n, url: u, cc: guessCountry(u) }, false);
  };
  row.append(add);
  body.append(name, url, row);
  setTimeout(() => name.focus(), 250);
}

// ============================================================ search screen

const GENRES = [['Melodisk techno', 'melodic'], ['Synthpop', 'synthpop'], ['Deep house', 'deep house'], ['Chill', 'chillout'],
  ['Dubstep', 'dubstep'], ['Drum & bass', 'drum and bass'], ['Afrobeats', 'afro'], ['Fransk rap', 'mix:rap fr|french rap'],
  ['Hiphop', 'hip hop'], ['Elektroniskt', 'electronic'], ['Sverige', 'land:SE'], ['Frankrike', 'land:FR']];
const CHIP_COLORS = ['sage', 'sand', 'sky', 'rose', 'lav'];
let searchSeq = 0, searchTimer = null, results = [];

function openSearch() {
  $('search').hidden = false;
  document.body.style.overflow = 'hidden';
  renderGenres();
  renderRecent();
  setTimeout(() => $('query').focus(), 50);
}
function closeSearch() {
  $('search').hidden = true;
  document.body.style.overflow = '';
}
function renderGenres(selected) {
  const box = $('genres');
  box.textContent = '';
  GENRES.forEach(([label, tag], i) => {
    const c = el('button', 'gchip' + (selected === tag ? ' on' : ''), label);
    c.type = 'button';
    const col = CHIP_COLORS[i % CHIP_COLORS.length];
    c.style.background = 'var(--' + col + '-bg)';
    c.style.color = 'var(--' + col + '-fg)';
    c.onclick = () => {
      $('query').value = '';
      renderGenres(tag);
      renderRecent();
      if (tag.startsWith('land:')) lookup(() => stationSearch('countrycode=' + tag.substring(5)).then((r) => ({ results: r })), 'Populäraste i ' + label);
      else if (tag.startsWith('mix:')) lookup(() => mix(tag.substring(4).split('|')), label);
      else lookup(() => stationSearch('tag=' + enc(tag)).then((r) => ({ results: r })), label);
    };
    box.append(c);
  });
}
function renderRecent() {
  const box = $('recent');
  const recent = store.get('recentSearches', []);
  box.textContent = '';
  box.hidden = !recent.length || $('query').value.trim() !== '';
  if (box.hidden) return;
  box.append(el('span', 'label', 'Senaste:'));
  for (const q of recent) {
    const c = el('button', 'gchip recent', q);
    c.type = 'button';
    c.onclick = () => { $('query').value = q; runQuery(); };
    box.append(c);
  }
}
function remember(q) {
  const list = store.get('recentSearches', []).filter((x) => x.toLowerCase() !== q.toLowerCase());
  list.unshift(q);
  store.set('recentSearches', list.slice(0, 6));
}
function runQuery() {
  const q = $('query').value.trim();
  if (q.length < 2) return;
  renderGenres();
  lookup(() => freeSearch(q), '”' + q + '”', q);
}
async function lookup(what, label, rememberQ) {
  const seq = ++searchSeq;
  $('info').textContent = 'Söker ' + label + '…';
  try {
    const out = await what();
    if (seq !== searchSeq) return;
    results = out.results;
    if (rememberQ && results.length) remember(rememberQ);
    const head = out.note ? out.note + ' · ' : '';
    $('info').textContent = results.length ? head + results.length + ' kanaler'
      : head + 'Inga kanaler hittades för ' + label + '. Prova ett annat ord eller en genre.';
    renderResults();
  } catch (e) {
    if (seq === searchSeq) $('info').textContent = e.message;
  }
}
function renderResults() {
  const ul = $('results');
  ul.textContent = '';
  results.forEach((r) => {
    const li = el('li', 'res');
    const head = el('button', 'res-head');
    head.type = 'button';
    const logo = el('div', 'logo-wrap');
    logoTile(logo, r.logo, r.name);
    const txt = el('div', 'txt');
    txt.append(el('div', 'name', shortName(r.name)), el('div', 'sub', [r.cc, r.br > 0 ? r.br + ' kbps' : ''].filter(Boolean).join(' · ')));
    head.append(logo, txt, icon('chevron', 'sm'));
    head.setAttribute('aria-expanded', 'false');
    const more = el('div', 'res-more');
    more.hidden = true;
    const tags = (r.tags || '').split(',').map((t) => t.trim()).filter(Boolean).slice(0, 4).join(', ');
    more.append(el('div', 'detail', [r.name !== shortName(r.name) ? r.name : '', resultDetails(r), tags].filter(Boolean).join('\n')));
    const btns = el('div', 'btns');
    const tryB = el('button', 'btn outline', 'Provlyssna');
    tryB.onclick = () => playStation(toStation(r), stations.some((s) => s.url === r.url));
    const addB = el('button', 'btn accent', 'Lägg till');
    addB.onclick = () => addStation(toStation(r), false);
    btns.append(tryB, addB);
    more.append(btns);
    head.onclick = () => { more.hidden = !more.hidden; head.setAttribute('aria-expanded', String(!more.hidden)); };
    li.append(head, more);
    ul.append(li);
  });
}
$('query').addEventListener('input', () => {
  clearTimeout(searchTimer);
  renderRecent();
  if ($('query').value.trim().length >= 2) searchTimer = setTimeout(runQuery, 450);
});
$('query').addEventListener('keydown', (e) => { if (e.key === 'Enter') { clearTimeout(searchTimer); runQuery(); $('query').blur(); } });
$('searchBack').onclick = closeSearch;
$('addLink').onclick = askForStation;

// ============================================================ snack

let snackTimer = null;
function snack(text, actionLabel, action) {
  const box = $('snack');
  $('snackText').textContent = text;
  const btn = $('snackAction');
  btn.hidden = !actionLabel;
  if (actionLabel) { btn.textContent = actionLabel; btn.onclick = () => { box.hidden = true; action(); }; }
  box.hidden = false;
  clearTimeout(snackTimer);
  snackTimer = setTimeout(() => { box.hidden = true; }, actionLabel ? 6000 : 4500);
}

// ============================================================ wiring

$('outBtn').onclick = chooseOutput;
$('menuBtn').onclick = showMenu;
$('searchBar').onclick = openSearch;
$('emptySearch').onclick = openSearch;
$('orderCountry').onclick = () => { grouped = true; store.set('grouped', true); renderList(); };
$('orderOwn').onclick = () => { grouped = false; store.set('grouped', false); renderList(); };
$('editBtn').onclick = () => { editing = false; renderList(); };
$('installBtn').onclick = addToHomeScreen;
window.addEventListener('appinstalled', () => { installPrompt = null; renderList(); });
$('npButton').onclick = togglePlay;
$('npText').onclick = showSongSheet;
const vol = $('volume');
let volTimer = 0;
vol.addEventListener('pointerdown', () => { dragging.volume = true; });
vol.addEventListener('pointerup', () => { dragging.volume = false; setVolume(+vol.value); });
vol.addEventListener('input', () => {
  const now = Date.now();
  if (now - volTimer > 150) { volTimer = now; setVolume(+vol.value); }
});
vol.addEventListener('change', () => setVolume(+vol.value));

if ('serviceWorker' in navigator) {
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js', { updateViaCache: 'none' }).catch(() => {}));
}

loadPatterns().then(() => pokeSongs());
importFromLink();
renderAll();
// Restarted while the radio still plays on a speaker? Chrome does not always reconnect by itself:
// after a moment, show where it probably plays, so one tap reconnects without restarting it.
setTimeout(() => {
  const last = store.get('castingOn', null);
  if (caster.connected || caster.guess || !last || Date.now() - last.at > 12 * 3600000) return; // rejoined, or nothing to say
  caster.guess = last;
  nowPlaying = stations.find((s) => s.url === last.url) || nowPlaying;
  renderAll();
}, 5000);

// For the automatic tests in GitHub (and for curious minds in the browser console).
window.radiocast = {
  version: VERSION,
  get stations() { return stations; },
  get song() { return songs.current; },
  get nowPlaying() { return nowPlaying; },
  setPlaying(i) { nowPlaying = stations[i]; localState = 'playing'; songs.watchedUrl = null; renderAll(); return songTick(); },
  recentSongs,
  stepChannel,
  listOrder,
  freeSearch,
};

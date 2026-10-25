// Changing channel on a speaker, with a fake Google Cast SDK (no network): the speaker answers (ok), never answers (hang) or refuses (fail).
// Usage: node web-tests/cast.mjs <url> <ok|hang|fail>
import { chromium } from 'playwright';
const url = process.argv[2] || 'http://localhost:8765/index.html';
const mode = process.argv[3] || 'ok';
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 390, height: 844 } });
const errors = []; p.on('pageerror', (e) => errors.push(e.message));
await p.route(/^https?:\/\/(?!localhost)/, (r) => r.abort());
await p.addInitScript((mode) => {
  localStorage.setItem('rc.welcomed', 'true');
  const listeners = {}; let remote = null;
  const player = { isMediaLoaded: false, playerState: 'IDLE', mediaInfo: null, volumeLevel: 0.5 };
  const media = { playerState: 'PLAYING', media: null, addUpdateListener(f) { this.f = f; }, getStatus(a, ok) { ok && ok(); } };
  const session = {
    getSessionId: () => 'S1', getCastDevice: () => ({ friendlyName: 'Bottenplan' }), getMediaSession: () => (player.isMediaLoaded ? media : null),
    sendMessage: () => Promise.resolve(),
    loadMedia(req) {
      window.__loads = (window.__loads || 0) + 1;
      if (mode === 'hang') return new Promise(() => {});
      if (mode === 'fail') return Promise.reject('LOAD_FAILED');
      return new Promise((res) => setTimeout(() => {
        player.isMediaLoaded = true; player.playerState = 'PLAYING'; player.mediaInfo = { contentId: req.media.contentId, metadata: { title: req.media.metadata.title } };
        media.media = { contentId: req.media.contentId }; media.playerState = 'PLAYING';
        (listeners.any || []).forEach((f) => f()); res();
      }, 500));
    },
  };
  window.chrome = { cast: { media: { DEFAULT_MEDIA_RECEIVER_APP_ID: 'X', StreamType: { LIVE: 'LIVE' }, MediaInfo: function (id, ct) { this.contentId = id; }, MusicTrackMediaMetadata: function () {}, LoadRequest: function (m) { this.media = m; } }, Image: function (u) { this.url = u; }, AutoJoinPolicy: { ORIGIN_SCOPED: 'o' }, requestSessionById() {} } };
  const ctx = {
    setOptions() {}, addEventListener(t, f) { (listeners[t] = listeners[t] || []).push(f); },
    getCurrentSession: () => session, requestSession: () => Promise.resolve(), endCurrentSession() {},
  };
  window.cast = { framework: {
    CastContext: { getInstance: () => ctx },
    RemotePlayer: function () { return player; },
    RemotePlayerController: function () { return { addEventListener(t, f) { (listeners.any = listeners.any || []).push(f); }, stop() {}, setVolumeLevel() {} }; },
    CastContextEventType: { CAST_STATE_CHANGED: 'cs', SESSION_STATE_CHANGED: 'ss' }, CastState: { NO_DEVICES_AVAILABLE: 'none' },
    SessionState: { SESSION_STARTED: 'SESSION_STARTED', SESSION_RESUMED: 'SESSION_RESUMED', SESSION_ENDED: 'SESSION_ENDED', SESSION_START_FAILED: 'F', SESSION_STARTING: 'SESSION_STARTING' },
    RemotePlayerEventType: { ANY_CHANGE: 'any' },
  } };
  window.__startCast = () => { window.__onGCastApiAvailable(true); (listeners.ss || []).forEach((f) => f({ sessionState: 'SESSION_STARTED' })); };
}, mode);
await p.goto(url); await p.waitForTimeout(600);
await p.evaluate(() => window.__startCast()); await p.waitForTimeout(3500);
const show = async (label) => console.log(label.padEnd(22), '| chip', await p.textContent('#outText'), '| card', (await p.innerText('#np')).replace(/\s+/g, ' ').slice(0, 70), '| list', await p.$$eval('.row.playing .name', (n) => n.map((x) => x.textContent).join(',')), '| loads', await p.evaluate(() => window.__loads));
await show('ansluten');
await p.locator('.row').nth(0).click(); await p.waitForTimeout(2000); await show('kanal 1, 2 s');
await p.locator('.row').nth(3).click(); await p.waitForTimeout(2000); await show('kanal 4, 2 s');
if (mode !== 'ok') { await p.waitForTimeout(20000); await show('kanal 4, 22 s'); console.log('snack:', await p.textContent('#snackText')); }
const card = (await p.innerText('#np')).replace(/\s+/g, ' ');
const list = await p.$$eval('.row.playing .name', (n) => n.map((x) => x.textContent).join(','));
const name = await p.locator('.row .name').nth(3).textContent();
const fail = (m) => errors.push(mode + ': ' + m);
if (mode === 'ok') {
  if (!card.includes(name) || !card.includes('Spelar på Bottenplan')) fail('kortet visar inte nya kanalen som spelande: ' + card);
  if (list !== name) fail('listan markerar inte nya kanalen: ' + list);
}
if (mode === 'hang' && !(await p.textContent('#snackText')).includes('svarade inte')) fail('inget besked när högtalaren inte svarar');
if (mode === 'fail' && !card.includes('kunde inte spela')) fail('inget felbesked när högtalaren vägrar');
await b.close();
if (errors.length) { console.error(errors.join('\n')); process.exit(1); }
console.log('Kanalbyte på högtalare (' + mode + '): allt gick bra.');

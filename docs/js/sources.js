/*
 * Where the current song comes from: the channels' own lists (meta-sources.json: Radio France,
 * Nova, Sveriges Radio), else the radio server's status page. Plus cover art from Apple's search.
 * No screen code here; the app decides what to show.
 */
import { getJson, simplify, enc } from './util.js';
import { cleanSong, splitSong } from './songtext.js';

let patterns = [];

export async function loadPatterns(url = 'meta-sources.json') {
  try { patterns = (await getJson(url)).sources || []; } catch (e) { patterns = []; }
}
/** For tests: use these sources instead of fetching the file. */
export function setPatterns(list) { patterns = list || []; }

/** The known source for a stream: { p, key, api }, or null. */
export function patternFor(url) {
  for (const p of patterns) {
    try {
      const m = new RegExp(p.match).exec(url);
      if (!m) continue;
      let key = m[1] || '';
      if (p.map) { if (!(key in p.map)) continue; key = String(p.map[key]); }
      return { p, key, api: p.url.replace('$1', key) };
    } catch (e) { /* bad pattern */ }
  }
  return null;
}

/** The string at a path like "now.firstLine.title" or "results[0].name", or null. */
export function at(obj, path) {
  let cur = obj;
  for (const part of String(path).split('.')) {
    const m = /^([^[]*)(?:\[(\d+)\])?$/.exec(part);
    if (!m) return null;
    if (m[1]) cur = cur && typeof cur === 'object' ? cur[m[1]] : undefined;
    if (m[2] != null) cur = Array.isArray(cur) ? cur[+m[2]] : undefined;
    if (cur == null) return null;
  }
  return typeof cur === 'string' ? cur : typeof cur === 'number' ? String(cur) : null;
}

/** "{a.b} – {c.d}" filled from the JSON; parts that are missing are left out. */
export function fill(template, json) {
  const values = [];
  let missing = false;
  const out = template.replace(/\{([^}]+)\}/g, (_, path) => {
    const v = at(json, path);
    if (!v || !v.trim()) { missing = true; return ''; }
    values.push(v.trim());
    return v.trim();
  });
  if (!values.length) return null;
  return missing ? values.join(' – ') : out;
}

/**
 * Radio France: the song playing now ({ text, cover }, text null during talk), and the songs
 * before it in the same answer (past), for the history.
 */
export function liveMeta(json, url, stationName, nowSeconds = Date.now() / 1000) {
  let current = null;
  const past = [];
  for (const st of Object.values(json.steps || {})) {
    if (!st || st.embedType !== 'song' || st.start > nowSeconds) continue;
    const artist = (typeof st.performers === 'string' && st.performers.trim()) || (typeof st.authors === 'string' && st.authors.trim()) || '';
    const title = typeof st.title === 'string' ? st.title.trim() : '';
    if (!title) continue;
    const text = artist ? artist + ' – ' + title : title;
    const cover = typeof st.visual === 'string' && st.visual.startsWith('http') ? st.visual : null;
    if (st.end + 5 >= nowSeconds) current = { text, cover };
    else {
      const c = cleanSong(text, stationName);
      if (c) past.push({ text: c, cover, url, at: st.start * 1000 });
    }
  }
  return { text: current ? current.text : null, cover: current ? current.cover : null, past };
}

/** The current song from the channel's own list: { text, cover, past? }, null if not found. */
export async function fromPattern(url, stationName) {
  const hit = patternFor(url);
  if (!hit) return null;
  const json = await getJson(hit.api);
  const type = hit.p.type || 'json';
  if (type === 'radiofrance-livemeta') return liveMeta(json, url, stationName);
  let item = json;
  if (type === 'list') {
    const arr = Array.isArray(json) ? json : json[hit.p.list];
    const norm = (u) => (u || '').replace(/^https?:\/\//, '').toLowerCase();
    item = (arr || []).find((o) => norm(at(o, hit.p.select)) === norm(url));
    if (!item) return null;
  }
  return { text: fill(hit.p.song, item), cover: hit.p.cover ? at(item, hit.p.cover) : null };
}

/** Icecast servers list every stream with its title on a status page. */
export async function fromIcecast(url) {
  const u = new URL(url);
  const j = await getJson(u.origin + '/status-json.xsl', 6000);
  let src = j.icestats && j.icestats.source;
  if (!src) return null;
  if (!Array.isArray(src)) src = [src];
  const m = src.find((o) => (o.listenurl || '').endsWith(u.pathname)) || (src.length === 1 ? src[0] : null);
  if (!m) return null;
  const title = (m.title || '').trim(), artist = (m.artist || '').trim();
  return { text: artist ? artist + ' – ' + title : title };
}

/** Songs from a channel's own list of the latest songs (Sveriges Radio), that have started. */
export function historyFrom(json, h, url, stationName, now = Date.now()) {
  const out = [];
  for (const o of json[h.list] || []) {
    const text = cleanSong(fill(h.song, o), stationName);
    const m = /(\d{9,13})/.exec(at(o, h.time) || '');
    if (!text || !m) continue;
    let t = +m[1];
    if (t < 1e11) t *= 1000;
    if (t < now) out.push({ text, cover: null, url, at: t }); // later ones are only planned
  }
  return out;
}
export async function fetchHistory(url, stationName) {
  const hit = patternFor(url);
  if (!hit || !hit.p.history) return [];
  const h = hit.p.history;
  return historyFrom(await getJson(h.url.replace('$1', hit.key)), h, url, stationName);
}

const covers = new Map();
/** The cover given by the source, or one from Apple's music search when the artist matches. */
export async function coverFor(text, given) {
  if (given && /^https?:/.test(given)) return given;
  if (!text.includes(' – ')) return null;
  if (covers.has(text)) return covers.get(text);
  let found = null;
  try {
    const [artist] = splitSong(text);
    const j = await getJson('https://itunes.apple.com/search?entity=song&limit=3&term=' + enc(text.replace(' – ', ' ')));
    const a = simplify(artist);
    for (const r of j.results || []) {
      const ra = simplify(r.artistName);
      if (a && ra && (ra.includes(a) || a.includes(ra)) && r.artworkUrl100) { found = r.artworkUrl100.replace('100x100bb', '300x300bb'); break; }
    }
  } catch (e) { /* the station's logo is shown instead */ }
  covers.set(text, found);
  return found;
}

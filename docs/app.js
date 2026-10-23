/* RadioCast web: web radio in the browser, song info, and casting to Chromecast from Chrome. */
'use strict';

const VERSION = 'Webb 1.0';

// ============================================================ small helpers

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};
const icon = (name, cls) => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
  s.setAttribute('viewBox', '0 0 24 24');
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', '#i-' + name);
  s.appendChild(u);
  return s;
};
const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem('rc.' + key); return v == null ? fallback : JSON.parse(v); } catch (e) { return fallback; }
  },
  set(key, value) { try { localStorage.setItem('rc.' + key, JSON.stringify(value)); } catch (e) { /* private mode */ } },
};
const fold = (s) => (s || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/\s+/g, ' ').trim();
const simplify = (s) => (s || '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');

async function getJson(url, timeoutMs = 8000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ctrl.signal, cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  } finally { clearTimeout(t); }
}

// ============================================================ stations

const DEFAULT_STATIONS = [
  { name: 'Radio Nova', url: 'https://novazz.ice.infomaniak.ch/novazz-128.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Eklektiskt' },
  { name: 'Nouvo Nova (ny musik)', url: 'https://nova-nouvo.ice.infomaniak.ch/nova-nouvo-128.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Ny musik' },
  { name: "Mouv'", url: 'https://icecast.radiofrance.fr/mouv-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Hiphop' },
  { name: 'FIP', url: 'https://icecast.radiofrance.fr/fip-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Eklektiskt' },
  { name: 'France Inter', url: 'https://icecast.radiofrance.fr/franceinter-midfi.mp3', cc: 'FR', lang: 'french', br: 128, logo: '', genre: 'Nyheter och kultur' },
  { name: 'Sveriges Radio P3', url: 'https://live1.sr.se/p3-aac-320', cc: 'SE', lang: 'swedish', br: 320, logo: '', genre: 'Pop' },
];

let stations = store.get('stations', null) || DEFAULT_STATIONS.slice();
const saveStations = () => store.set('stations', stations);

const COUNTRY_NAMES = (() => {
  try { return new Intl.DisplayNames(['sv'], { type: 'region' }); } catch (e) { return null; }
})();
function countryName(cc) {
  if (!cc || cc.length !== 2) return '';
  try { return (COUNTRY_NAMES && COUNTRY_NAMES.of(cc.toUpperCase())) || cc.toUpperCase(); } catch (e) { return cc.toUpperCase(); }
}

/** "Radio Nova 101.5 FM | Paris | Musique" becomes "Radio Nova 101.5 FM". */
function shortName(name) {
  const n = (name || '').trim().replace(/\s+/g, ' ');
  if (n.length <= 28) return n;
  let cut = n.length;
  for (const sep of [' | ', ' - ', ' – ', ' — ', ' :: ', ' • ', ' / ', ' (', ' [', ': ', ', ']) {
    const i = n.indexOf(sep);
    if (i >= 6 && i < cut) cut = i;
  }
  let s = n.substring(0, cut).trim();
  if (s.length > 40) s = s.substring(0, 38).trim() + '…';
  return s;
}

/** Letters for the logo tile: "Radio Nova" → NOVA, "Sveriges Radio P3" → P3. */
function initials(name) {
  const words = shortName(name).toUpperCase().replace(/['’]/g, '').replace(/[^\p{L}\p{N} ]/gu, ' ').trim().split(/\s+/).filter(Boolean);
  if (!words.length) return '?';
  let keep = words.filter((w) => !['RADIO', 'SVERIGES', 'FM', 'THE'].includes(w));
  if (!keep.length) keep = [words[0]];
  const last = keep[keep.length - 1];
  if (keep.length === 1 || last.length <= 3) {
    const w = keep.length === 1 ? keep[0] : last;
    return w.length <= 5 ? w : w.substring(0, 2);
  }
  return keep.slice(0, 3).map((w) => w[0]).join('');
}
function palette(name) {
  let h = 0;
  for (const c of (name || '').toLowerCase()) h = (Math.imul(h, 31) + c.codePointAt(0)) | 0;
  return ((h % 5) + 5) % 5;
}

/** Logo tile: the station's logo (or a song cover) on a pastel tile with initials as fallback. */
function logoTile(target, imageUrl, name, cover) {
  target.className = target.className.replace(/\bp\d\b/g, '').trim() + ' p' + palette(name);
  target.textContent = initials(name);
  if (imageUrl && /^https?:\/\//.test(imageUrl)) {
    const img = new Image();
    img.alt = '';
    img.referrerPolicy = 'no-referrer';
    img.className = cover ? 'cover' : '';
    img.onload = () => { if (target.dataset.src === imageUrl) { target.textContent = ''; target.appendChild(img); } };
    target.dataset.src = imageUrl;
    img.src = imageUrl.replace(/^http:/, 'https:');
  } else {
    target.dataset.src = '';
  }
}

function subtitle(s) {
  return [s.genre, s.br > 0 ? s.br + ' kbps' : ''].filter(Boolean).join(' · ');
}

function guessCountry(url) {
  try {
    const host = new URL(url).hostname.toLowerCase();
    const known = { 'sr.se': 'SE', 'sverigesradio.se': 'SE', 'radiofrance.fr': 'FR', 'infomaniak.ch': 'FR', 'bbc.co.uk': 'GB' };
    for (const k in known) if (host === k || host.endsWith('.' + k)) return known[k];
    const tld = host.split('.').pop();
    if (tld === 'uk') return 'GB';
    if (/^[a-z]{2}$/.test(tld) && !['fm', 'io', 'me', 'tv', 'co', 'cc', 'ws', 'am'].includes(tld)) return tld.toUpperCase();
  } catch (e) { /* not an address */ }
  return '';
}

// ============================================================ search (radio-browser.info)

const COUNTRIES = {};
[['SE', 'sverige', 'sweden', 'svensk', 'svenskt'], ['NO', 'norge', 'norway', 'norsk'], ['DK', 'danmark', 'denmark', 'dansk'],
  ['FI', 'finland', 'finsk'], ['FR', 'frankrike', 'france', 'fransk', 'franskt'], ['DE', 'tyskland', 'germany', 'deutschland', 'tysk'],
  ['GB', 'england', 'storbritannien', 'britain', 'uk', 'brittisk'], ['US', 'usa', 'amerika', 'amerikansk'],
  ['ES', 'spanien', 'spain', 'spansk'], ['IT', 'italien', 'italy', 'italiensk'], ['NL', 'nederlanderna', 'holland', 'netherlands'],
  ['BE', 'belgien', 'belgium'], ['CH', 'schweiz', 'switzerland'], ['AT', 'osterrike', 'austria'], ['CA', 'kanada', 'canada'],
  ['PL', 'polen', 'poland'], ['EE', 'estland', 'estonia'], ['IE', 'irland', 'ireland'], ['PT', 'portugal'], ['GR', 'grekland', 'greece'],
  ['JP', 'japan'], ['BR', 'brasilien', 'brazil'], ['AU', 'australien', 'australia']]
  .forEach(([cc, ...words]) => words.forEach((w) => { COUNTRIES[w] = cc; }));
const LANGUAGES = {};
const LANGUAGE_SV = {};
[['swedish', 'svenska'], ['english', 'engelska'], ['french', 'franska'], ['german', 'tyska'], ['spanish', 'spanska'],
  ['norwegian', 'norska'], ['danish', 'danska'], ['finnish', 'finska'], ['italian', 'italienska'], ['dutch', 'nederlandska'],
  ['arabic', 'arabiska'], ['portuguese', 'portugisiska'], ['polish', 'polska'], ['russian', 'ryska'], ['greek', 'grekiska'],
  ['turkish', 'turkiska'], ['persian', 'persiska'], ['japanese', 'japanska']]
  .forEach(([en, sv]) => { LANGUAGES[en] = en; LANGUAGES[sv] = en; LANGUAGE_SV[en] = sv; });
const SYNONYMS = { elektroniskt: 'electronic', elektronisk: 'electronic', elektro: 'electro', klassiskt: 'classical', klassisk: 'classical',
  nyheter: 'news', prat: 'talk', julmusik: 'christmas', jul: 'christmas', hiphop: 'hip hop', dansmusik: 'dance', barn: 'kids',
  lugnt: 'chillout', lugn: 'chillout', sport: 'sports' };
const VOCABULARY = new Set(('electronic electro techno house deep trance dubstep drum bass ambient chillout chill lounge downtempo jazz blues rock pop hip hop rap '
  + 'classical country reggae metal punk soul funk disco synthpop synthwave indie alternative oldies news talk sports schlager dansband latin afro '
  + 'afrobeats minimal melodic progressive dance hits christmas gospel folk world phonk lofi garage breakbeat jungle hardstyle psytrance experimental '
  + 'acoustic instrumental soundtrack kids comedy culture public radio nova mouv fip france inter sveriges megapol bandit rix star lugna favoriter '
  + 'skyrock fun nrj bbc kexp somafm groove salad nostalgie cherie virgin europe classic vinyl yle nrk deutschlandfunk jazzradio ibiza global').split(' '));

function distance(a, b) {
  const n = a.length, m = b.length;
  const d = Array.from({ length: n + 1 }, (_, i) => [i, ...new Array(m).fill(0)]);
  for (let j = 0; j <= m; j++) d[0][j] = j;
  for (let i = 1; i <= n; i++) {
    for (let j = 1; j <= m; j++) {
      const cost = a[i - 1] === b[j - 1] ? 0 : 1;
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + cost);
      if (i > 1 && j > 1 && a[i - 1] === b[j - 2] && a[i - 2] === b[j - 1]) d[i][j] = Math.min(d[i][j], d[i - 2][j - 2] + 1);
    }
  }
  return d[n][m];
}
function fuzzy(map, word) {
  if (word.length < 5) return null;
  const allowed = word.length >= 8 ? 2 : 1;
  let best = null;
  for (const k in map) {
    if (k.length >= 5 && distance(word, k) <= allowed) {
      if (best && best !== map[k]) return null;
      best = map[k];
    }
  }
  return best;
}
/** "sverige p3" → name "p3" in Sweden; "franska nyheter" → French-language news. */
function parseQuery(raw) {
  let country = null, language = null;
  const rest = [];
  for (const word of fold(raw).split(' ')) {
    if (!word) continue;
    if (SYNONYMS[word]) { rest.push(SYNONYMS[word]); continue; }
    if (VOCABULARY.has(word) && !COUNTRIES[word] && !LANGUAGES[word]) { rest.push(word); continue; }
    if (!country && COUNTRIES[word]) { country = COUNTRIES[word]; continue; }
    if (!language && LANGUAGES[word]) { language = LANGUAGES[word]; continue; }
    if (!country) { const c = fuzzy(COUNTRIES, word); if (c) { country = c; continue; } }
    if (!language) { const l = fuzzy(LANGUAGES, word); if (l) { language = l; continue; } }
    rest.push(word);
  }
  let text = rest.join(' ');
  if (country && text === 'inter' && /france inter/i.test(raw)) { country = null; text = 'france inter'; }
  return { country, language, text };
}
function correct(text) {
  let changed = false;
  const words = text.split(' ').map((w) => {
    if (w.length < 4 || VOCABULARY.has(w) || /\d/.test(w)) return w;
    const allowed = w.length >= 7 ? 2 : 1;
    let best = null, bestD = allowed + 1;
    for (const v of VOCABULARY) {
      if (Math.abs(v.length - w.length) > allowed) continue;
      const d = distance(w, v);
      if (d < bestD || (d === bestD && best && best[0] !== w[0] && v[0] === w[0])) { bestD = d; best = v; }
    }
    if (best && bestD <= allowed) { changed = true; return best; }
    return w;
  });
  return changed ? words.join(' ') : text;
}
function score(name, text) {
  if (!text) return 0;
  const tokens = fold(name).split(/[^a-z0-9]+/);
  const flat = fold(name);
  let worst = 3;
  for (const w of text.split(' ')) {
    const s = tokens.includes(w) ? 3 : tokens.some((t) => t.startsWith(w)) ? 2 : flat.includes(w) ? 1 : 0;
    worst = Math.min(worst, s);
  }
  return worst;
}

const HOSTS = ['de1.api.radio-browser.info', 'nl1.api.radio-browser.info', 'at1.api.radio-browser.info', 'all.api.radio-browser.info'];
async function catalogue(path) {
  let last;
  for (const h of HOSTS) {
    try { return await getJson('https://' + h + path, 10000); } catch (e) { last = e; }
  }
  throw new Error('Radiokatalogen svarar inte. Kontrollera internet och försök igen.');
}
async function stationSearch(params) {
  const list = await catalogue('/json/stations/search?' + params + '&hidebroken=true&order=clickcount&reverse=true&limit=40');
  const out = [], seen = new Set();
  for (const o of list) {
    const url = (o.url_resolved || o.url || '').trim();
    const name = (o.name || '').trim().replace(/\s+/g, ' ');
    if (!name || !/^https?:\/\//.test(url)) continue;
    const key = name.toLowerCase() + '|' + url;
    if (seen.has(key)) continue;
    seen.add(key);
    out.push({ name, url, cc: (o.countrycode || '').trim(), lang: (o.language || '').trim(), br: o.bitrate | 0,
      tags: (o.tags || '').trim(), logo: /^https?:\/\//.test(o.favicon || '') ? o.favicon.trim() : '' });
  }
  return out;
}
let vocabularyLoaded = false;
async function loadVocabulary() {
  if (vocabularyLoaded) return;
  vocabularyLoaded = true;
  try {
    const tags = await catalogue('/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=1500');
    for (const t of tags) for (const p of fold(t.name).split(/[^a-z0-9]+/)) if (p.length >= 3 && p.length <= 20) VOCABULARY.add(p);
  } catch (e) { /* the built-in words still work */ }
}
const enc = encodeURIComponent;
async function find(text, filters) {
  const results = await stationSearch('name=' + enc(text) + filters);
  if (results.length < 15) {
    const urls = new Set(results.map((r) => r.url));
    for (const r of await stationSearch('tag=' + enc(text) + filters)) if (!urls.has(r.url)) { urls.add(r.url); results.push(r); }
  }
  return results.map((r, i) => [r, i]).sort((a, b) => (score(b[0].name, text) - score(a[0].name, text)) || a[1] - b[1]).map((x) => x[0]);
}
async function freeSearch(raw) {
  const q = parseQuery(raw);
  const filters = (q.country ? '&countrycode=' + q.country : '') + (q.language ? '&language=' + enc(q.language) : '');
  const scope = [q.country ? countryName(q.country) : '', q.language ? LANGUAGE_SV[q.language] || q.language : ''].filter(Boolean).join(', ');
  if (!q.text) {
    if (!filters) return { results: [], note: null };
    return { results: await stationSearch(filters.substring(1)), note: 'Populäraste: ' + scope };
  }
  let results = await find(q.text, filters);
  let note = scope ? 'Söker i ' + scope : null;
  if (results.length < 3) {
    await loadVocabulary();
    const fixed = correct(q.text);
    if (fixed !== q.text) {
      const better = await find(fixed, filters);
      if (better.length > results.length) { results = better; note = 'Visar träffar för ”' + fixed + '”' + (scope ? ' i ' + scope : ''); }
    }
  }
  if (!results.length && q.text.includes(' ')) {
    const longest = q.text.split(' ').reduce((a, b) => (b.length > a.length ? b : a), '');
    if (longest.length >= 3) {
      results = await find(longest, filters);
      if (results.length) note = 'Inget för hela frågan. Visar träffar för ”' + longest + '”';
    }
  }
  return { results, note };
}
async function mix(tags) {
  const lists = await Promise.all(tags.map((t) => stationSearch('tag=' + enc(t)).catch(() => [])));
  const out = [], urls = new Set();
  for (let i = 0; i < 8; i++) for (const l of lists) if (l[i] && !urls.has(l[i].url)) { urls.add(l[i].url); out.push(l[i]); }
  return { results: out, note: null };
}
function genreFromTags(tags) {
  const g = (tags || '').split(',').map((t) => t.trim()).filter((t) => t && t.length <= 25)[0] || '';
  return g ? g[0].toUpperCase() + g.substring(1) : '';
}
function resultDetails(r) {
  return [countryName(r.cc), r.lang ? (LANGUAGE_SV[r.lang.split(/[,;/]/)[0].trim().toLowerCase()] || r.lang.split(/[,;/]/)[0].trim()) : '',
    r.br > 0 ? r.br + ' kbps' : ''].filter(Boolean).join(' · ');
}
const toStation = (r) => ({ name: r.name, url: r.url, cc: r.cc, lang: r.lang, br: r.br, logo: r.logo, genre: genreFromTags(r.tags) });

// ============================================================ song text

const JUNK = new Set(['unknown', 'advert', 'advertisement', 'commercial', 'jingle', 'publicité', 'pub', 'promo', 'station id', 'id', 'live',
  'en direct', 'direct', 'radio', 'music', 'musique', 'untitled', 'n/a', 'null']);
function capitalise(t) {
  let upper = 0, letters = 0;
  for (const c of t) if (/\p{L}/u.test(c)) { letters++; if (c === c.toUpperCase() && c !== c.toLowerCase()) upper++; }
  if (letters < 4 || upper * 10 < letters * 8) return t;
  if (!t.trim().includes(' ') && letters <= 5) return t;
  let out = '', start = true;
  for (const c of t) { out += start ? c : c.toLowerCase(); start = !/[\p{L}\p{N}'’]/u.test(c); }
  return out;
}
function splitSong(song) {
  const i = song.indexOf(' – ');
  return i < 0 ? ['', song] : [song.substring(0, i).trim(), song.substring(i + 3).trim()];
}
/** Clean "Artist – Title", or null for empty text, the station's own name, addresses and fillers. */
function cleanSong(raw, stationName) {
  if (raw == null) return null;
  let t = String(raw).replace(/\u0000/g, ' ').replace(/\s+/g, ' ').trim();
  const now = /(?:spelar nu|now playing|en ce moment)\s*:?\s+(.+)$/i.exec(t);
  if (now) t = now[1].trim();
  t = t.replace(/^[-–—|:•\s]+|[-–—|:•\s]+$/g, '').trim();
  if (t.length < 3) return null;
  const low = t.toLowerCase();
  if (low.startsWith('http') || low.startsWith('www.') || /\.(com|fr|se|net|org)(\/.*)?$/.test(low)) return null;
  if (JUNK.has(low) || /^(advert|commercial|jingle|publicit)/i.test(low)) return null;
  t = t.replace(/\s+[-–—]\s+/g, ' – ');
  const song = t.includes(' – ');
  if (stationName) {
    const st = simplify(stationName), sh = simplify(shortName(stationName)), bare = simplify(stationName.replace(/\bradio\b/gi, ''));
    if (song) {
      const artist = simplify(splitSong(t)[0]);
      if (artist && (artist === sh || artist === st || artist === bare)) return null;
    } else {
      const tt = simplify(t);
      if (!tt || st.includes(tt)) return null;
      if (sh && tt.includes(sh) && tt.length <= sh.length + 12) return null;
    }
  }
  if (!song) return capitalise(t);
  const [a, b] = splitSong(t);
  return capitalise(a) + ' – ' + capitalise(b);
}
const EXTRAS = 'radio edit|radio version|single version|album version|edit|remaster(?:ed)?(?: \\d{4})?|\\d{4} remaster(?:ed)?'
  + '|explicit|clean|mono|stereo|original mix|extended mix|extended version|version courte';
const BRACKETED = new RegExp('\\s*[(\\[](?:' + EXTRAS + ')[^)\\]]*[)\\]]', 'gi');
const TRAILING = new RegExp('\\s+[-–]\\s+(?:' + EXTRAS + ').*$', 'i');
function shortTitle(title) {
  const t = title.replace(BRACKETED, '').replace(TRAILING, '').trim();
  return t || title;
}
const songArtist = (s) => splitSong(s.text)[0];
const songTitle = (s) => splitSong(s.text)[1];

// ============================================================ song watcher

const songs = {
  current: null,          // { text, cover, url, at }
  history: store.get('history', []),
  patterns: [],
  watchedUrl: null,
  nextFetch: 0,
  backfilledAt: 0,
  misses: 0,
  covers: new Map(),
};

async function loadPatterns() {
  try {
    const j = await getJson('meta-sources.json');
    songs.patterns = j.sources || [];
  } catch (e) { songs.patterns = []; }
}
function patternFor(url) {
  for (const p of songs.patterns) {
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
function at(obj, path) {
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
function fill(template, json) {
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
function addHistory(s) {
  const h = songs.history;
  for (const o of h) {
    if (o.url === s.url && o.text === s.text && Math.abs(o.at - s.at) < 20 * 60000) {
      if (!o.cover && s.cover) o.cover = s.cover;
      o.at = Math.min(o.at, s.at);
      return false;
    }
  }
  let i = 0;
  while (i < h.length && h[i].at > s.at) i++;
  h.splice(i, 0, s);
  if (h.length > 80) h.length = 80;
  store.set('history', h);
  return true;
}
function recentSongs(url) {
  const cur = songs.current;
  return songs.history.filter((s) => s.url === url && !(cur && s.text === cur.text)).slice(0, 15);
}

/** Radio France: the song playing now; earlier songs in the answer go into the history. */
function liveMeta(json, url, stationName) {
  const now = Date.now() / 1000;
  let current = null;
  for (const st of Object.values(json.steps || {})) {
    if (!st || st.embedType !== 'song' || st.start > now) continue;
    let artist = (typeof st.performers === 'string' && st.performers.trim()) || (typeof st.authors === 'string' && st.authors.trim()) || '';
    const title = typeof st.title === 'string' ? st.title.trim() : '';
    if (!title) continue;
    const text = artist ? artist + ' – ' + title : title;
    const cover = typeof st.visual === 'string' && st.visual.startsWith('http') ? st.visual : null;
    if (st.end + 5 >= now) current = { text, cover };
    else {
      const c = cleanSong(text, stationName);
      if (c) addHistory({ text: c, cover, url, at: st.start * 1000 });
    }
  }
  return current || { text: null };
}
async function fromPattern(url, stationName) {
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
async function fromIcecast(url) {
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
/** A channel's own list of the latest songs (Sveriges Radio): fills the history. */
async function backfill(url, stationName) {
  const hit = patternFor(url);
  if (!hit || !hit.p.history) return;
  try {
    const h = hit.p.history;
    const json = await getJson(h.url.replace('$1', hit.key));
    let added = false;
    for (const o of json[h.list] || []) {
      const text = cleanSong(fill(h.song, o), stationName);
      const m = /(\d{9,13})/.exec(at(o, h.time) || '');
      if (!text || !m) continue;
      let t = +m[1];
      if (t < 1e11) t *= 1000;
      if (t < Date.now()) added = addHistory({ text, cover: null, url, at: t }) || added;
    }
    if (added) renderSongs();
  } catch (e) { /* no history this time */ }
}
async function coverFor(text, given) {
  if (given && /^https?:/.test(given)) return given;
  if (!text.includes(' – ')) return null;
  if (songs.covers.has(text)) return songs.covers.get(text);
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
  songs.covers.set(text, found);
  return found;
}
function publishSong(s) {
  const old = songs.current;
  if (!s && !old) return;
  if (s && old && s.text === old.text && s.cover === old.cover && s.url === old.url) return;
  songs.current = s;
  if (s) addHistory({ ...s });
  renderSongs();
}
async function songTick() {
  const s = nowPlaying;
  const active = musicActive();
  if (!s || !active) {
    if (songs.watchedUrl) { songs.watchedUrl = null; publishSong(null); }
    return;
  }
  const url = s.url;
  if (url !== songs.watchedUrl) {
    songs.watchedUrl = url; songs.nextFetch = 0; songs.misses = 0; songs.backfilledAt = 0;
    publishSong(null);
  }
  const now = Date.now();
  const visible = document.visibilityState === 'visible';
  const hit = patternFor(url);
  if (hit && now - songs.backfilledAt > 5 * 60000) { songs.backfilledAt = now; backfill(url, s.name); }
  if (now < songs.nextFetch) return;
  songs.nextFetch = now + (visible ? 25000 : 150000);
  let found = null;
  try { found = hit ? await fromPattern(url, s.name) : await fromIcecast(url); } catch (e) { found = undefined; }
  if (songs.watchedUrl !== url) return;
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
  if (document.visibilityState === 'visible') { songs.backfilledAt = 0; pokeSongs(); renderAll(); }
});

// ============================================================ playback

const audio = $('audio');
let nowPlaying = (() => {
  const last = store.get('lastPlayed', null);
  return stations.find((s) => s.url === last) || null;
})();
let localState = 'stopped';   // stopped | buffering | playing
let playError = null;
let volume = store.get('volume', 100);

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
    localState = 'buffering';
    audio.src = s.url.startsWith('http://') && location.protocol === 'https:' ? s.url.replace('http://', 'https://') : s.url;
    audio.volume = volume / 100;
    audio.play().catch((e) => {
      if (e && e.name === 'NotAllowedError') { localState = 'stopped'; renderAll(); return; }
      failLocal();
    });
  }
  if (!saved) snack('Provlyssnar ' + shortName(s.name) + '. Den är inte sparad.', 'Lägg till', () => addStation(s, false));
  expandCountry(s.cc);
  pokeSongs();
  renderAll();
}
function stopLocal() {
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
audio.addEventListener('playing', () => { localState = 'playing'; renderAll(); });
audio.addEventListener('waiting', () => { if (audio.src) { localState = 'buffering'; renderAll(); } });
audio.addEventListener('error', () => { if (audio.getAttribute('src')) failLocal(); });
audio.addEventListener('pause', () => { if (audio.src && localState !== 'stopped') { localState = 'stopped'; renderAll(); } });

function musicActive() {
  return caster.connected ? ['PLAYING', 'BUFFERING', 'PAUSED'].includes(caster.state) : localState !== 'stopped';
}
function stopMusic() {
  if (caster.connected) castStop(); else stopLocal();
  renderAll();
}
function togglePlay() {
  if (musicActive()) { stopMusic(); return; }
  if (!nowPlaying) { snack('Tryck på en kanal i listan för att spela den.'); return; }
  playStation(nowPlaying, stations.some((x) => x.url === nowPlaying.url));
}
function setVolume(v) {
  volume = v;
  store.set('volume', v);
  if (caster.connected) castVolume(v / 100); else audio.volume = v / 100;
}

// ============================================================ Chromecast (Chrome on Android and computers)

const caster = { available: false, connected: false, device: '', state: 'IDLE', ctx: null, player: null, controller: null, mediaUrl: null };

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
      renderChip();
    });
    ctx.addEventListener(F.CastContextEventType.SESSION_STATE_CHANGED, (e) => onCastSession(e.sessionState));
    controller.addEventListener(F.RemotePlayerEventType.ANY_CHANGE, onRemoteChange);
    caster.available = ctx.getCastState() !== F.CastState.NO_DEVICES_AVAILABLE;
    renderChip();
  } catch (e) { /* casting stays unavailable */ }
};

function onCastSession(state) {
  const S = window.cast.framework.SessionState;
  const session = caster.ctx.getCurrentSession();
  if (state === S.SESSION_STARTED || state === S.SESSION_RESUMED) {
    caster.connected = true;
    caster.device = session && session.getCastDevice() ? session.getCastDevice().friendlyName : 'högtalaren';
    const wasPlaying = localState !== 'stopped';
    if (state === S.SESSION_STARTED && wasPlaying && nowPlaying) {
      stopLocal();
      castLoad(nowPlaying); // what played in the phone moves to the speaker
    }
    syncRemote();
  } else if (state === S.SESSION_ENDED || state === S.SESSION_START_FAILED) {
    if (state === S.SESSION_START_FAILED) snack('Kunde inte ansluta till högtalaren.');
    caster.connected = false;
    caster.device = '';
    caster.state = 'IDLE';
  }
  renderAll();
}
function syncRemote() {
  const p = caster.player;
  if (!p) return;
  caster.state = p.isMediaLoaded ? (p.playerState || 'IDLE') : 'IDLE';
  const id = p.mediaInfo && p.mediaInfo.contentId;
  if (id && (!nowPlaying || nowPlaying.url !== id)) {
    // Started earlier (or from another phone): show it.
    nowPlaying = stations.find((s) => s.url === id) || { name: (p.mediaInfo.metadata && p.mediaInfo.metadata.title) || 'Radio', url: id, cc: '', logo: '' };
    pokeSongs();
  }
  if (caster.connected && !dragging.volume) $('volume').value = Math.round((p.volumeLevel || 0) * 100);
}
function onRemoteChange() {
  const before = caster.state;
  syncRemote();
  if (caster.state !== before) { pokeSongs(); renderAll(); }
}
function castLoad(s) {
  const session = caster.ctx && caster.ctx.getCurrentSession();
  if (!session) return;
  const info = new chrome.cast.media.MediaInfo(s.url, contentType(s.url));
  info.streamType = chrome.cast.media.StreamType.LIVE;
  info.metadata = new chrome.cast.media.MusicTrackMediaMetadata();
  info.metadata.title = shortName(s.name);
  if (s.logo) info.metadata.images = [new chrome.cast.Image(s.logo)];
  const req = new chrome.cast.media.LoadRequest(info);
  req.autoplay = true;
  caster.state = 'BUFFERING';
  session.loadMedia(req).then(() => syncRemote(), () => {
    caster.state = 'IDLE';
    playError = 'Högtalaren kunde inte spela strömmen. Kontrollera länken.';
    renderAll();
  });
}
function castStop() {
  if (caster.controller && caster.player.isMediaLoaded) caster.controller.stop();
  caster.state = 'IDLE';
}
function castVolume(level) {
  if (!caster.player) return;
  caster.player.volumeLevel = level;
  caster.controller.setVolumeLevel();
}
function chooseOutput() {
  if (caster.ctx && caster.available) {
    // Chrome's own list of speakers and groups; while casting it also has "Sluta casta".
    caster.ctx.requestSession().catch(() => { /* closed without choosing */ });
    return;
  }
  const body = sheet('Var ska ljudet spelas?');
  const p = (t) => body.appendChild(el('p', 'muted', t));
  if (caster.ctx) {
    p('Hittar ingen Chromecast just nu. Är telefonen på samma wifi som högtalarna?');
  } else if (/iPhone|iPad|iPod/.test(navigator.userAgent)) {
    p('På iPhone spelar webbappen i telefonen. Chromecast går inte från Safari, men AirPlay fungerar: öppna Kontrollcenter, tryck på ljudrutan och välj högtalare.');
  } else {
    p('Casta till Chromecast går i Chrome (på Android eller datorn). I den här webbläsaren spelar radion i enheten.');
  }
}

// ============================================================ rendering

let grouped = store.get('grouped', true);
let collapsed = new Set(store.get('collapsed', []));
let editing = false;
const dragging = { volume: false };

function renderAll() {
  renderList();
  renderChip();
  renderNowPlaying();
  renderSession();
}
function renderSongs() { renderNowPlaying(); renderSession(); if (sheetState.kind === 'song') renderSongSheet(); }

function renderChip() {
  const chip = $('castChip');
  chip.classList.toggle('connected', caster.connected);
  const name = caster.connected ? caster.device : 'Telefonen';
  $('chipText').textContent = name;
  $('chipIcon').querySelector('use').setAttribute('href', caster.connected ? '#i-speaker' : (caster.available ? '#i-caster' : '#i-phone'));
  chip.setAttribute('aria-label', caster.connected ? 'Ljudet går till ' + name + '. Tryck för att byta eller sluta casta.'
    : 'Ljudet går till telefonen. Tryck för att välja en högtalare.');
}

function renderList() {
  const box = $('stations');
  box.textContent = '';
  $('empty').hidden = stations.length > 0;
  $('orderCountry').setAttribute('aria-selected', String(grouped));
  $('orderOwn').setAttribute('aria-selected', String(!grouped));
  $('editBtn').textContent = editing ? 'Klar' : 'Redigera';
  $('editBtn').hidden = stations.length === 0;
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
  const coll = new Intl.Collator('sv');
  const keys = [...byCountry.keys()].sort((a, b) => (a ? 0 : 1) - (b ? 0 : 1) || coll.compare(countryName(a), countryName(b)));
  for (const cc of keys) {
    const list = byCountry.get(cc).sort((a, b) => coll.compare(shortName(a[0].name), shortName(b[0].name)));
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
  if (caster.connected) {
    const st = caster.state;
    if (st === 'PLAYING') return 'Spelar på ' + caster.device;
    if (st === 'BUFFERING') return 'Startar på ' + caster.device + '…';
    if (st === 'PAUSED') return 'Pausad på ' + caster.device;
    return 'Redo att spela på ' + caster.device;
  }
  if (localState === 'playing') return 'Spelar' + (/iPhone|iPad/.test(navigator.userAgent) ? '' : ' i telefonen');
  if (localState === 'buffering') return 'Startar…';
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
      artist: song ? (songArtist(song) || shortName(s.name)) : statusText(),
      album: shortName(s.name),
      artwork: [{ src: art, sizes: '300x300' }],
    });
    navigator.mediaSession.playbackState = musicActive() ? 'playing' : 'paused';
  } catch (e) { /* not supported */ }
}
if ('mediaSession' in navigator) {
  const h = (a, f) => { try { navigator.mediaSession.setActionHandler(a, f); } catch (e) { /* unsupported action */ } };
  h('play', () => { if (!musicActive()) togglePlay(); });
  h('pause', () => { if (musicActive()) stopMusic(); });
  h('stop', () => { if (musicActive()) stopMusic(); });
}

// ============================================================ list actions

function stationTapped(s) {
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

const sheetState = { kind: null };
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
  setTimeout(() => { sh.hidden = true; sh.style.transform = ''; $('scrim').hidden = true; sheetState.kind = null; }, 180);
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
  item('Dela min kanallista', 'plus', shareList);
  item('Lägg till med egen länk', 'plus', askForStation);
  item('Om RadioCast', 'note', () => {
    const b = sheet('Om RadioCast');
    b.append(el('p', null, VERSION + '. Webbradio i telefonen, med låtinformation.'));
    b.append(el('p', 'muted', 'I Chrome (Android och dator) kan du casta till Chromecast. På iPhone: lägg till på hemskärmen via Dela-knappen i Safari, och använd AirPlay för högtalare.'));
    b.append(el('p', 'muted', 'Kanaler och logotyper från radio-browser.info. Låtinformation från kanalernas egna listor.'));
  });
}
function shareList() {
  const data = btoa(unescape(encodeURIComponent(JSON.stringify(stations.map((s) => [s.name, s.url, s.cc, s.br, s.logo, s.genre])))));
  const link = location.origin + location.pathname + '#lista=' + data;
  const done = () => snack('Länken med din lista är kopierad. Öppna den på en annan enhet.');
  if (navigator.share) navigator.share({ title: 'Min RadioCast-lista', url: link }).catch(() => {});
  else if (navigator.clipboard) navigator.clipboard.writeText(link).then(done, () => prompt('Kopiera länken:', link));
  else prompt('Kopiera länken:', link);
}
function importFromLink() {
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

$('castChip').onclick = chooseOutput;
$('menuBtn').onclick = showMenu;
$('searchBar').onclick = openSearch;
$('emptySearch').onclick = openSearch;
$('orderCountry').onclick = () => { grouped = true; store.set('grouped', true); renderList(); };
$('orderOwn').onclick = () => { grouped = false; store.set('grouped', false); renderList(); };
$('editBtn').onclick = () => { editing = !editing; renderList(); };
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
  window.addEventListener('load', () => navigator.serviceWorker.register('sw.js').catch(() => {}));
}

loadPatterns().then(() => pokeSongs());
importFromLink();
renderAll();

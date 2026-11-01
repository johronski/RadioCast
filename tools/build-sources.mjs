// Bygger Sveriges Radios karta i meta-sources.json: kortkod i strömlänken (live1.sr.se/p4sth-aac-320) -> kanal-id (701).
// Hämtar SR:s kanallista och följer varje kanals strömlänkar (mp3-omdirigering och AAC-spellista) till länken med kortkod.
// Skriver alla tre kopiorna. Kör: node tools/build-sources.mjs   (behöver nät; körs varje vecka av sr-sources.yml)
import { readFileSync, writeFileSync } from 'node:fs';

const root = new URL('..', import.meta.url);
const FILES = ['meta-sources.json', 'docs/meta-sources.json', 'app/src/main/assets/meta-sources.json'];
const ID = 'sverigesradio-kanal';
// Kortkoden: första delen av sökvägen på *.sr.se, t.ex. live1.sr.se/p4sth-aac-320 eller ljud1-cdn.sr.se/lc/p4malm.m3u8.
const MATCH = 'sr\\.se/(?:lc/(?:[a-z0-9]+/)?)?([a-z0-9]+)[-_.]';

/** Kortkoden i en SR-länk, eller null. */
export function codeOf(url) {
  const m = new RegExp(MATCH).exec(url || '');
  return m ? m[1] : null;
}

async function get(url, opts = {}) {
  const ac = new AbortController(); const t = setTimeout(() => ac.abort(), 10000);
  try { return await fetch(url, { ...opts, signal: ac.signal, headers: { 'User-Agent': 'RadioCast-sources' } }); }
  finally { clearTimeout(t); }
}

/** Följer omdirigeringar och returnerar alla länkar på vägen (läser aldrig själva ljudet). */
async function chain(url) {
  const seen = [url];
  for (let i = 0; i < 6; i++) {
    try {
      const r = await get(url, { redirect: 'manual' });
      const loc = r.headers.get('location');
      r.body?.cancel().catch(() => {});
      if (!loc || r.status < 300 || r.status >= 400) break;
      url = new URL(loc, url).href; seen.push(url);
    } catch (e) { break; }
  }
  return seen;
}

/** Kortkoder för en kanal: via mp3-länken och AAC-spellistan (.pls). */
async function codesFor(c) {
  const urls = [];
  if (c.liveaudio && c.liveaudio.url) urls.push(...await chain(c.liveaudio.url));
  try {
    const pls = await (await get(`https://sverigesradio.se/topsy/direkt/${c.id}-hi-aac.pls`)).text();
    for (const m of pls.matchAll(/^File\d+=(\S+)/gm)) urls.push(...await chain(m[1]));
  } catch (e) { /* bara mp3-vägen */ }
  return [...new Set(urls.map(codeOf).filter(Boolean))];
}

/** Kod -> id. En kod som flera kanaler leder till går till den enda av dem som inte har någon annan kod. */
export function decide(found) {
  const claims = {};
  for (const [id, codes] of found) for (const k of codes) (claims[k] = claims[k] || []).push(id);
  const map = {};
  for (const [k, ids] of Object.entries(claims)) {
    if (ids.length === 1) { map[k] = ids[0]; continue; }
    const only = ids.filter((id) => found.get(id).length === 1);
    if (only.length === 1) map[k] = only[0];
    else console.warn(`krock utan lösning: ${k} = ${ids.join(', ')}; hoppar över`);
  }
  return map;
}

/** Ersätter match och map för SR-posten i filens text; resten av filen lämnas orörd. */
export function rewrite(text, map) {
  const start = text.indexOf(`"id": "${ID}"`);
  if (start < 0) throw new Error('hittar inte ' + ID);
  const end = text.indexOf('"url":', start);
  const body = Object.keys(map).sort().map((k) => `        ${JSON.stringify(k)}: ${map[k]}`).join(',\n');
  const block = `"id": "${ID}",\n      "match": ${JSON.stringify(MATCH)},\n      "map": {\n${body}\n      },\n      `;
  return text.slice(0, start) + block + text.slice(end);
}

async function main() {
  const channels = (await (await get('https://api.sr.se/api/v2/channels?format=json&pagination=false')).json()).channels || [];
  const found = new Map();
  await Promise.all(channels.map(async (c) => found.set(c.id, await codesFor(c))));
  const map = decide(found);
  const name = Object.fromEntries(channels.map((c) => [c.id, c.name]));
  // Behåll tidigare koder för kanaler som finns kvar men inte gav någon kod nu (tillfälligt fel).
  const old = JSON.parse(readFileSync(new URL(FILES[0], root), 'utf8')).sources.find((s) => s.id === ID).map || {};
  const taken = new Set(Object.values(map));
  for (const [k, id] of Object.entries(old)) {
    if (!(k in map) && name[id] && !taken.has(id)) { map[k] = id; console.log(`behåller ${k} -> ${id}`); }
  }
  for (const k of Object.keys(map).sort()) console.log(`${k} -> ${map[k]} ${name[map[k]] || ''}`);
  const missing = channels.filter((c) => !Object.values(map).includes(c.id)).map((c) => c.name);
  if (missing.length) console.log('ingen kortkod:', missing.join(', '));
  // Skydd: ser kartan fel ut har SR ändrat något – skriv inget.
  if (Object.keys(map).length < 20 || map.p1 !== 132 || map.p3 !== 164) {
    console.error('Kartan ser fel ut (' + Object.keys(map).length + ' koder). Ingen fil ändrad.');
    process.exit(1);
  }
  for (const f of FILES) {
    const u = new URL(f, root);
    writeFileSync(u, rewrite(readFileSync(u, 'utf8'), map));
  }
  console.log(Object.keys(map).length + ' koder skrivna till ' + FILES.length + ' filer.');
}

if (import.meta.url === `file://${process.argv[1]}`) await main();

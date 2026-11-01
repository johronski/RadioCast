// Bygger Sveriges Radios karta i meta-sources.json: kortkod i strömlänken (live1.sr.se/p4sth-aac-320) -> kanal-id (701).
// Hämtar SR:s kanallista och följer varje kanals strömlänk (…/srapi/<id>.mp3) till länken med kortkod.
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

/** Följer omdirigeringar och returnerar alla länkar på vägen. */
async function chain(url) {
  const seen = [url];
  for (let i = 0; i < 6; i++) {
    const ac = new AbortController(); const t = setTimeout(() => ac.abort(), 10000);
    try {
      const r = await fetch(url, { redirect: 'manual', signal: ac.signal, headers: { 'User-Agent': 'RadioCast-sources' } });
      const loc = r.headers.get('location');
      ac.abort();
      if (!loc || r.status < 300 || r.status >= 400) break;
      url = new URL(loc, url).href; seen.push(url);
    } catch (e) { break; } finally { clearTimeout(t); }
  }
  return seen;
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
  const res = await fetch('https://api.sr.se/api/v2/channels?format=json&pagination=false');
  const channels = (await res.json()).channels || [];
  const map = {};
  const missing = [];
  await Promise.all(channels.map(async (c) => {
    const live = c.liveaudio && c.liveaudio.url;
    const code = live && (await chain(live)).map(codeOf).find(Boolean);
    if (!code) { missing.push(c.name); return; }
    if (map[code] && map[code] !== c.id) console.warn(`krock: ${code} = ${map[code]} och ${c.id} (${c.name}); behåller lägsta`);
    map[code] = Math.min(map[code] || Infinity, c.id);
    console.log(`${code} -> ${c.id} ${c.name}`);
  }));
  if (missing.length) console.log('ingen kortkod:', missing.join(', '));
  // Skydd: blir kartan misstänkt liten har SR ändrat något – skriv inget.
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

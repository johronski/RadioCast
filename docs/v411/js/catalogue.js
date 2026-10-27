/* The radio-browser.info catalogue: searching for stations. */
import { getJson, fold, enc } from './util.js';
import { countryName } from './names.js';
import { LANGUAGE_SV, VOCABULARY, parseQuery, correct, score } from './query.js';

export const HOSTS = ['de1.api.radio-browser.info', 'nl1.api.radio-browser.info', 'at1.api.radio-browser.info', 'all.api.radio-browser.info'];
export async function catalogue(path) {
  for (const h of HOSTS) {
    try { return await getJson('https://' + h + path, 10000); } catch (e) { /* next server */ }
  }
  throw new Error('Radiokatalogen svarar inte. Kontrollera internet och försök igen.');
}
export async function stationSearch(params) {
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
export let vocabularyLoaded = false;
export async function loadVocabulary() {
  if (vocabularyLoaded) return;
  vocabularyLoaded = true;
  try {
    const tags = await catalogue('/json/tags?order=stationcount&reverse=true&hidebroken=true&limit=1500');
    for (const t of tags) for (const p of fold(t.name).split(/[^a-z0-9]+/)) if (p.length >= 3 && p.length <= 20) VOCABULARY.add(p);
  } catch (e) { /* the built-in words still work */ }
}
export async function find(text, filters) {
  const results = await stationSearch('name=' + enc(text) + filters);
  if (results.length < 15) {
    const urls = new Set(results.map((r) => r.url));
    for (const r of await stationSearch('tag=' + enc(text) + filters)) if (!urls.has(r.url)) { urls.add(r.url); results.push(r); }
  }
  return results.map((r, i) => [r, i]).sort((a, b) => (score(b[0].name, text) - score(a[0].name, text)) || a[1] - b[1]).map((x) => x[0]);
}
export async function freeSearch(raw) {
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
export async function mix(tags) {
  const lists = await Promise.all(tags.map((t) => stationSearch('tag=' + enc(t)).catch(() => [])));
  const out = [], urls = new Set();
  for (let i = 0; i < 8; i++) for (const l of lists) if (l[i] && !urls.has(l[i].url)) { urls.add(l[i].url); out.push(l[i]); }
  return { results: out, note: null };
}
export function genreFromTags(tags) {
  const g = (tags || '').split(',').map((t) => t.trim()).filter((t) => t && t.length <= 25)[0] || '';
  return g ? g[0].toUpperCase() + g.substring(1) : '';
}
export function resultDetails(r) {
  return [countryName(r.cc), r.lang ? (LANGUAGE_SV[r.lang.split(/[,;/]/)[0].trim().toLowerCase()] || r.lang.split(/[,;/]/)[0].trim()) : '',
    r.br > 0 ? r.br + ' kbps' : ''].filter(Boolean).join(' · ');
}
export const toStation = (r) => ({ name: r.name, url: r.url, cc: r.cc, lang: r.lang, br: r.br, logo: r.logo, genre: genreFromTags(r.tags) });

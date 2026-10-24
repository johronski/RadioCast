/* Understands the search box: countries, languages, Swedish genre words, typos. Pure, so it is tested in CI. */
import { fold } from './util.js';

export const COUNTRIES = {};
[['SE', 'sverige', 'sweden', 'svensk', 'svenskt'], ['NO', 'norge', 'norway', 'norsk'], ['DK', 'danmark', 'denmark', 'dansk'],
  ['FI', 'finland', 'finsk'], ['FR', 'frankrike', 'france', 'fransk', 'franskt'], ['DE', 'tyskland', 'germany', 'deutschland', 'tysk'],
  ['GB', 'england', 'storbritannien', 'britain', 'uk', 'brittisk'], ['US', 'usa', 'amerika', 'amerikansk'],
  ['ES', 'spanien', 'spain', 'spansk'], ['IT', 'italien', 'italy', 'italiensk'], ['NL', 'nederlanderna', 'holland', 'netherlands'],
  ['BE', 'belgien', 'belgium'], ['CH', 'schweiz', 'switzerland'], ['AT', 'osterrike', 'austria'], ['CA', 'kanada', 'canada'],
  ['PL', 'polen', 'poland'], ['EE', 'estland', 'estonia'], ['IE', 'irland', 'ireland'], ['PT', 'portugal'], ['GR', 'grekland', 'greece'],
  ['JP', 'japan'], ['BR', 'brasilien', 'brazil'], ['AU', 'australien', 'australia']]
  .forEach(([cc, ...words]) => words.forEach((w) => { COUNTRIES[w] = cc; }));
export const LANGUAGES = {};
export const LANGUAGE_SV = {};
[['swedish', 'svenska'], ['english', 'engelska'], ['french', 'franska'], ['german', 'tyska'], ['spanish', 'spanska'],
  ['norwegian', 'norska'], ['danish', 'danska'], ['finnish', 'finska'], ['italian', 'italienska'], ['dutch', 'nederlandska'],
  ['arabic', 'arabiska'], ['portuguese', 'portugisiska'], ['polish', 'polska'], ['russian', 'ryska'], ['greek', 'grekiska'],
  ['turkish', 'turkiska'], ['persian', 'persiska'], ['japanese', 'japanska']]
  .forEach(([en, sv]) => { LANGUAGES[en] = en; LANGUAGES[sv] = en; LANGUAGE_SV[en] = sv; });
export const SYNONYMS = { elektroniskt: 'electronic', elektronisk: 'electronic', elektro: 'electro', klassiskt: 'classical', klassisk: 'classical',
  nyheter: 'news', prat: 'talk', julmusik: 'christmas', jul: 'christmas', hiphop: 'hip hop', dansmusik: 'dance', barn: 'kids',
  lugnt: 'chillout', lugn: 'chillout', sport: 'sports' };
export const VOCABULARY = new Set(('electronic electro techno house deep trance dubstep drum bass ambient chillout chill lounge downtempo jazz blues rock pop hip hop rap '
  + 'classical country reggae metal punk soul funk disco synthpop synthwave indie alternative oldies news talk sports schlager dansband latin afro '
  + 'afrobeats minimal melodic progressive dance hits christmas gospel folk world phonk lofi garage breakbeat jungle hardstyle psytrance experimental '
  + 'acoustic instrumental soundtrack kids comedy culture public radio nova mouv fip france inter sveriges megapol bandit rix star lugna favoriter '
  + 'skyrock fun nrj bbc kexp somafm groove salad nostalgie cherie virgin europe classic vinyl yle nrk deutschlandfunk jazzradio ibiza global').split(' '));

export function distance(a, b) {
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
export function fuzzy(map, word) {
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
export function parseQuery(raw) {
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
export function correct(text) {
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
export function score(name, text) {
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

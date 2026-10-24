/* Turning what radio stations say about the current song into clean text. Pure, so it is tested in CI. */
import { simplify } from './util.js';
import { shortName } from './names.js';

export const JUNK = new Set(['unknown', 'advert', 'advertisement', 'commercial', 'jingle', 'publicité', 'pub', 'promo', 'station id', 'id', 'live',
  'en direct', 'direct', 'radio', 'music', 'musique', 'untitled', 'n/a', 'null']);
export function capitalise(t) {
  let upper = 0, letters = 0;
  for (const c of t) if (/\p{L}/u.test(c)) { letters++; if (c === c.toUpperCase() && c !== c.toLowerCase()) upper++; }
  if (letters < 4 || upper * 10 < letters * 8) return t;
  if (!t.trim().includes(' ') && letters <= 5) return t;
  let out = '', start = true;
  for (const c of t) { out += start ? c : c.toLowerCase(); start = !/[\p{L}\p{N}'’]/u.test(c); }
  return out;
}
export function splitSong(song) {
  const i = song.indexOf(' – ');
  return i < 0 ? ['', song] : [song.substring(0, i).trim(), song.substring(i + 3).trim()];
}
/** Clean "Artist – Title", or null for empty text, the station's own name, addresses and fillers. */
export function cleanSong(raw, stationName) {
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
export const EXTRAS = 'radio edit|radio version|single version|album version|edit|remaster(?:ed)?(?: \\d{4})?|\\d{4} remaster(?:ed)?'
  + '|explicit|clean|mono|stereo|original mix|extended mix|extended version|version courte';
export const BRACKETED = new RegExp('\\s*[(\\[](?:' + EXTRAS + ')[^)\\]]*[)\\]]', 'gi');
export const TRAILING = new RegExp('\\s+[-–]\\s+(?:' + EXTRAS + ').*$', 'i');
export function shortTitle(title) {
  const t = title.replace(BRACKETED, '').replace(TRAILING, '').trim();
  return t || title;
}
export const songArtist = (s) => splitSong(s.text)[0];
export const songTitle = (s) => splitSong(s.text)[1];

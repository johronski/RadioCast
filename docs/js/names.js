/* Names and logos: country names, short station names, initials tiles. */
import { simplify } from './util.js';

export const COUNTRY_NAMES = (() => {
  try { return new Intl.DisplayNames(['sv'], { type: 'region' }); } catch (e) { return null; }
})();
export function countryName(cc) {
  if (!cc || cc.length !== 2) return '';
  try { return (COUNTRY_NAMES && COUNTRY_NAMES.of(cc.toUpperCase())) || cc.toUpperCase(); } catch (e) { return cc.toUpperCase(); }
}

/** "Radio Nova 101.5 FM | Paris | Musique" becomes "Radio Nova 101.5 FM". */
export function shortName(name) {
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
export function initials(name) {
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
export function palette(name) {
  let h = 0;
  for (const c of (name || '').toLowerCase()) h = (Math.imul(h, 31) + c.codePointAt(0)) | 0;
  return ((h % 5) + 5) % 5;
}

/** Logo tile: the station's logo (or a song cover) on a pastel tile with initials as fallback. */
export function logoTile(target, imageUrl, name, cover) {
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

export function subtitle(s) {
  return [s.genre, s.br > 0 ? s.br + ' kbps' : ''].filter(Boolean).join(' · ');
}

export function guessCountry(url) {
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

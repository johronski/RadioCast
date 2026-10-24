/* Small helpers shared by the web app. */

export const $ = (id) => document.getElementById(id);
export const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text != null) e.textContent = text;
  return e;
};
export const icon = (name, cls) => {
  const s = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
  s.setAttribute('class', 'ico' + (cls ? ' ' + cls : ''));
  s.setAttribute('viewBox', '0 0 24 24');
  const u = document.createElementNS('http://www.w3.org/2000/svg', 'use');
  u.setAttribute('href', '#i-' + name);
  s.appendChild(u);
  return s;
};
export const store = {
  get(key, fallback) {
    try { const v = localStorage.getItem('rc.' + key); return v == null ? fallback : JSON.parse(v); } catch (e) { return fallback; }
  },
  set(key, value) { try { localStorage.setItem('rc.' + key, JSON.stringify(value)); } catch (e) { /* private mode */ } },
};
export const fold = (s) => (s || '').toLowerCase().normalize('NFD').replace(/\p{M}+/gu, '').replace(/\s+/g, ' ').trim();
export const simplify = (s) => (s || '').toLowerCase().replace(/[^\p{L}\p{N}]/gu, '');

export async function getJson(url, timeoutMs = 8000) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);
  try {
    const r = await fetch(url, { signal: ctrl.signal, cache: 'no-store' });
    if (!r.ok) throw new Error('HTTP ' + r.status);
    return await r.json();
  } finally { clearTimeout(t); }
}
export const enc = encodeURIComponent;

/* Keeps the app itself available offline; radio streams and song lookups always go to the network. */
const CACHE = 'radiocast-web-20';
const SHELL = ['./', 'index.html', 'style.css?v=2.9', 'js/app.js?v=2.9', 'js/util.js', 'js/names.js', 'js/query.js',
  'js/catalogue.js', 'js/songtext.js', 'js/sources.js', 'js/history.js', 'manifest.webmanifest', 'meta-sources.json',
  'icons/icon-192.png', 'icons/icon-512.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});
self.addEventListener('activate', (e) => {
  e.waitUntil(caches.keys().then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
    .then(() => self.clients.claim()));
});
self.addEventListener('fetch', (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return; // streams, APIs: untouched
  // Network first, so a new version shows up at once; the saved copy when offline.
  e.respondWith(fetch(e.request, { cache: 'no-cache' }).then((r) => {
    if (r.ok) { const copy = r.clone(); caches.open(CACHE).then((c) => c.put(e.request, copy)); }
    return r;
  }).catch(() => caches.match(e.request).then((r) => r || caches.match('index.html'))));
});

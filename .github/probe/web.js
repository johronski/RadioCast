const urls = process.argv.slice(2).length ? process.argv.slice(2) : [
  'https://sverigesradio.se/topsy/direkt/srapi/132.mp3',
  'https://sverigesradio.se/topsy/direkt/srapi/163.mp3',
  'https://sverigesradio.se/topsy/direkt/srapi/164.mp3',
  'https://sverigesradio.se/topsy/direkt/srapi/701.mp3',
  'https://live1.sr.se/p1-aac-192', 'https://live1.sr.se/p2-aac-192', 'https://live1.sr.se/p3-aac-192', 'https://live1.sr.se/p4sthlm-aac-192',
  'https://live1.sr.se/p1-aac-320', 'https://live1.sr.se/p2-aac-320',
];
for (const u of urls) {
  try {
    const ac = new AbortController(); setTimeout(() => ac.abort(), 8000);
    const r = await fetch(u, { signal: ac.signal });
    const reader = r.body.getReader(); const { value } = await reader.read(); ac.abort();
    console.log(r.status, r.headers.get('content-type'), (value || []).length, r.url, '<=', u);
  } catch (e) { console.log('ERR', e.message, u); }
}
const r = await fetch('https://api.sr.se/api/v2/channels?format=json&pagination=false');
const j = await r.json();
for (const c of j.channels.filter((c) => /^P[1-4]( Stockholm)?$/.test(c.name) || c.id === 701)) console.log(c.id, c.name, c.liveaudio && c.liveaudio.url, c.image);

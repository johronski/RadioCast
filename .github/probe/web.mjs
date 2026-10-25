const urls = [
  'https://stream.radiofrance.fr/fip/fip.m3u8?id=radiofrance',
  'https://stream.radiofrance.fr/mouv/mouv.m3u8?id=radiofrance',
  'https://stream.radiofrance.fr/fip/fip_hifi.m3u8?id=radiofrance',
  'https://sverigesradio.se/topsy/direkt/164-hi.m3u8',
  'https://sverigesradio.se/topsy/direkt/srapi/164.m3u8',
  'https://live1.sr.se/p3-aac-320/playlist.m3u8',
  'https://ljud1-cdn.sr.se/hls/p3/master.m3u8',
];
const head = async (u) => {
  const ac = new AbortController(); const tm = setTimeout(() => ac.abort(), 6000);
  const r = await fetch(u, { headers: { Origin: 'https://johronski.github.io' }, redirect: 'follow', signal: ac.signal });
  if (!/mpegurl|m3u|text|octet/i.test(r.headers.get('content-type') || '')) { ac.abort(); clearTimeout(tm); return { r, t: '(inte en spellista: ' + r.headers.get('content-type') + ')' }; }
  const t = await r.text(); clearTimeout(tm);
  return { r, t };
};
for (const u of urls) {
  try {
    const { r, t } = await head(u);
    console.log('==', r.status, 'cors', r.headers.get('access-control-allow-origin'), r.url);
    console.log(t.slice(0, 500));
    // follow first variant
    const v = t.split('\n').find((l) => l && !l.startsWith('#') && l.includes('.m3u8'));
    if (v) {
      const vu = new URL(v, r.url).href; const x = await head(vu);
      const segs = (x.t.match(/#EXTINF:([\d.]+)/g) || []).map((s) => +s.split(':')[1]);
      console.log('  variant', x.r.status, 'cors', x.r.headers.get('access-control-allow-origin'), 'segments', segs.length, 'total s', Math.round(segs.reduce((a, b) => a + b, 0)), x.t.split('\n').filter((l) => l.startsWith('#EXT-X-PLAYLIST-TYPE') || l.startsWith('#EXT-X-PROGRAM-DATE-TIME')).slice(0, 2));
    } else {
      const segs = (t.match(/#EXTINF:([\d.]+)/g) || []).map((s) => +s.split(':')[1]);
      if (segs.length) console.log('  segments', segs.length, 'total s', Math.round(segs.reduce((a, b) => a + b, 0)));
    }
  } catch (e) { console.log('ERR', u, e.message); }
}

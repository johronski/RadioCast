const urls = [
  'https://www.radiofrance.fr/mouv/api/live',
  'https://www.radiofrance.fr/mouv/api/live?webradio=mouv',
  'https://www.radiofrance.fr/api/live/webradios/mouv',
  'https://www.radiofrance.fr/mouv/api/songs',
  'https://api.radiofrance.fr/livemeta/live/6/inter_player',
  'https://api.radiofrance.fr/livemeta/live/6/mouv_player',
  'https://api.radiofrance.fr/livemeta/live/6/webrf_mouv_player',
  'https://api.radiofrance.fr/livemeta/live/7/fip_player',
  'https://icecast.radiofrance.fr/status-json.xsl',
];
for (const u of urls) {
  try {
    const r = await fetch(u, { headers: { Origin: 'https://johronski.github.io' } });
    const t = await r.text();
    console.log('==', r.status, 'cors:', r.headers.get('access-control-allow-origin'), u, '\n', t.replace(/\s+/g, ' ').slice(0, 700));
  } catch (e) { console.log('ERR', u, e.message); }
}

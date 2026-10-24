for (const id of [6, 7]) {
  const r = await fetch('https://api.radiofrance.fr/livemeta/pull/' + id);
  const t = await r.text();
  console.log('ID', id, r.status, t.length);
  try {
    const j = JSON.parse(t);
    console.log('keys', Object.keys(j));
    const now = Date.now() / 1000;
    const steps = Object.values(j.steps || {}).sort((a, b) => a.start - b.start);
    for (const s of steps.filter((s) => s.end > now - 1800 && s.start < now + 600)) console.log(s.start - now | 0, s.end - now | 0, s.embedType, '|', s.title, '|', s.authors, '|', s.performers, '|', s.titreAlbum, '| level', s.depth || s.level);
    console.log('levels', JSON.stringify(j.levels).slice(0, 600));
  } catch (e) { console.log(t.slice(0, 500)); }
}
// what the app makes of it
const m = await import('../../docs/js/sources.js');
const meta = JSON.parse((await import('fs')).readFileSync('docs/meta-sources.json', 'utf8'));
m.setPatterns(meta.sources);
for (const u of ['https://icecast.radiofrance.fr/mouv-midfi.mp3', 'https://icecast.radiofrance.fr/fip-midfi.mp3']) console.log(u, JSON.stringify(await m.fromPattern(u, 'x')));

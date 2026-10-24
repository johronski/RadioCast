// Checks against the real channels that songs are still found (their lists can change). Needs network.
import { chromium } from 'playwright';
const url = process.argv[2] || 'http://localhost:8765/index.html';
const b = await chromium.launch();
const p = await b.newPage();
await p.goto(url);
await p.waitForFunction(() => window.radiocast && window.radiocast.stations.length > 0);
let found = 0;
for (let i = 0; i < 6; i++) {
  const r = await p.evaluate(async (i) => {
    await window.radiocast.setPlaying(i);
    await new Promise((res) => setTimeout(res, 6000));
    const s = window.radiocast.stations[i];
    return s.name + ': ' + (window.radiocast.song ? window.radiocast.song.text : '(ingen låt just nu)')
      + ' · historik ' + window.radiocast.recentSongs(s.url).length;
  }, i);
  if (!r.includes('(ingen låt')) found++;
  console.log(r);
}
const search = await p.evaluate(async () => (await window.radiocast.freeSearch('sverige p3')).results.length);
console.log('Sökning "sverige p3": ' + search + ' träffar');
await b.close();
// Talk shows can leave one or two channels without a song; none at all means something broke.
if (found === 0 || search === 0) { console.error('Inga låtar eller sökträffar alls: kontrollera källorna.'); process.exit(1); }

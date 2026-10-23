const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  const errs = []; p.on('pageerror', e => errs.push('pageerror ' + e.message));
  p.on('console', m => { if (m.type() === 'error') errs.push('console ' + m.text()); });
  await p.goto('http://localhost:8765/index.html'); await p.waitForTimeout(1500);
  for (const i of [0, 2, 3, 5]) {
    const out = await p.evaluate(async (i) => {
      nowPlaying = stations[i]; localState = 'playing'; songs.watchedUrl = null; songs.history = [];
      await songTick(); await new Promise(r => setTimeout(r, 4000)); await songTick();
      await new Promise(r => setTimeout(r, 3000));
      return stations[i].name + ': ' + JSON.stringify(songs.current) + ' | historik ' + recentSongs(stations[i].url).length;
    }, i);
    console.log(out);
  }
  console.log(await p.evaluate(async () => { const r = await freeSearch('sverige p3'); return 'sök sverige p3: ' + r.results.length + ' ' + (r.results[0] && r.results[0].name); }));
  console.log(await p.evaluate(async () => { const r = await freeSearch('tehcno'); return 'sök tehcno: ' + r.results.length + ' ' + r.note; }));
  console.log(errs.join('\n') || 'inga fel');
  await b.close();
})();

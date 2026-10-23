const { chromium } = require('playwright');
(async () => {
  const b = await chromium.launch(); const p = await b.newPage();
  await p.goto('http://localhost:8765/index.html'); await p.waitForTimeout(1500);
  console.log(await p.evaluate(async () => {
    const url = stations[5].url; const hit = patternFor(url);
    const j = await getJson(hit.p.history.url.replace('$1', hit.key));
    const o = [];
    o.push('antal i svar: ' + (j.song || []).length);
    for (const x of (j.song || []).slice(0, 25)) o.push(JSON.stringify([fill(hit.p.history.song, x), cleanSong(fill(hit.p.history.song, x), stations[5].name), at(x, 'starttimeutc')]));
    songs.history = []; await backfill(url, stations[5].name);
    o.push('historik efter backfill: ' + songs.history.length);
    return o.join('\n');
  }));
  await b.close();
})();

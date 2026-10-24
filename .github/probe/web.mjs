import { webkit, devices } from 'playwright';
const b = await webkit.launch();
const c = await b.newContext({ ...devices['iPhone 13'], locale: 'sv-SE' });
const p = await c.newPage();
await p.goto('http://localhost:8765/index.html'); await p.waitForTimeout(1500);
for (let round = 0; round < 2; round++) {
  const out = await p.evaluate(async () => {
    const m = await import('./js/sources.js');
    const urls = window.radiocast.stations.map((s) => [s.name, s.url]);
    const res = [];
    for (const [n, u] of urls) {
      const hit = m.patternFor(u);
      let f = null; try { f = await m.fromPattern(u, n); } catch (e) { f = 'ERR ' + e.message; }
      res.push(n + ' | ' + (hit && hit.api) + ' | ' + JSON.stringify(f && (f.text !== undefined ? f.text : f)));
    }
    return res.join('\n');
  });
  console.log(new Date().toISOString()); console.log(out);
  await p.waitForTimeout(20000);
}
await b.close();

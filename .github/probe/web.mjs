import { webkit, devices } from 'playwright';
const b = await webkit.launch();
const c = await b.newContext({ ...devices['iPhone 13'], locale: 'sv-SE' });
const p = await c.newPage();
const errors = []; p.on('pageerror', (e) => errors.push(e.message));
await p.goto('http://localhost:8765/index.html'); await p.waitForTimeout(1500);
console.log(await p.evaluate(() => window.radiocast.version));
const names = await p.$$eval('.row .name', (n) => n.map((x) => x.textContent));
for (let i = 0; i < names.length; i++) {
  await p.locator('.row').nth(i).click(); await p.waitForTimeout(8000);
  console.log(names[i], '|', (await p.innerText('#np')).replace(/\s+/g, ' '), '| volym synlig:', await p.isVisible('.vol'), '| t', await p.evaluate(() => document.getElementById('audio').currentTime.toFixed(1)));
}
console.log('fel:', errors.join('; ') || 'inga');
await b.close();

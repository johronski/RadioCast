// Opens the web app in a real (headless) Chrome and clicks through it. No network needed.
import { chromium } from 'playwright';
const url = process.argv[2] || 'http://localhost:8765/index.html';
const b = await chromium.launch();
const p = await b.newPage({ viewport: { width: 390, height: 844 } });
const errors = [];
p.on('pageerror', (e) => errors.push(e.message));
await p.route(/^https?:\/\/(?!localhost)/, (r) => r.abort()); // offline: only the app itself
await p.goto(url);
await p.waitForFunction(() => window.radiocast && document.querySelectorAll('.row').length > 0, null, { timeout: 10000 });
const check = (ok, what) => { if (!ok) { errors.push('Misslyckades: ' + what); } else console.log('ok  ' + what); };
check(await p.locator('.row').count() === 6, 'listan visar 6 kanaler');
await p.click('#searchBar');
check(await p.isVisible('#search'), 'sökfönstret öppnas');
check(await p.locator('#genres .gchip').count() === 12, 'genreknapparna finns');
await p.click('#searchBack');
await p.click('#orderOwn'); await p.click('#editBtn');
check(await p.locator('.grip').count() === 6, 'redigeringsläget visar handtag');
await p.click('#editBtn');
await p.click('.row >> nth=0');
check(await p.isVisible('#np'), 'spelarkortet visas efter tryck på en kanal');
await p.click('#npText');
check(await p.isVisible('#sheet'), 'låtvyn öppnas');
await p.click('#sheetClose'); await p.waitForTimeout(300);
check(await p.isHidden('#sheet'), 'låtvyn stängs med ✕');
await p.click('#menuBtn');
check((await p.textContent('#sheetBody')).includes('Dela min kanallista'), 'menyn öppnas');
// Restarted while the radio played on a speaker: the app says where it probably plays.
const p2 = await b.newPage();
p2.on('pageerror', (e) => errors.push(e.message));
await p2.route(/^https?:\/\/(?!localhost)/, (r) => r.abort());
await p2.addInitScript(() => localStorage.setItem('rc.castingOn',
  JSON.stringify({ device: 'Kök', url: 'https://live1.sr.se/p3-aac-320', at: Date.now() })));
await p2.goto(url);
await p2.waitForTimeout(3500);
check((await p2.textContent('#chipText')) === 'Kök', 'efter omstart visar knappen högtalaren');
check((await p2.textContent('#npStatus')).includes('troligen på Kök'), 'efter omstart: "spelar troligen på Kök"');
await b.close();
if (errors.length) { console.error(errors.join('\n')); process.exit(1); }
console.log('Allt gick bra.');

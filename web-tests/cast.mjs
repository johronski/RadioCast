// Casting, with a pretend Google Cast SDK (web-tests/castsim.js) and a fake clock: every case
// a phone user meets with Chromecast, checked on what the screen shows and what the speakers play.
// Usage: node web-tests/cast.mjs [url]
import { chromium } from 'playwright';
import { readFileSync } from 'node:fs';

const url = process.argv[2] || 'http://localhost:8765/index.html';
const sim = readFileSync(new URL('./castsim.js', import.meta.url), 'utf8');
const STATIONS = [
  { name: 'Alfa', url: 'http://radio.test/alfa.mp3', cc: 'SE', br: 128, logo: '', genre: 'Pop' },
  { name: 'Beta', url: 'http://radio.test/beta.mp3', cc: 'SE', br: 128, logo: '', genre: 'Jazz' },
  { name: 'Gamma', url: 'http://radio.test/gamma.mp3', cc: 'SE', br: 128, logo: '', genre: 'Rock' },
];
const ANDROID = 'Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Mobile Safari/537.36';

const b = await chromium.launch();
const failures = [];
let passed = 0;

const DESKTOP = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0 Safari/537.36';
async function scenario(name, setup, run) {
  const c = await b.newContext({ viewport: { width: 390, height: 844 }, userAgent: (setup && setup.desktop) ? DESKTOP : ANDROID, locale: 'sv-SE' });
  const p = await c.newPage();
  const errors = [];
  p.on('pageerror', (e) => errors.push(e.message));
  await p.route(/^https?:\/\/(?!localhost)/, (r) => r.abort());
  await p.clock.install();
  await p.addInitScript(({ stations, extra }) => {
    localStorage.setItem('rc.stations', JSON.stringify(stations));
    for (const [k, v] of Object.entries(extra || {})) localStorage.setItem('rc.' + k, JSON.stringify(v));
  }, { stations: STATIONS, extra: setup && setup.store });
  await p.addInitScript(sim);
  if (setup && setup.before) await p.addInitScript(setup.before);
  await p.goto(url + ((setup && setup.hash) || ''));
  await p.clock.runFor(1500);
  const t = {
    p,
    wait: (ms) => p.clock.runFor(ms),
    tap: async (name) => { await p.locator('.row', { hasText: name }).first().click(); await p.clock.runFor(100); },
    chip: () => p.textContent('#outText'),
    status: async () => (await p.textContent('#npStatus')).trim(),
    card: async () => (await p.textContent('#npName')).trim(),
    marked: () => p.$$eval('.row.playing .name', (n) => n.map((x) => x.textContent).join(',')),
    dev: (n) => p.evaluate((n) => ({ ...window.sim.devices[n], media: undefined }), n),
    set: (expr) => p.evaluate(expr),
    snack: async () => ((await p.isVisible('#snack')) ? (await p.textContent('#snackText')).trim() : ''),
    menu: async (label) => { await p.click('#outBtn'); await p.clock.runFor(300); await p.locator('#sheetBody .menu-item', { hasText: label }).click(); await p.clock.runFor(100); },
    connect: async (dev) => { await t.set(`window.sim.pick = ${JSON.stringify(dev)}`); await p.click('#outBtn'); await p.clock.runFor(3000); },
  };
  const checks = [];
  const expect = (ok, what) => checks.push([!!ok, what]);
  try { await run(t, expect); } catch (e) { checks.push([false, 'kastade: ' + e.message]); }
  for (const e of errors) checks.push([false, 'sidfel: ' + e]);
  const bad = checks.filter((x) => !x[0]);
  if (bad.length) failures.push(name + ':\n   - ' + bad.map((x) => x[1]).join('\n   - '));
  else passed++;
  console.log((bad.length ? 'FEL ' : 'ok  ') + name);
  await c.close();
}

const alfa = STATIONS[0].url, beta = STATIONS[1].url;

await scenario('ansluter till Kök när inget spelar', null, async (t, expect) => {
  await t.connect('Kök');
  expect((await t.chip()) === 'Kök', 'knappen visar Kök, inte ' + (await t.chip()));
  expect(!(await t.dev('Kök')).playing, 'Kök ska inte börja spela av sig själv');
});

await scenario('det som spelar i telefonen flyttar till högtalaren', null, async (t, expect) => {
  await t.set('window.radiocast.setPlaying(0)');
  await t.connect('Kök');
  await t.wait(1500);
  expect((await t.dev('Kök')).playing === alfa, 'Kök spelar Alfa');
  expect((await t.status()) === 'Spelar på Kök', 'status: ' + (await t.status()));
  expect((await t.marked()) === 'Alfa', 'listan markerar Alfa: ' + (await t.marked()));
});

await scenario('byter kanal på högtalaren', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.tap('Beta'); await t.wait(1500);
  expect((await t.dev('Kök')).playing === beta, 'Kök spelar Beta');
  expect((await t.card()) === 'Beta' && (await t.status()) === 'Spelar på Kök', 'kortet: ' + (await t.card()) + ' / ' + (await t.status()));
  expect((await t.marked()) === 'Beta', 'listan markerar Beta: ' + (await t.marked()));
});

await scenario('Chrome rapporterar gamla kanalen en stund efter bytet', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.devices['Kök'].lag = true");
  await t.tap('Beta');
  for (let i = 0; i < 6; i++) {
    await t.wait(500);
    expect((await t.card()) === 'Beta', 'kortet hoppar inte tillbaka till Alfa (' + i * 0.5 + ' s): ' + (await t.card()));
  }
  await t.wait(2000);
  expect((await t.card()) === 'Beta' && (await t.status()) === 'Spelar på Kök', 'till slut: ' + (await t.card()) + ' / ' + (await t.status()));
});

await scenario('grupp där Chrome aldrig märker kanalbytet (logg 26 okt)', null, async (t, expect) => {
  await t.connect('Vardagsrum/Hall');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.devices['Vardagsrum/Hall'].stale = true");
  await t.tap('Beta'); await t.wait(1500);
  expect((await t.dev('Vardagsrum/Hall')).playing === beta, 'gruppen spelar Beta');
  for (const s of [2, 8, 30]) {
    await t.wait(s === 2 ? 500 : s === 8 ? 6000 : 22000);
    expect((await t.card()) === 'Beta' && (await t.marked()) === 'Beta', 'visar Beta efter ' + s + ' s: ' + (await t.card()) + ' / ' + (await t.marked()));
  }
  expect(!(await t.snack()).includes('svarade inte'), 'inget falskt "svarade inte"');
});

await scenario('delad styrlänk: ansluter till pågående cast', {
  before: () => { const d = window.sim.devices['Kök']; d.playing = 'http://radio.test/gamma.mp3'; d.state = 'PLAYING'; d.sessionId = 'S77'; d.title = 'Gamma'; },
  hash: '#styr=S77&url=' + encodeURIComponent('http://radio.test/gamma.mp3') + '&namn=Gamma&hogtalare=K%C3%B6k',
}, async (t, expect) => {
  await t.wait(3000);
  expect((await t.chip()) === 'Kök' && (await t.card()) === 'Gamma' && (await t.status()) === 'Spelar på Kök', 'visar: ' + (await t.chip()) + ' / ' + (await t.card()) + ' / ' + (await t.status()));
  expect((await t.set('window.sim.loads.length')) === 0, 'startade inte om strömmen');
});

await scenario('efter omstart: "Spelar troligen på" → Välj högtalare', {
  store: { castingOn: { device: 'Kök', url: 'http://radio.test/beta.mp3', at: Date.now(), sessionId: null } },
}, async (t, expect) => {
  await t.wait(5500);
  expect((await t.status()).includes('troligen på Kök'), 'gissar Kök: ' + (await t.status()));
  await t.set("window.sim.pick = 'Kök'");
  await t.menu('Välj högtalare'); await t.wait(3500);
  expect((await t.dev('Kök')).playing === beta, 'Beta startar på Kök');
  expect((await t.status()) === 'Spelar på Kök', 'status: ' + (await t.status()));
});

await scenario('grupp som spelar men aldrig svarar appen', null, async (t, expect) => {
  await t.connect('Vardagsrum/Hall');
  await t.set("window.sim.devices['Vardagsrum/Hall'].mode = 'silent-ok'");
  await t.tap('Beta'); await t.wait(3000);
  expect((await t.dev('Vardagsrum/Hall')).playing === beta, 'gruppen spelar Beta');
  expect((await t.status()) === 'Spelar på Vardagsrum/Hall', 'status utan att vänta 20 s: ' + (await t.status()));
  await t.wait(25000);
  expect(!(await t.snack()).includes('svarade inte'), 'inget falskt "svarade inte": ' + (await t.snack()));
});

await scenario('högtalare som aldrig svarar', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.devices['Kök'].mode = 'hang'");
  await t.tap('Beta'); await t.wait(21000);
  expect((await t.snack()).includes('svarade inte'), 'säger till: ' + (await t.snack()));
  expect((await t.card()) === 'Alfa', 'visar det som verkligen spelar (Alfa): ' + (await t.card()));
  expect((await t.marked()) === 'Alfa', 'listan: ' + (await t.marked()));
});

await scenario('högtalare som vägrar', null, async (t, expect) => {
  await t.connect('Kök');
  await t.set("window.sim.devices['Kök'].mode = 'fail'");
  await t.tap('Beta'); await t.wait(1000);
  expect((await t.status()).includes('kunde inte spela'), 'felbesked: ' + (await t.status()));
});

await scenario('stopp', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.p.click('#npButton'); await t.wait(500);
  expect(!(await t.dev('Kök')).playing, 'Kök tyst');
  expect((await t.marked()) === '', 'ingen markerad kanal');
});

await scenario('spela i telefonen', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.menu('Spela i telefonen'); await t.wait(800);
  expect(!(await t.dev('Kök')).playing, 'Kök tyst');
  expect((await t.chip()) === 'Telefon', 'knappen: ' + (await t.chip()));
  expect((await t.card()) === 'Alfa', 'Alfa fortsätter i telefonen');
});

await scenario('byt högtalare: Kök → Vardagsrum/Hall', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'Vardagsrum/Hall'; window.sim.pickDelay = 2000");
  await t.menu('Byt högtalare'); await t.wait(500);
  expect((await t.dev('Kök')).playing === alfa, 'Kök spelar medan du väljer');
  await t.wait(9000);
  expect((await t.dev('Vardagsrum/Hall')).playing === alfa, 'Vardagsrum/Hall spelar Alfa');
  expect(!(await t.dev('Kök')).playing, 'Kök tyst');
  expect((await t.chip()) === 'Vardagsrum/Hall' && (await t.status()) === 'Spelar på Vardagsrum/Hall', 'visar: ' + (await t.chip()) + ' / ' + (await t.status()));
  expect(!(await t.set('window.sim.wrongDialogs')), 'listan kom direkt, inte den gamla högtalarens ruta (logg 26 okt)');
  await t.tap('Beta'); await t.wait(1500);
  expect((await t.dev('Vardagsrum/Hall')).playing === beta, 'kanalbyte direkt efteråt fungerar');
});

await scenario('byt högtalare: Chrome ansluter tillbaka själv och visar fel ruta (logg 26 okt)', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'Vardagsrum/Hall'; window.sim.releaseDelay = 700; window.sim.autoJoin = true");
  await t.menu('Byt högtalare'); await t.wait(1000);
  expect((await t.set('window.sim.wrongDialogs')) === 1, 'första försöket: Chrome visar fel ruta');
  await t.wait(2500);
  expect((await t.snack()).includes('Tryck Byt högtalare igen'), 'säger att du kan trycka igen: ' + (await t.snack()));
  await t.wait(4000);
  expect((await t.chip()) === 'Kök' && (await t.dev('Kök')).playing === alfa, 'Kök spelar vidare under tiden: ' + (await t.chip()));
  expect(!(await t.snack()).includes('Kunde inte ansluta'), 'inget falskt "Kunde inte ansluta"');
  await t.set("window.sim.releaseDelay = 0");
  await t.menu('Byt högtalare'); await t.wait(5000);
  expect((await t.dev('Vardagsrum/Hall')).playing === alfa && (await t.status()) === 'Spelar på Vardagsrum/Hall', 'andra försöket: flyttad: ' + (await t.status()));
});

await scenario('byt högtalare: Chrome vägrar först', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'refuse'; window.sim.afterRefuse = 'Vardagsrum/Hall'");
  await t.menu('Byt högtalare'); await t.wait(5000);
  expect((await t.dev('Vardagsrum/Hall')).playing === alfa, 'Vardagsrum/Hall spelar Alfa');
});

await scenario('byt högtalare: stänger listan', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'cancel'");
  await t.menu('Byt högtalare'); await t.wait(6000);
  expect((await t.dev('Kök')).playing === alfa, 'Kök spelar fortfarande Alfa');
  expect((await t.chip()) === 'Kök', 'tillbaka på Kök: ' + (await t.chip()));
  expect((await t.status()) === 'Spelar på Kök', 'status: ' + (await t.status()));
});

await scenario('dator: byt högtalare i Chromes fulla lista', { desktop: true, before: () => { window.sim.desktop = true; } }, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'Vardagsrum/Hall'");
  await t.menu('Byt högtalare'); await t.wait(800);
  expect((await t.dev('Kök')).playing === alfa, 'Kök spelar medan du väljer');
  await t.wait(4000);
  expect((await t.dev('Vardagsrum/Hall')).playing === alfa && (await t.status()) === 'Spelar på Vardagsrum/Hall', 'flyttad: ' + (await t.status()));
});

await scenario('dator: Sluta casta i Chromes lista', { desktop: true, before: () => { window.sim.desktop = true; } }, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.pick = 'stop'");
  await t.menu('Byt högtalare'); await t.wait(7000);
  expect(!(await t.dev('Kök')).playing, 'Kök tyst');
  expect((await t.chip()) === 'Telefon' && !(await t.snack()).includes('bröts'), 'telefon, utan felmeddelande: ' + (await t.chip()) + ' / ' + (await t.snack()));
});

await scenario('en annan app tar över högtalaren', null, async (t, expect) => {
  await t.connect('Kök');
  await t.tap('Alfa'); await t.wait(1500);
  await t.set("window.sim.takeOver('Kök')"); await t.wait(500);
  expect((await t.chip()) === 'Telefon', 'knappen: ' + (await t.chip()));
  expect((await t.snack()).includes('Kontakten med Kök bröts'), 'säger till: ' + (await t.snack()));
  expect(!(await t.set("localStorage.getItem('rc.castingOn')")) || (await t.set("localStorage.getItem('rc.castingOn')")) === 'null', 'glömmer var det spelade');
});

await scenario('omstart medan Kök spelar: ansluter igen utan avbrott', {
  store: { castingOn: { device: 'Kök', url: 'http://radio.test/beta.mp3', at: Date.now(), sessionId: 'S9' } },
  before: () => { const d = window.sim.devices['Kök']; d.playing = 'http://radio.test/beta.mp3'; d.state = 'PLAYING'; d.sessionId = 'S9'; d.title = 'Beta'; },
}, async (t, expect) => {
  await t.wait(2500);
  expect((await t.chip()) === 'Kök', 'knappen: ' + (await t.chip()));
  expect((await t.card()) === 'Beta' && (await t.status()) === 'Spelar på Kök', 'kortet: ' + (await t.card()) + ' / ' + (await t.status()));
  expect((await t.set('window.sim.loads.length')) === 0, 'startade inte om strömmen');
});

await scenario('högtalaren spelar redan något annat när du ansluter', {
  before: () => { const d = window.sim.devices['Kök']; d.playing = 'http://radio.test/gamma.mp3'; d.state = 'PLAYING'; d.title = 'Gamma'; },
}, async (t, expect) => {
  // Chrome restarts the player when a session starts on it: it stops; nothing new starts by itself.
  await t.connect('Kök'); await t.wait(3000);
  expect((await t.set('window.sim.loads.length')) === 0, 'startar inget av sig själv');
  expect((await t.chip()) === 'Kök', 'ansluten');
});

await scenario('reglaget följer volymknapparna även efter att du dragit i det (27 okt)', null, async (t, expect) => {
  await t.set('window.radiocast.setPlaying(0)');
  await t.connect('Kök'); await t.wait(1500);
  await t.set('window.sim.buttons(0.3)'); await t.wait(300);
  expect((await t.set("+document.getElementById('volume').value")) === 30, 'före: reglaget följer knapparna');
  // As in Chrome on the phone: the finger touches the slider, the value changes, but no pointerup comes.
  await t.set(`(() => { const v = document.getElementById('volume');
    v.dispatchEvent(new PointerEvent('pointerdown', { bubbles: true }));
    v.value = 60; v.dispatchEvent(new Event('input', { bubbles: true })); v.dispatchEvent(new Event('change', { bubbles: true }));
    v.dispatchEvent(new PointerEvent('pointercancel', { bubbles: true })); })()`);
  await t.wait(300);
  await t.set('window.sim.buttons(0.8)'); await t.wait(300);
  const v = await t.set("+document.getElementById('volume').value");
  expect(v === 80, 'efter: reglaget följer knapparna, visar ' + v);
});

await b.close();
console.log(passed + ' fall gick bra, ' + failures.length + ' fel.');
if (failures.length) { console.error(failures.join('\n')); process.exit(1); }

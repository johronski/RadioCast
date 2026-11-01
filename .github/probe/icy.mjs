// Probe 1 nov: kan webbappen läsa "spelas nu" ur okända kanaler? ICY via fetch + Icecast-statussida, i riktig Chromium.
import { chromium } from 'playwright';
const N = 30;
const list = await (await fetch(`https://de1.api.radio-browser.info/json/stations/search?order=random&limit=${N}&hidebroken=true`, { headers: { 'User-Agent': 'RadioCast-probe' } })).json();
const b = await chromium.launch();
const p = await b.newPage();
await p.goto('http://localhost:8765/index.html');
const res = await p.evaluate(async (list) => {
  const dec = new TextDecoder('utf-8');
  async function icy(url) {
    const ac = new AbortController(); const t = setTimeout(() => ac.abort(), 12000);
    try {
      const r = await fetch(url, { headers: { 'Icy-MetaData': '1' }, signal: ac.signal, cache: 'no-store' });
      const mi = r.headers.get('icy-metaint');
      const rd = r.body.getReader(); let buf = new Uint8Array(0);
      while (buf.length < 300000) {
        const { value, done } = await rd.read(); if (done) break;
        const n = new Uint8Array(buf.length + value.length); n.set(buf); n.set(value, buf.length); buf = n;
        const s = dec.decode(buf.length > 60000 ? buf.subarray(buf.length - 60000) : buf);
        const m = /StreamTitle='(.*?)';/s.exec(s);
        if (m) { ac.abort(); return { ok: 'titel', text: m[1], header: !!mi }; }
      }
      ac.abort(); return { ok: 'ingen titel i strömmen', header: !!mi };
    } catch (e) { return { ok: 'fetch nekad (' + (e.name === 'AbortError' ? 'tidsgräns' : 'CORS/nät') + ')' }; }
    finally { clearTimeout(t); }
  }
  async function status(url) {
    try {
      const u = new URL(url); const ac = new AbortController(); setTimeout(() => ac.abort(), 6000);
      const j = await (await fetch(u.origin + '/status-json.xsl', { signal: ac.signal })).json();
      let s = j.icestats && j.icestats.source; if (!s) return 'ingen källa';
      if (!Array.isArray(s)) s = [s];
      const m = s.find((o) => (o.listenurl || '').endsWith(u.pathname)) || (s.length === 1 ? s[0] : null);
      return m ? ('titel: ' + [m.artist, m.title].filter(Boolean).join(' – ')) : 'ingen matchande ström';
    } catch (e) { return 'nej'; }
  }
  return Promise.all(list.map(async (s) => {
    const url = s.url_resolved || s.url;
    const o = { name: s.name.trim().slice(0, 40), cc: s.countrycode, codec: s.codec, url, hls: !!s.hls || /\.m3u8/.test(url) };
    if (!url.startsWith('https:')) { o.icy = { ok: 'http – blockeras på https-sidan' }; o.status = '-'; return o; }
    if (o.hls) { o.icy = { ok: 'HLS – ingen ICY' }; o.status = '-'; return o; }
    [o.icy, o.status] = await Promise.all([icy(url), status(url)]);
    return o;
  }));
}, list);
const sum = {};
for (const r of res) {
  console.log(`${r.name} | ${r.cc} | ${r.codec}\n  ICY: ${r.icy.ok}${r.icy.text !== undefined ? ' "' + r.icy.text + '"' : ''}${r.icy.header !== undefined ? ' (metaint läsbar: ' + r.icy.header + ')' : ''}\n  status: ${r.status}\n  ${r.url}`);
  const k = r.icy.ok === 'titel' && r.icy.text.trim() ? 'låt via ICY' : (String(r.status).startsWith('titel: ') && r.status.length > 8 ? 'låt bara via statussida' : r.icy.ok.split(' (')[0]);
  sum[k] = (sum[k] || 0) + 1;
}
console.log('\nSAMMANFATTNING (' + res.length + ' kanaler):', JSON.stringify(sum, null, 1));
await b.close();

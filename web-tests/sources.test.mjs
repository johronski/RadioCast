import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { setPatterns, patternFor, at, fill, liveMeta, historyFrom } from '../docs/js/sources.js';
import { addHistory, recent } from '../docs/js/history.js';

const root = new URL('..', import.meta.url);
const meta = JSON.parse(readFileSync(new URL('docs/meta-sources.json', root)));
setPatterns(meta.sources);

test('kanalernas listor hittas för rätt strömmar', () => {
  assert.equal(patternFor('https://icecast.radiofrance.fr/fip-midfi.mp3').key, '7');
  assert.equal(patternFor('https://icecast.radiofrance.fr/mouv-midfi.mp3').key, '6');
  assert.equal(patternFor('https://live1.sr.se/p3-aac-320').key, '164');
  assert.equal(patternFor('https://sverigesradio.se/topsy/direkt/srapi/164.mp3').key, '164');
  assert.equal(patternFor('https://novazz.ice.infomaniak.ch/novazz-128.mp3').p.id, 'nova');
  assert.equal(patternFor('https://streams.scenesat.com/main/hq.mp3'), null);
});
test('sökvägar och mallar', () => {
  const j = { playlist: { song: { artist: 'Titiyo', title: 'Come Along' } }, list: [{ n: 'a' }, { n: 'b' }] };
  assert.equal(at(j, 'list[1].n'), 'b');
  assert.equal(fill('{playlist.song.artist} – {playlist.song.title}', j), 'Titiyo – Come Along');
  assert.equal(fill('{playlist.song.artist} – {saknas}', j), 'Titiyo');
  assert.equal(fill('{x} – {y}', j), null);
});
test('Radio France: nuvarande låt och tidigare låtar', () => {
  const now = 1000000;
  const json = { steps: {
    a: { embedType: 'song', start: now - 400, end: now - 200, title: 'Gammal', authors: 'Artist A', visual: 'https://x/a.jpg' },
    b: { embedType: 'song', start: now - 100, end: now + 100, title: 'Nu', performers: '', authors: 'Artist B' },
    c: { embedType: 'expression', start: now - 1000, end: now + 1000, title: 'Programmet' },
    d: { embedType: 'song', start: now + 200, end: now + 400, title: 'Sen', authors: 'C' },
  } };
  const r = liveMeta(json, 'u', 'FIP', now);
  assert.equal(r.text, 'Artist B – Nu');
  assert.deepEqual(r.past.map((p) => p.text), ['Artist A – Gammal']);
  assert.equal(r.past[0].at, (now - 400) * 1000);
  assert.equal(liveMeta({ steps: { c: json.steps.c } }, 'u', 'FIP', now).text, null);
});
test('Sveriges Radio: bara låtar som redan börjat räknas', () => {
  const h = meta.sources.find((s) => s.id === 'sverigesradio-kanal').history;
  const now = 1792638500000;
  const json = { song: [
    { artist: 'Raye', title: 'Joy.', starttimeutc: '/Date(1792638457000)/' },
    { artist: 'Planerad', title: 'Senare', starttimeutc: '/Date(1792639999000)/' },
  ] };
  const out = historyFrom(json, h, 'u', 'P3', now);
  assert.deepEqual(out.map((s) => s.text), ['Raye – Joy.']);
});
test('historik: dubbletter slås ihop och nyast hamnar först', () => {
  const list = [];
  assert.equal(addHistory({ text: 'A – 1', url: 'u', at: 1000 }, list, false), true);
  assert.equal(addHistory({ text: 'A – 2', url: 'u', at: 5000 }, list, false), true);
  assert.equal(addHistory({ text: 'A – 1', url: 'u', at: 2000, cover: 'c' }, list, false), false);
  assert.deepEqual(list.map((s) => s.text), ['A – 2', 'A – 1']);
  assert.equal(list[1].cover, 'c');
  assert.deepEqual(recent('u', 'A – 2', list).map((s) => s.text), ['A – 1']);
});
test('låtkällorna är samma för webbappen och Android-appen', () => {
  const docs = readFileSync(new URL('docs/meta-sources.json', root), 'utf8');
  assert.equal(readFileSync(new URL('meta-sources.json', root), 'utf8'), docs);
  assert.equal(readFileSync(new URL('app/src/main/assets/meta-sources.json', root), 'utf8'), docs);
});

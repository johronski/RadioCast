import test from 'node:test';
import assert from 'node:assert/strict';
import { cleanSong, shortTitle, capitalise, splitSong } from '../docs/js/songtext.js';

const cases = [
  ['Daft Punk - Around the World', 'Radio Nova', 'Daft Punk – Around the World'],
  ['  Daft Punk -  Around the World ', 'Radio Nova', 'Daft Punk – Around the World'],
  ['Radio Nova', 'Radio Nova', null],
  ['NOVA', 'Radio Nova', null],
  ['Nova - le grand mix', 'Radio Nova', null],
  ['Unknown', 'FIP', null],
  ['www.nova.fr', 'Radio Nova', null],
  [' - ', 'FIP', null],
  ['Jingle Nova', 'Radio Nova', null],
  ['Publicité', "Mouv'", null],
  ['Fela Kuti', 'FIP', 'Fela Kuti'],
  ['Morgonpasset i P3 med David Druid, spelar nu Titiyo - Come Along', 'Sveriges Radio P3', 'Titiyo – Come Along'],
  ['REDBONE – COME AND GET YOUR LOVE', 'Radio Nova', 'Redbone – Come And Get Your Love'],
  ['RACHEL CHINOURIRI - One song away', 'Radio Nova', 'Rachel Chinouriri – One song away'],
  ['ABBA - Dancing Queen', 'P3', 'ABBA – Dancing Queen'],
];
for (const [raw, station, want] of cases) {
  test(`städar "${raw}"`, () => assert.equal(cleanSong(raw, station), want));
}
test('korta titlar', () => {
  assert.equal(shortTitle('Come Along (Radio Edit)'), 'Come Along');
  assert.equal(shortTitle('Heroes – 2017 Remaster'), 'Heroes');
  assert.equal(shortTitle('Song [Explicit]'), 'Song');
  assert.equal(shortTitle('Edit (Radio Edit)'), 'Edit');
  assert.equal(shortTitle('Sending you love (feat. Natasha Watts)'), 'Sending you love (feat. Natasha Watts)');
});
test('versaler och delning', () => {
  assert.equal(capitalise('AC/DC'), 'AC/DC');
  assert.deepEqual(splitSong('A – B – C'), ['A', 'B – C']);
});

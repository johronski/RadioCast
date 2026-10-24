import test from 'node:test';
import assert from 'node:assert/strict';
import { shortName, initials, guessCountry, countryName } from '../docs/js/names.js';

test('korta kanalnamn', () => {
  assert.equal(shortName('Radio Nova 101.5 FM | Paris | Musique éclectique'), 'Radio Nova 101.5 FM');
  assert.equal(shortName('FIP'), 'FIP');
});
test('initialer på logotyprutan', () => {
  assert.equal(initials('Radio Nova'), 'NOVA');
  assert.equal(initials('Sveriges Radio P3'), 'P3');
  assert.equal(initials("Mouv'"), 'MOUV');
});
test('land från strömlänk och landsnamn', () => {
  assert.equal(guessCountry('https://live1.sr.se/p3-aac-320'), 'SE');
  assert.equal(guessCountry('https://icecast.radiofrance.fr/fip-midfi.mp3'), 'FR');
  assert.equal(countryName('SE'), 'Sverige');
});

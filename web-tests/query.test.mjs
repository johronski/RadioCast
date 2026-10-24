import test from 'node:test';
import assert from 'node:assert/strict';
import { parseQuery, correct, score } from '../docs/js/query.js';

test('land och namn: "sverige p3"', () => {
  assert.deepEqual(parseQuery('sverige p3'), { country: 'SE', language: null, text: 'p3' });
});
test('språk och svensk genre: "franska nyheter"', () => {
  assert.deepEqual(parseQuery('franska nyheter'), { country: null, language: 'french', text: 'news' });
});
test('felstavat land: "frankrke rap"', () => {
  assert.equal(parseQuery('frankrke rap').country, 'FR');
});
test('"france inter" är en kanal, inte "inter" i Frankrike', () => {
  assert.deepEqual(parseQuery('france inter'), { country: null, language: null, text: 'france inter' });
});
test('genre är inte land: "trance"', () => {
  assert.equal(parseQuery('trance').country, null);
});
test('stavfel rättas: tehcno → techno', () => {
  assert.equal(correct('tehcno'), 'techno');
  assert.equal(correct('p3'), 'p3');
});
test('rangordning: helt ord före del av ord', () => {
  assert.equal(score('Sveriges Radio P3', 'p3'), 3);
  assert.equal(score('Radio MP3 Hits', 'p3'), 1);
});

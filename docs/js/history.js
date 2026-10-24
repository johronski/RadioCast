/* The songs heard (or listed by the channel), newest first, kept on this device. */
import { store } from './util.js';

const MAX = 80;
export const history = store.get('history', []);

/** Adds a song unless it is already there (same channel and song within 20 minutes). */
export function addHistory(s, list = history, save = true) {
  for (const o of list) {
    if (o.url === s.url && o.text === s.text && Math.abs(o.at - s.at) < 20 * 60000) {
      if (!o.cover && s.cover) o.cover = s.cover;
      o.at = Math.min(o.at, s.at);
      return false;
    }
  }
  let i = 0;
  while (i < list.length && list[i].at > s.at) i++;
  list.splice(i, 0, s);
  if (list.length > MAX) list.length = MAX;
  if (save) store.set('history', list);
  return true;
}

/** Songs before the current one on a channel, newest first. */
export function recent(url, currentText, list = history) {
  return list.filter((s) => s.url === url && s.text !== currentText).slice(0, 15);
}

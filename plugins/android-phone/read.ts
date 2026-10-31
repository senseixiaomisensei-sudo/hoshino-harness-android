// WP-4 (B-1) composite read tools: schemas and argument checks. No Cordis imports so it can be unit-tested.
// The Android side (ScreenQueries.kt) validates again; checking here first keeps a bad call from touching the screen.
import type {Data} from './steps.js';

const s = (description?: string) => (description ? {type: 'string', description} : {type: 'string'});
const n = (description?: string) => (description ? {type: 'number', description} : {type: 'number'});

/** Element filters shared by phone_query and phone_find. Descriptions only where the name is not enough (prompt tokens). */
const FILTER = {text: s(), id: s(), role: s('btn/input/text/list/switch/tab'), inside: s('Text of its row/container')};
const BAND = {from_y: n(), to_y: n()};

export const READ_TOOLS: Array<{name: string; action: string; description: string; parameters: Data}> = [
  {name: 'phone_query', action: 'query', description: 'Find elements on the current screen by text/id/role/inside, with refs, container and row. No scrolling.',
    parameters: {...FILTER, limit: n()}},
  {name: 'phone_extract', action: 'extract', description: 'Read the current screen as text, rows (with refs) or form (labels and values); from_y/to_y 0..999 limit the area.',
    parameters: {mode: {type: 'string', enum: ['text', 'rows', 'form']}, ...BAND}},
  {name: 'phone_find', action: 'find', description: 'Scroll on the phone until an element matching text/id/role/inside shows; returns refs.',
    parameters: {...FILTER, direction: {type: 'string', enum: ['down', 'up', 'left', 'right']}, max_scrolls: n()}},
  {name: 'phone_collect', action: 'collect', description: 'Read a whole list or chat in one call: scrolls on the phone, drops duplicates, returns each row once with a stable key. up = older chat history; pass cursor to continue.',
    parameters: {direction: {type: 'string', enum: ['down', 'up']}, ...BAND, max_items: n(), max_scrolls: n(), cursor: s()}},
];

const int = (a: Data, k: string, min: number, max: number) => {
  const v = a[k];
  if (v === undefined || v === null) return undefined;
  if (typeof v !== 'number' || !Number.isInteger(v) || v < min || v > max) throw new Error(`${k} must be an integer ${min}..${max}`);
  return v;
};
const str = (a: Data, k: string) => {
  const v = a[k];
  if (v === undefined || v === null || v === '') return undefined;
  if (typeof v !== 'string') throw new Error(`${k} must be a string`);
  return v.trim().slice(0, 200) || undefined;
};
const oneOf = (a: Data, k: string, allowed: readonly string[]) => {
  const v = str(a, k);
  if (v !== undefined && !allowed.includes(v.toLowerCase())) throw new Error(`${k} must be ${allowed.join('/')}`);
  return v?.toLowerCase();
};
const pick = (o: Data) => Object.fromEntries(Object.entries(o).filter(([, v]) => v !== undefined));
const filters = (a: Data) => {
  const f = pick({text: str(a, 'text'), id: str(a, 'id'), role: str(a, 'role'), inside: str(a, 'inside')});
  if (!Object.keys(f).length) throw new Error('give at least one of text, id, role, inside');
  return f;
};
const band = (a: Data) => {
  const from_y = int(a, 'from_y', 0, 999); const to_y = int(a, 'to_y', 0, 999);
  if ((to_y ?? 999) - (from_y ?? 0) < 50) throw new Error('to_y must be at least 50 above from_y');
  return pick({from_y, to_y});
};

/** Tool arguments → body of the Android `phone` call (without sessionId). Throws a readable error on bad input. */
export function readArgs(action: string, a: Data = {}): Data {
  switch (action) {
    case 'query': return {action, ...filters(a), ...pick({limit: int(a, 'limit', 1, 60)})};
    case 'extract': return {action, ...pick({mode: oneOf(a, 'mode', ['text', 'rows', 'form'])}), ...band(a)};
    case 'find': return {action, ...filters(a), ...pick({direction: oneOf(a, 'direction', ['down', 'up', 'left', 'right']), max_scrolls: int(a, 'max_scrolls', 0, 30)})};
    case 'collect': {
      const cursor = str(a, 'cursor');
      if (cursor !== undefined && !/^c1\.(up|down)(\.[0-9a-f]{8}){1,4}$/.test(cursor)) throw new Error('bad cursor (pass the cursor a previous phone_collect returned)');
      const direction = oneOf(a, 'direction', ['down', 'up']) ?? cursor?.split('.')[1];
      if (cursor !== undefined && direction !== cursor.split('.')[1]) throw new Error('cursor was made for the other direction');
      return {action, ...pick({direction}), ...band(a), ...pick({max_items: int(a, 'max_items', 1, 300), max_scrolls: int(a, 'max_scrolls', 0, 40), cursor})};
    }
    default: throw new Error(`unknown read action ${action}`);
  }
}

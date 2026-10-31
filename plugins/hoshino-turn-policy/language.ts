// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// Port of Mika core/agent/ReplyLanguage.kt: is the final answer in the user's language? Counts letters by Unicode
// script, never by model or word list. Han and kana are counted per character, other scripts per word; quoted text,
// code, URLs, tags, parenthesised text and short name-like cells are not counted. Pure function.
export const MIN_USER_UNITS = 3;
export const MIN_ANSWER_UNITS = 20;
export const MISMATCH_SHARE = 0.6;
export const NAME_UNITS = 6;
export const KANA_SHARE = 0.1;
export type Family = string;
export const CHINESE = 'Chinese', JAPANESE = 'Japanese', KOREAN = 'Korean';
export type Mismatch = { answer: Family; user: Family };

const SCRIPTS: Array<[string, RegExp]> = [
  ['HAN', /\p{Script=Han}/u], ['KANA', /[\p{Script=Hiragana}\p{Script=Katakana}]/u], ['HANGUL', /\p{Script=Hangul}/u],
  ['LATIN', /\p{Script=Latin}/u], ['CYRILLIC', /\p{Script=Cyrillic}/u], ['ARABIC', /\p{Script=Arabic}/u], ['GREEK', /\p{Script=Greek}/u],
  ['HEBREW', /\p{Script=Hebrew}/u], ['THAI', /\p{Script=Thai}/u], ['DEVANAGARI', /\p{Script=Devanagari}/u],
];
const COMMONISH = /[\p{Script=Common}\p{Script=Inherited}]/u;
const LETTER = /\p{L}/u;
const MARK = /\p{M}/u;
const LOWER = /\p{Ll}/u;
function keyOf(ch: string): string { for (const [k, r] of SCRIPTS) if (r.test(ch)) return k; return 'OTHER'; }

type Run = { key: string; units: number };
function countsAsWord(word: string): boolean {
  const k = keyOf(word[0]!);
  if (k !== 'LATIN' && k !== 'CYRILLIC') return true;
  return [...word].some(c => LOWER.test(c));
}
function runsOf(cell: string): Run[] {
  const out: Run[] = [];
  let current = null as Run | null;
  let word = '';
  const endWord = () => { if (word && current && countsAsWord(word)) current.units++; word = ''; };
  for (const ch of cell) {
    if (MARK.test(ch)) continue;
    if (LETTER.test(ch) && COMMONISH.test(ch)) continue; // e.g. the prolonged sound mark follows its neighbours
    if (LETTER.test(ch)) {
      const key = keyOf(ch);
      if (current?.key !== key) { endWord(); current = { key, units: 0 }; out.push(current); }
      if (key === 'HAN' || key === 'KANA') current!.units++; else word += ch;
    } else if (ch === ' ' || ch === '-' || ch === '\'' || ch === '’') {
      endWord();
    } else { endWord(); current = null; }
  }
  endWord();
  return out;
}
const CELL = /[\n|、]/;
export function units(text: string, skipNames: boolean): Map<Family, number> {
  const cells = text.split(CELL).map(cell => runsOf(cell).filter(r => r.units > 0));
  const counted = (skipNames ? cells.filter(c => !(c.length === 1 && c[0]!.units <= NAME_UNITS)) : cells).flat();
  const han = counted.filter(r => r.key === 'HAN').reduce((a, r) => a + r.units, 0);
  const kana = counted.filter(r => r.key === 'KANA').reduce((a, r) => a + r.units, 0);
  const cjk = kana > 0 && kana >= (han + kana) * KANA_SHARE ? JAPANESE : CHINESE;
  const out = new Map<Family, number>();
  for (const r of counted) {
    const family = r.key === 'HAN' || r.key === 'KANA' ? cjk : r.key === 'HANGUL' ? KOREAN : r.key[0] + r.key.slice(1).toLowerCase() + ' script';
    out.set(family, (out.get(family) ?? 0) + r.units);
  }
  return out;
}
const FENCE = /```[\s\S]*?```/g;
const INLINE_CODE = /`[^`\n]*`/g;
const URL = /\b(?:https?:\/\/|www\.)\S+/gi;
const TAG = /<[^<>\n]*>/g;
const QUOTES = /「[^」\n]*」|『[^』\n]*』|“[^”\n]*”|"[^"\n]*"|《[^》\n]*》/g;
const PARENS = /[(（][^()（）\n]*[)）]/g;
export function stripQuoted(text: string): string {
  let s = text.replace(FENCE, ' ');
  for (const r of [INLINE_CODE, URL, TAG, QUOTES]) s = s.replace(r, ' ');
  for (;;) { const next = s.replace(PARENS, ' '); if (next === s) return s; s = next; }
}
const top = (m: Map<Family, number>) => [...m].reduce((best, e) => (e[1] > best[1] ? e : best), ['', -1] as [Family, number]);
/** The two families when the answer is clearly not in the language of the user's message; otherwise null. */
export function mismatch(user: string, answer: string): Mismatch | null {
  const u = units(user, false);
  const a = units(stripQuoted(answer), true);
  const uTotal = [...u.values()].reduce((x, y) => x + y, 0);
  const aTotal = [...a.values()].reduce((x, y) => x + y, 0);
  if (uTotal < MIN_USER_UNITS || aTotal < MIN_ANSWER_UNITS) return null;
  const [userFamily] = top(u);
  const [answerFamily, count] = top(a);
  if (count < aTotal * MISMATCH_SHARE || answerFamily === userFamily) return null;
  return { answer: answerFamily, user: userFamily };
}

// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// RT17 light rule check (subset of Mika core/verify/Grounding.kt): the literal facts in an answer — quoted text,
// numbers with units / decimals / 3+ digits / magnitudes, dates and times, digit-bearing identifiers — must appear in
// something read during the task (tool results) or in the user's own words. No model call; pure function.
export type Claim = { kind: 'quote' | 'number' | 'date' | 'token'; text: string };
export type Grounding = { claims: Claim[]; unmatched: Claim[] };

const ZERO_WIDTH = /[\u200B\u200C\u200D\uFEFF\u2060]/g;
const MARKUP = /\*\*|__|~~|`/g;
const FOOTNOTE = /\[\^?\d{1,3}\]|【\d{1,3}】/g;
const QUOTED = /「([^」\n]*)」|『([^』\n]*)』|“([^”\n]*)”|"([^"\n]*)"|‘([^’\n]*)’/g;
const NUMBER = /(?<!\d)(?<!\d[.,])\d+(?:[.,]\d+)*(?!\d)([ \t]*(?:[亿万]|千(?![米克瓦卡帕焦])))?/g;
const DATE = /(?<!\d)(?:\d{4}\s*[-/年]\s*\d{1,2}(?:\s*[-/月]\s*\d{1,2}\s*[日号]?)?|\d{4}\.\d{1,2}\.\d{1,2}|\d{1,2}\s*月\s*\d{1,2}\s*[日号]?|\d{1,2}\s*:\s*\d{2}(?:\s*:\s*\d{2})?)(?!\d)/g;
const ORDINAL = /第[ \t]*\d+[ \t]*(?=[条个集页项张篇步名位行章节轮次题])|前[ \t]*\d+[ \t]*(?=[个条名位项篇张])|top[ \t]*\d+|no\.[ \t]*\d+|#\d+|^[ \t]*\d+[ \t]*[.、)）](?!\d)/gim;
const UNIT_AFTER = /^\s*(?:%|‰|元|块|角|美元|欧元|日元|港元|英镑|℃|°|度|公里|千米|米|厘米|毫米|公斤|千克|斤|克|吨|小时|分钟|秒|毫秒|岁|英寸|寸|折|星|(?:km|cm|mm|kg|mAh|GB|MB|KB|TB|Hz|ms|[kKwWmgGM])(?![A-Za-z]))/;
const CURRENCY_BEFORE = /[¥$€£]\s*$/;
const TOKEN = /(?<![A-Za-z0-9_])(?=[A-Za-z0-9_-]*[A-Za-z])(?=[A-Za-z0-9_-]*\d)[A-Za-z0-9][A-Za-z0-9_-]{3,}(?![A-Za-z0-9_])/g;
const MAGNITUDE: Record<string, number> = { 亿: 8, 万: 4, 千: 3 };

function canon(s: string): string { return s.normalize('NFKC').replace(ZERO_WIDTH, '').replace(MARKUP, '').replace(FOOTNOTE, ' '); }
/** Loose text identity: letters and digits only, lowercase, thousands separators gone. */
function norm(s: string): string { return canon(s).replace(/(\d),(?=\d{3}\b)/g, '$1').toLowerCase().replace(/[^\p{L}\p{N}.]+/gu, ''); }
function scaled(value: string, magnitude: string): string | null {
  const v = Number(value.replace(/,(?=\d{3}\b)/g, ''));
  if (!Number.isFinite(v)) return null;
  const m = MAGNITUDE[magnitude.trim()];
  const out = m ? v * 10 ** m : v;
  return String(Math.round(out * 1e6) / 1e6);
}
function numbersIn(s: string): Set<string> {
  const out = new Set<string>();
  for (const m of canon(s).matchAll(NUMBER)) {
    const raw = m[0].replace(/[ \t]*[亿万千]$/, '');
    out.add(raw.replace(/,(?=\d{3}\b)/g, ''));
    const sc = scaled(raw, m[1] ?? ''); if (sc) out.add(sc);
  }
  return out;
}
/** Facts worth checking in the answer (excluding ones already present in the user's message). */
export function claims(answer: string, user = ''): Claim[] {
  const a = canon(answer);
  const userNorm = norm(user);
  const out: Claim[] = [];
  const seen = new Set<string>();
  const add = (kind: Claim['kind'], t: string) => { const k = kind + ':' + norm(t); if (!seen.has(k) && norm(t) && !(userNorm && userNorm.includes(norm(t)))) { seen.add(k); out.push({ kind, text: t.trim() }); } };
  for (const m of a.matchAll(QUOTED)) { const q = (m.slice(1).find(x => x !== undefined) ?? '').trim(); if (norm(q).length >= 2) add('quote', q); }
  const withoutQuotes = a.replace(QUOTED, ' ');
  for (const m of withoutQuotes.matchAll(DATE)) add('date', m[0]);
  const noDates = withoutQuotes.replace(DATE, ' ').replace(ORDINAL, ' ').replace(/https?:\/\/\S+/g, ' ');
  for (const m of noDates.matchAll(TOKEN)) add('token', m[0]);
  const noTokens = noDates.replace(TOKEN, ' ');
  for (const m of noTokens.matchAll(NUMBER)) {
    const idx = m.index ?? 0;
    const raw = m[0];
    const digits = raw.replace(/\D/g, '');
    const after = noTokens.slice(idx + raw.length, idx + raw.length + 6);
    const before = noTokens.slice(Math.max(0, idx - 3), idx);
    const significant = /[.,]\d/.test(raw) || digits.length >= 3 || !!m[1] || UNIT_AFTER.test(after) || CURRENCY_BEFORE.test(before);
    if (significant) add('number', raw + (UNIT_AFTER.exec(after)?.[0] ?? ''));
  }
  return out;
}
/** Check the answer's facts against evidence texts. Facts the user wrote are known; small bare integers are never required. */
export function ground(answer: string, evidence: readonly string[], user = ''): Grounding {
  const list = claims(answer, user);
  const ev = evidence.map(norm);
  const evNumbers = new Set<string>(); for (const e of evidence) for (const n of numbersIn(e)) evNumbers.add(n);
  for (const n of numbersIn(user)) evNumbers.add(n);
  const evDigits = new Set<string>(); for (const e of [...evidence, user]) for (const d of canon(e).match(/\d+/g) ?? []) evDigits.add(String(Number(d)));
  const unmatched = list.filter(c => {
    if (c.kind === 'number') {
      const m = [...canon(c.text).matchAll(NUMBER)][0];
      if (!m) return false;
      const raw = m[0].replace(/[ \t]*[亿万千]$/, '').replace(/,(?=\d{3}\b)/g, '');
      const sc = scaled(m[0].replace(/[ \t]*[亿万千]$/, ''), m[1] ?? '');
      return !(evNumbers.has(raw) || (sc !== null && evNumbers.has(sc)));
    }
    const n = norm(c.text);
    if (c.kind === 'date') { const groups = (c.text.match(/\d+/g) ?? []).map(x => String(Number(x))); return !(ev.some(e => e.includes(n)) || (groups.length >= 2 && groups.every(g => evDigits.has(g)))); }
    return !ev.some(e => e.includes(n));
  });
  return { claims: list, unmatched };
}

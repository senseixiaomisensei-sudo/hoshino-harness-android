// Shared, process-local state for the WP9-B plugins (hoshino-context, hoshino-turn-policy, hoshino-outcome):
// one settings file, per-session ledgers read by /mobile/context, and the native notice channel.
import { mkdir, readFile, rename, writeFile } from 'node:fs/promises';
import { dirname, join } from 'node:path';
import { newSessionCache, type SessionCache } from './core.js';

export type ContextSettings = {
  /** CX2: `auto` = model summary (compact route, else conversation model) with a model-free fallback; `rules` = never call a model. */
  compaction: { strategy: 'auto' | 'rules'; thresholdRatio: number };
  /** CX5 */
  untrusted: { enabled: boolean; extraTools: string[] };
  /** RT10 */
  /** discuss: `remind` (m2.9.3 default) = hold the first gated call once with a soft reminder, the next one runs; `deny` = pre-m2.9.3 hard refusal (opt-in). */
  intent: { enabled: boolean; model: boolean; timeoutMs: number; discuss: 'remind' | 'deny' };
  /** RT11 */
  turnPolicy: { empty: boolean; language: boolean; readback: boolean; readbackTools: string[] };
  /** RT12 / SB4 */
  guard: { failureWarn: number; failureStop: number; sandboxLoopWarn: number; sandboxLoopStop: number; sandboxBudgetMs: number };
  /** RT16 */
  retry: { enabled: boolean; maxRetries: number; maxWaitMs: number };
  /** RT9 / RT17 */
  outcome: { enabled: boolean; model: boolean; timeoutMs: number; grounding: boolean };
};
export const DEFAULT_SETTINGS: ContextSettings = {
  compaction: { strategy: 'auto', thresholdRatio: 0.7 },
  untrusted: { enabled: true, extraTools: [] },
  intent: { enabled: true, model: true, timeoutMs: 6000, discuss: 'remind' },
  turnPolicy: { empty: true, language: true, readback: true, readbackTools: ['web_render'] },
  guard: { failureWarn: 2, failureStop: 8, sandboxLoopWarn: 3, sandboxLoopStop: 5, sandboxBudgetMs: 30 * 60_000 },
  retry: { enabled: true, maxRetries: 3, maxWaitMs: 30_000 },
  outcome: { enabled: true, model: true, timeoutMs: 10_000, grounding: true },
};
export const settings: ContextSettings = structuredClone(DEFAULT_SETTINGS);
let settingsPath = '';
function merge(target: any, source: any, defaults: any) {
  for (const key of Object.keys(defaults)) {
    const value = source?.[key];
    if (defaults[key] && typeof defaults[key] === 'object' && !Array.isArray(defaults[key])) merge(target[key], value, defaults[key]);
    else if (value !== undefined && typeof value === typeof defaults[key] && Array.isArray(value) === Array.isArray(defaults[key])) target[key] = structuredClone(value);
    else target[key] = structuredClone(defaults[key]);
  }
}
/** Load `<selfHome>/config/context.json`; a broken file keeps defaults (never stops the kernel). */
export async function loadSettings(selfHome: string): Promise<ContextSettings> {
  settingsPath = join(selfHome, 'config', 'context.json');
  let parsed: unknown = {};
  try { parsed = JSON.parse(await readFile(settingsPath, 'utf8')); } catch { /* defaults */ }
  merge(settings, parsed, DEFAULT_SETTINGS);
  if (!['auto', 'rules'].includes(settings.compaction.strategy)) settings.compaction.strategy = 'auto';
  if (!['remind', 'deny'].includes(settings.intent.discuss)) settings.intent.discuss = 'remind';
  settings.compaction.thresholdRatio = Math.min(0.95, Math.max(0.3, settings.compaction.thresholdRatio));
  return settings;
}
export async function saveSettings(next: unknown): Promise<ContextSettings> {
  merge(settings, next, DEFAULT_SETTINGS);
  if (!['remind', 'deny'].includes(settings.intent.discuss)) settings.intent.discuss = 'remind';
  if (settingsPath) {
    await mkdir(dirname(settingsPath), { recursive: true });
    await writeFile(settingsPath + '.tmp', JSON.stringify(settings, null, 2));
    await rename(settingsPath + '.tmp', settingsPath);
  }
  return settings;
}

/** Per-session ledgers, keyed by session id. Bounded: only the most recent sessions are kept. */
export type BudgetLedger = { sandboxMs: number; failures: number; stopped?: string; advice: number };
export type OutcomeRecord = { turn: number; outcome: 'done' | 'partial' | 'not_possible' | 'unknown'; reason: string; by: 'rule' | 'model' | 'none'; subtype?: string; ungrounded?: string[]; at: number };
const MAX_SESSIONS = 64;
function bounded<T>(map: Map<string, T>, id: string, make: () => T): T {
  let value = map.get(id);
  if (!value) { value = make(); map.set(id, value); if (map.size > MAX_SESSIONS) map.delete(map.keys().next().value as string); }
  return value;
}
export const caches = new Map<string, SessionCache>();
export const budgets = new Map<string, BudgetLedger>();
export const outcomes = new Map<string, OutcomeRecord[]>();
export const cacheOf = (id: string) => bounded(caches, id, newSessionCache);
export const budgetOf = (id: string): BudgetLedger => bounded<BudgetLedger>(budgets, id, () => ({ sandboxMs: 0, failures: 0, advice: 0 }));
export const outcomesOf = (id: string) => bounded(outcomes, id, () => [] as OutcomeRecord[]);

type Bridge = { call<T>(method: string, body?: unknown, signal?: AbortSignal): Promise<T> };
/** Fire-and-forget native notice (Kotlin shows it in the conversation). Never throws, never blocks the loop. */
export function notify(bridge: Bridge | undefined, sessionId: string, kind: string, text: string, extra: Record<string, unknown> = {}): void {
  if (!bridge || !sessionId) return;
  void bridge.call('context-notice', { sessionId, kind, text: text.slice(0, 1200), ...extra }, AbortSignal.timeout(10_000)).catch(() => undefined);
}

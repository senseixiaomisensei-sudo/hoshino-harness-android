// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// WP9-B loop-quality plugin over original dsh seams (no fork):
//  RT10 intent gate (tools/pre-execute, rules first, light model only when undecided)
//  RT11 pre-answer reminders (agent/turn-stopping → agent.steer, each kind at most once per turn)
//  RT12 stuck guard (failure ladder, sandbox loop, give-up advice; dsh repeat-tool-reminder covers identical calls)
//  SB4  sandbox runtime budget (tools/execute deadline + pre-execute refusal)
//  RT16 transient retry before the fallback route, readable failure reasons
//  CX6  step/progress runtime notices (dsh time-context supplies the clock)
import type { Context } from '../../node-host/src/platform.js';
import { createUserMessage, BlockAssembler } from '@deepseek-ai/dsh-llm';
import type { ContextFormed } from '@deepseek-ai/dsh-llm';
import type {} from '@deepseek-ai/dsh-tools';
import type {} from '@deepseek-ai/dsh-agent';
import type {} from '../hoshino-context/index.js';
import { mismatch } from './language.js';
import { ruleIntent, parseIntent, LOOKUP_TOOLS, INTENT_PROMPT, DISCUSS_DENIAL, NUDGE, needsReadback, READBACK_TOOLS, SANDBOX_TOOLS, ADVICE, climb, signature, classify, isTransient, userReason, detail, type Intent } from './rules.js';

declare module '@deepseek-ai/dsh-llm' {
  interface MessageSourceMap {
    'hoshino-turn-policy': { kind: 'hoshino-turn-policy' } & ContextFormed;
    'hoshino-guard': { kind: 'hoshino-guard' } & ContextFormed;
  }
}
export const name = 'hoshino-turn-policy';
export const inject = ['androidBridge', 'agents', 'llm', 'hoshinoContext'];

const text = (content: readonly any[] = []) => content.filter(b => b?.type === 'text').map(b => String(b.text ?? '')).join('');
const notice = (kind: 'hoshino-turn-policy' | 'hoshino-guard', summary: string, body: string) =>
  createUserMessage({ content: [{ type: 'text', text: body }], source: { kind, form: 'notice', summary: summary.slice(0, 120) } as any });
const sleep = (ms: number, signal: AbortSignal) => new Promise<void>((resolve, reject) => {
  if (signal.aborted) { reject(signal.reason); return; }
  const timer = setTimeout(() => { signal.removeEventListener('abort', abort); resolve(); }, ms);
  const abort = () => { clearTimeout(timer); reject(signal.reason); };
  signal.addEventListener('abort', abort, { once: true });
});

/** Events of the session's open (or latest) turn, oldest first. */
export function turnEvents(session: any): any[] {
  const out: any[] = [];
  for (let seq = session.seq - 1; seq >= 0; seq--) {
    const event = session.eventAt(seq);
    out.push(event);
    if (event.type === 'turn/start') break;
  }
  return out.reverse();
}
/** The human message that opened the turn (null for scheduled/plugin-originated turns). */
function humanText(events: any[]): string | null {
  const users = events.filter(e => e.type === 'user/message' && e.data?.source?.kind === 'user');
  return users.length ? text(users.at(-1).data.content) : null;
}

type TurnState = { turn: number; nudges: Set<string>; intent?: Intent | null; intentDone?: boolean; failures: number; failureRung: number; warnings: number; lastSig?: string; sigRepeats: number; sandboxSig?: string; sandboxRepeats: number; sandboxRung: number; stopped?: string; sandboxMs: number; retries: number; retryWaitMs: number };

export function apply(ctx: Context) {
  const { settings, budgetOf, notify } = ctx.hoshinoContext;
  const states = new Map<string, TurnState>();
  const stateOf = (agent: any, turn: number): TurnState => {
    let s = states.get(agent.id);
    if (!s || s.turn !== turn) {
      s = { turn, nudges: new Set(), failures: 0, failureRung: 0, warnings: 0, sigRepeats: 0, sandboxRepeats: 0, sandboxRung: 0, sandboxMs: 0, retries: 0, retryWaitMs: 0 };
      states.set(agent.id, s);
      if (states.size > 128) states.delete(states.keys().next().value as string);
    }
    return s;
  };
  const currentTurn = (agent: any) => { for (let seq = agent.session.seq - 1; seq >= 0; seq--) { const e = agent.session.eventAt(seq); if (e.type === 'turn/start') return e.data.turn as number; } return 0; };
  ctx.on('agent/disposed', ({ agent }: any) => { states.delete(agent.id); });

  // ---------------------------------------------------------------- light model calls (intent)
  async function lightModel(agent: any, system: string, user: string, timeoutMs: number, signal: AbortSignal): Promise<string | null> {
    const route = ctx.get('mobileM2')?.config.get('routing.json')?.compact;
    const header = agent.session.requestHeader?.()?.config;
    const target = route?.providerId && route?.modelId ? { provider: String(route.providerId), model: String(route.modelId) } : header?.provider ? { provider: header.provider, model: header.model } : agent.options?.provider ? { provider: agent.options.provider, model: agent.options.model } : undefined;
    if (!target) return null;
    const stop = AbortSignal.any([signal, AbortSignal.timeout(timeoutMs)]);
    try {
      const assembler = new BlockAssembler();
      for await (const chunk of ctx.llm.stream({ ...target, system, messages: [{ role: 'user', content: [{ type: 'text', text: user }] }] as any, maxTokens: 512, signal: stop, sessionId: agent.session.id } as any)) assembler.push(chunk as any);
      return text(assembler.blocks() as any[]);
    } catch { return null; }
  }

  // ---------------------------------------------------------------- RT10 + RT12 + SB4: pre-execute (prepended: runs before approval prompts)
  ctx.on('tools/pre-execute', async (exec: any, next: () => Promise<any>) => {
    const agent = exec.agent;
    if (!agent || exec.parent) return next();
    const s = stateOf(agent, currentTurn(agent));
    if (s.stopped && exec.name !== 'end_not_done') return { kind: 'deny', reason: ADVICE.stopped(s.stopped), info: { name: 'HoshinoGuard', code: 'GUARD_STOPPED' } };
    if (SANDBOX_TOOLS.has(exec.name) && s.sandboxMs >= settings.guard.sandboxBudgetMs)
      return { kind: 'deny', reason: ADVICE.budget(Math.round(settings.guard.sandboxBudgetMs / 60000)), info: { name: 'HoshinoBudget', code: 'SANDBOX_BUDGET_EXHAUSTED' } };
    if (SANDBOX_TOOLS.has(exec.name) && s.sandboxRepeats >= settings.guard.sandboxLoopStop && s.sandboxSig === signature(exec.name, exec.arguments))
      return { kind: 'deny', reason: ADVICE.sandboxLoop, info: { name: 'HoshinoGuard', code: 'SANDBOX_LOOP' } };
    if (settings.intent.enabled && !LOOKUP_TOOLS.has(exec.name)) {
      if (!s.intentDone) {
        s.intentDone = true;
        const events = turnEvents(agent.session);
        const human = humanText(events);
        // Only a person's message is judged; scheduled or plugin-started turns follow the model.
        s.intent = human === null ? null : ruleIntent(human);
        if (human !== null && s.intent === null && settings.intent.model) {
          const raw = await lightModel(agent, INTENT_PROMPT, `The user's current message:\n${human}`, settings.intent.timeoutMs, exec.signal);
          s.intent = raw === null ? null : parseIntent(raw);
        }
      }
      if (s.intent === 'DISCUSS') return { kind: 'deny', reason: DISCUSS_DENIAL, info: { name: 'HoshinoIntent', code: 'DISCUSS_INTENT' } };
    }
    return next();
  }, { prepend: true } as any);

  // SB4: cap a sandbox command to the task's remaining budget.
  ctx.on('tools/execute', async (exec: any, next: () => Promise<any>) => {
    if (!SANDBOX_TOOLS.has(exec.name) || !exec.agent || exec.parent) return next();
    const s = stateOf(exec.agent, currentTurn(exec.agent));
    const left = Math.max(1000, settings.guard.sandboxBudgetMs - s.sandboxMs);
    const timer = AbortSignal.timeout(left);
    const upstream = exec.signal;
    exec.signal = AbortSignal.any([upstream, timer]);
    const started = Date.now();
    try {
      const result = await next();
      if (timer.aborted && !upstream.aborted) {
        const message = ADVICE.budget(Math.round(settings.guard.sandboxBudgetMs / 60000));
        return { content: [{ type: 'text', text: `Error: ${message}` }], isError: true, error: { message, info: { name: 'HoshinoBudget', code: 'SANDBOX_BUDGET_EXHAUSTED' } } };
      }
      return result;
    } finally {
      exec.signal = upstream;
      s.sandboxMs += Date.now() - started;
      const b = budgetOf(exec.agent.id); b.sandboxMs += Date.now() - started;
    }
  });

  // RT12: failure ladder, sandbox loop and give-up advice, delivered append-only after the result.
  ctx.on('tools/post-execute', async (exec: any, result: any, next: () => Promise<any>) => {
    const decision = await next();
    const agent = exec.agent;
    if (!agent || exec.parent || exec.name === 'end_not_done') return decision;
    const s = stateOf(agent, currentTurn(agent));
    const failed = result.isError === true && !['DISCUSS_INTENT', 'GUARD_STOPPED'].includes(result.error?.info?.code);
    const advice: string[] = [];
    const sig = signature(exec.name, exec.arguments);
    if (failed) {
      s.failures++; budgetOf(agent.id).failures++;
      if (s.failures >= settings.guard.failureStop) { s.stopped ??= 'too_many_failures'; advice.push(ADVICE.stopped('too_many_failures')); }
      else {
        const ladder = [settings.guard.failureWarn, settings.guard.failureWarn * 2];
        const rung = climb(s.failures, ladder, s.failureRung);
        if (rung !== null) { s.failureRung = rung; s.warnings++; advice.push(rung >= ladder[1]! ? ADVICE.failuresWaysOut(s.failures) : ADVICE.failures(s.failures)); }
      }
      if (SANDBOX_TOOLS.has(exec.name)) {
        s.sandboxRepeats = s.sandboxSig === sig ? s.sandboxRepeats + 1 : 1; s.sandboxSig = sig;
        if (s.sandboxRepeats >= settings.guard.sandboxLoopWarn && s.sandboxRepeats > s.sandboxRung) { s.sandboxRung = s.sandboxRepeats; s.warnings++; advice.push(ADVICE.sandboxLoop); }
        if (s.sandboxRepeats >= settings.guard.sandboxLoopStop) { s.stopped ??= 'sandbox_loop'; }
      }
    } else if (result.isError !== true) {
      s.failures = 0; s.failureRung = 0;
      if (SANDBOX_TOOLS.has(exec.name)) { s.sandboxRepeats = 0; s.sandboxSig = undefined; s.sandboxRung = 0; }
    }
    // Identical repeats are reminded by dsh repeat-tool-reminder; we only count them toward the give-up advice.
    s.sigRepeats = s.lastSig === sig ? s.sigRepeats + 1 : 1; s.lastSig = sig;
    if (s.sigRepeats === 3 || s.sigRepeats === 6) s.warnings++;
    if (s.warnings >= 2 && (failed || s.sigRepeats >= 3) && !s.nudges.has('give-up')) { s.nudges.add('give-up'); advice.push(ADVICE.giveUp); budgetOf(agent.id).advice++; }
    if (s.stopped && !s.nudges.has('stopped')) {
      s.nudges.add('stopped'); budgetOf(agent.id).stopped = s.stopped;
      notify(ctx.androidBridge, agent.id, 'guard', s.stopped === 'sandbox_loop' ? '已安全停止：同一条命令原样重复仍然失败，本轮不再执行工具。' : '已安全停止：操作连续失败，本轮不再执行工具。');
    }
    if (!advice.length || decision?.kind === undefined) return decision;
    const ctxMessage = notice('hoshino-guard', advice.length > 1 ? 'guard advice' : advice[0]!.slice(0, 80), [...new Set(advice)].join('\n\n'));
    return { ...decision, additionalContexts: [ctxMessage, ...(decision.additionalContexts ?? [])] };
  });

  // ---------------------------------------------------------------- CX6: step/progress notice at the step boundary (append-only).
  ctx.on('agent/pre-step', async (payload: any, next: () => Promise<any>) => {
    const decision = await next();
    if (decision?.kind !== 'enter' || payload.step < 2) return decision;
    const mode = ctx.get('mobileM2') ? (() => { try { const o = (ctx as any).mobilePolicy?.options?.get(payload.agent.id); const modes = ctx.get('mobileM2')!.config.get('modes.json').modes ?? []; return modes.find((m: any) => m.id === o?.mode); } catch { return undefined; } })() : undefined;
    const max = typeof mode?.maxSteps === 'number' ? mode.maxSteps : undefined;
    const used = payload.step - 1;
    if (max !== undefined && max >= 10 && [0.5, 0.75].some(r => Math.floor(max * r) === used))
      return { ...decision, messages: [...decision.messages, notice('hoshino-turn-policy', `step check ${used}/${max}`, NUDGE.progress(used, max))] };
    return decision;
  });

  // ---------------------------------------------------------------- RT11: reminders before the answer goes to the user.
  ctx.on('agent/turn-stopping', async ({ agent, turn, signal }: any) => {
    if (signal.aborted) return;
    const s = stateOf(agent, turn);
    if (s.nudges.size >= 3 || s.stopped) return;
    const events = turnEvents(agent.session);
    const assistants = events.filter(e => e.type === 'assistant/message');
    const last = assistants.at(-1);
    if (!last) return;
    const content = last.data.message.content as any[];
    const answer = text(content).trim();
    const calls = content.filter(b => b.type === 'tool-call');
    if (calls.length) return;
    const steer = (kind: string, summary: string, body: string) => { s.nudges.add(kind); agent.steer(notice('hoshino-turn-policy', summary, body)); };
    if (!answer) {
      if (!settings.turnPolicy.empty || s.nudges.has('empty')) return;
      const finish = [...(last.data.stream ?? [])].reverse().map((r: any) => r?.chunk ?? r).find((c: any) => c?.type === 'finish');
      steer('empty', finish?.reason?.kind === 'max-tokens' ? 'reply truncated' : 'empty reply', finish?.reason?.kind === 'max-tokens' ? NUDGE.truncated : NUDGE.empty);
      return;
    }
    const human = humanText(events);
    if (settings.turnPolicy.language && human && !s.nudges.has('language')) {
      const m = mismatch(human, answer);
      if (m) { steer('language', 'answer language', NUDGE.language(m.answer, m.user)); return; }
    }
    const toolCalls = events.filter(e => e.type === 'tool/call');
    const results = new Map(events.filter(e => e.type === 'tool/result').map(e => [e.data.message.toolCallId, e.data.message]));
    const lastCall = toolCalls.at(-1);
    if (settings.turnPolicy.readback && lastCall && !s.nudges.has('readback')) {
      const result = results.get(lastCall.data.callId);
      if (result && !result.isError && needsReadback(lastCall.data.name, lastCall.data.arguments, settings.turnPolicy.readbackTools)) {
        steer('readback', `not read back: ${lastCall.data.name}`, NUDGE.readback(lastCall.data.name)); return;
      }
      if (result && !result.isError && lastCall.data.name === 'ask_user' && !s.nudges.has('followup')) {
        let question = ''; try { question = String(JSON.parse(lastCall.data.arguments).question ?? '').slice(0, 120); } catch { /* raw */ }
        // Only when the answer reads like a promise to act ("I'll ..."), not a direct reply.
        if (/(我(会|将|这就|马上|现在)|接下来|稍后|I'?ll |I will |let me )/i.test(answer)) { steer('followup', 'answered, not carried out', NUDGE.followUp(`the user answered your question 「${question}」`)); return; }
      }
    }
  });

  // ---------------------------------------------------------------- RT16: retry transient failures briefly, then let the fallback route take over.
  ctx.on('agent/request-error', async (payload: any, next: () => Promise<any>) => {
    const { agent, failure, signal } = payload;
    if (signal.aborted) return next();
    // RT11 (empty reply): dsh reports a completed response with no content as EMPTY_RESPONSE before any assistant message
    // exists, so agent/turn-stopping never sees it. Append the reminder to the surface and retry the request once; the
    // loop rebuilds the request from the surface on retry. A second empty reply ends the turn with the original error.
    if (failure?.code === 'EMPTY_RESPONSE' && settings.turnPolicy.empty) {
      const s = stateOf(agent, payload.turn);
      if (s.nudges.has('empty') || s.stopped) return next();
      s.nudges.add('empty');
      try { agent.session.append('user/message', notice('hoshino-turn-policy', 'empty reply', NUDGE.empty), { surfaceOp: 'append' }); } catch { return next(); }
      return { kind: 'retry' };
    }
    if (!settings.retry.enabled) return next();
    const kind = classify(failure);
    if (!isTransient(kind)) return next();
    const s = stateOf(agent, payload.turn);
    if (s.retries >= settings.retry.maxRetries || s.retryWaitMs >= settings.retry.maxWaitMs) return next();
    const wait = Math.min(failure.providerRetryAfterMs ?? 1000 * 2 ** s.retries, settings.retry.maxWaitMs - s.retryWaitMs, 20_000);
    s.retries++; s.retryWaitMs += wait;
    try { await sleep(Math.max(0, wait), signal); } catch { return next(); }
    return { kind: 'retry' };
  }, { prepend: true } as any);
  ctx.on('session/event', (session: any, event: any) => {
    if (event.type !== 'turn/end' || event.data?.reason?.kind !== 'error') return;
    const failure = event.data.reason.error ?? {};
    const kind = classify(failure);
    const model = session.requestHeader?.()?.config?.model ?? '';
    notify(ctx.androidBridge, session.id, 'failure', `怎么修：${userReason(kind, model, detail(String(failure.message ?? '')))}`, { failureKind: kind });
  });
}

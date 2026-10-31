// Pure orchestration of phone steps and one-shot approvals; no Cordis imports so it can be unit-tested.
export type Data = Record<string, any>;
export type Call = (method: string, body: Data, signal?: AbortSignal) => Promise<Data>;
export type Approve = (action: Data, signal?: AbortSignal) => Promise<{approved: boolean; remember?: boolean}>;

export const STEP_KINDS = ['tap', 'long_press', 'type', 'clear', 'scroll', 'swipe', 'back', 'key', 'open_app', 'wait'] as const;
export const MAX_STEPS = 20;

/** Kernel-side validation mirrors PhoneStep.parse on the Android side; Android validates again. */
export function checkSteps(steps: unknown): Data[] {
  if (!Array.isArray(steps) || steps.length < 1 || steps.length > MAX_STEPS) throw new Error(`steps must contain 1..${MAX_STEPS} actions`);
  return steps.map((s, i) => {
    if (!s || typeof s !== 'object' || Array.isArray(s)) throw new Error(`step ${i + 1}: must be an object`);
    const kind = String((s as Data).action ?? (s as Data).kind ?? '');
    if (!(STEP_KINDS as readonly string[]).includes(kind)) throw new Error(`step ${i + 1}: unknown action '${kind}'`);
    return {...(s as Data), action: kind};
  });
}

/**
 * Runs steps on the phone. When Android stops at an irreversible step (send / delete / order / submit) it
 * returns `pending` with a fingerprint of that step on that screen. We ask the existing approval policy once
 * (never remembered for the session), grant exactly that fingerprint, and continue from that step. A pay step
 * is never approved here: payment is always left to the user (phone_handoff). A declined step stops the run.
 */
export async function runSteps(call: Call, approve: Approve, sessionId: string, steps: Data[], appLabel?: string, signal?: AbortSignal): Promise<Data> {
  let remaining = steps;
  let offset = 0;
  const results: Data[] = [];
  let approvals = 0;
  while (true) {
    const r = await call('phone', {action: 'steps', sessionId, steps: remaining, ...(appLabel ? {app_label: appLabel} : {})}, signal);
    if (r.ok === false && r.error && !r.results) throw new Error(String(r.error) + (r.setup ? ` (${r.setup})` : ''));
    for (const x of (r.results ?? []) as Data[]) results.push({...x, index: Number(x.index ?? 0) + offset});
    const pending = r.pending as Data | undefined;
    if (!pending) return {...r, results, ...(r.stopped_at !== undefined ? {stopped_at: Number(r.stopped_at) + offset} : {})};
    const at = offset + Number(pending.index ?? 0);
    if (pending.action === 'pay') return {...r, results, stopped_at: at, reason: 'payment', note: 'Payment is always done by the user. Use phone_handoff so the user pays with their own hands.'};
    if (++approvals > steps.length) throw new Error('too many approval rounds');
    const answer = await approve({
      kind: 'tool', sessionId, tool: 'phone_irreversible',
      command: `${pending.action}: ${pending.step}${pending.app ? ` @ ${pending.app}` : ''}`,
      summary: `手机不可逆操作（${pending.action}）：${pending.matched}`,
      arguments: {step: pending.step, action: pending.action, matched: pending.matched, app: pending.app ?? null, once: true},
    }, signal);
    if (!answer.approved) return {...r, results, stopped_at: at, reason: 'declined', note: 'The user declined this irreversible step; do not retry it.'};
    await call('phone', {action: 'grant', sessionId, fingerprint: String(pending.fingerprint)}, signal);
    offset = at;
    remaining = remaining.slice(Number(pending.index ?? 0));
  }
}

/** Single-action tool arguments → one step. */
export function stepFor(action: string, args: Data): Data {
  const pick = (...keys: string[]) => Object.fromEntries(keys.filter(k => args[k] !== undefined && args[k] !== null).map(k => [k, args[k]]));
  switch (action) {
    case 'tap': case 'long_press': return {action, ...pick('ref', 'target', 'x', 'y', 'expect')};
    case 'type': return {action, ...pick('ref', 'target', 'text', 'enter', 'expect')};
    case 'clear': return {action, ...pick('ref', 'target')};
    case 'scroll': return {action, ...pick('ref', 'target', 'direction', 'expect')};
    case 'swipe': return {action, ...pick('x', 'y', 'x2', 'y2', 'duration_ms', 'expect')};
    case 'back': return {action, ...pick('expect')};
    case 'key': return {action, ...pick('key', 'expect')};
    case 'open_app': return {action, app: args.app, ...pick('expect')};
    case 'wait': return {action, expect: args.expect};
    default: throw new Error(`unknown action ${action}`);
  }
}

/** Compact text for the model: the screen text as-is, the rest as one JSON line. */
export function render(r: Data): string {
  const {screen, ...rest} = r;
  return JSON.stringify(rest) + (screen ? '\n' + String(screen) : '');
}

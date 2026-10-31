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
    if (r.ok === false && r.error && !r.results) throw new Error(phoneError(r));
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

/** Android `code` values that mean phone control itself is not set up (PhoneController.requireReady). */
export const SETUP_CODES: ReadonlySet<string> = new Set(['helper', 'accessibility', 'unsupported']);

/**
 * Readable error for a failed phone call. Setup problems carry the exact place to fix them and say plainly
 * that Shizuku / full-access is not needed, so the model never sends the user off to configure Shizuku (m2.8.1).
 */
export function phoneError(r: Data): string {
  const what = String(r.error ?? 'phone action failed');
  const code = r.code ? String(r.code) : '';
  if (!SETUP_CODES.has(code) && !r.setup) return what;
  const where = String(r.setup ?? '设置 → 设置手机操作');
  if (!SETUP_CODES.has(code)) return `${what}${code ? ` [${code}]` : ''} (${where})`;
  return `Phone control is not ready: ${what} [${code}]. Ask the user to open ${where} and finish it (built-in wireless-debugging helper + Hoshino accessibility service); call phone_status for details. ` +
    'Shizuku and full-access are NOT required for opening or operating apps; do not ask the user to install or configure Shizuku.';
}

/** Single-action tool arguments → one step. */
export function stepFor(action: string, args: Data): Data {
  const pick = (...keys: string[]) => Object.fromEntries(keys.filter(k => args[k] !== undefined && args[k] !== null).map(k => [k, args[k]]));
  switch (action) {
    case 'tap': case 'long_press': return {action, ...pick('ref', 'target', 'x', 'y', 'expect')};
    case 'type': return {action, ...pick('ref', 'target', 'x', 'y', 'text', 'enter', 'expect')};
    case 'clear': return {action, ...pick('ref', 'target', 'x', 'y')};
    case 'scroll': return {action, ...pick('ref', 'target', 'direction', 'expect')};
    case 'swipe': return {action, ...pick('x', 'y', 'x2', 'y2', 'duration_ms', 'expect')};
    case 'back': return {action, ...pick('expect')};
    case 'key': return {action, ...pick('key', 'expect')};
    case 'open_app': return {action, app: args.app, ...pick('cold', 'expect')};
    case 'wait': return {action, expect: args.expect};
    default: throw new Error(`unknown action ${action}`);
  }
}

/** Compact text for the model: the screen text as-is, the rest as one JSON line. */
export function render(r: Data): string {
  const {screen, ...rest} = r;
  return JSON.stringify(rest) + (screen ? '\n' + String(screen) : '');
}

/** System prompt for phone control. Exported so the wording the model sees is covered by tests. */
export const PHONE_PROMPT = [
  'Phone control: to open or operate any other app (e.g. "打开抖音") use the phone_* tools: phone_launch_app to open it, phone_observe to read, phone_do_steps / phone_tap / phone_type_text to act. They run apps on a background agent display while the user keeps using the main screen, and they need no Shizuku, no system_shell and no full-access session.',
  'Every app operation happens ONLY on that background virtual display, concurrently with the user\'s main screen, and keeps running while Hoshino itself is in the background. Never touch the user\'s main screen: do not use system_shell (monkey, am start, cmd activity start, input, cmd statusbar) to open or operate apps; such commands are refused.',
  'The virtual display is created only while a phone task runs. Not seeing an extra display (dumpsys display, a display list) does NOT mean the feature is missing: just call phone_launch_app.',
  'Start with phone_status if unsure. If phone control is not set up (helper not running, accessibility off), call phone_status and tell the user to open 设置 → 设置手机操作 to pair the built-in wireless-debugging helper and enable the accessibility service. Never tell the user to install or configure Shizuku for this, and never ask for full-access to open an app.',
  'Refs from phone_observe stay valid across reads; prefer phone_do_steps with expect conditions for known sequences.',
  'To read, prefer one call over scroll+observe loops: phone_collect reads a whole list or chat (scrolls and dedups on the phone), phone_find scrolls to a target, phone_query / phone_extract read the current screen.',
  'phone_type_text: give ref/target, or x/y to tap a field that is not in the element list (self-drawn input) and type there. Errors: no_focus = no input field holds focus (tap the field first); point_not_typed = nothing at that point took the text; needs_ime = the text could not be entered without touching the user\'s clipboard, try smaller parts or hand it to the user. Never copy text to the clipboard to read or move it.',
  'Sending, deleting, ordering and submitting go through the user\'s approval policy for that exact step on that exact screen (by default the user is asked once per step; a declined step must not be retried). Payment, passwords, captchas and verification codes are always handed to the user with phone_handoff, in every approval mode including no-approval. Never operate Hoshino itself. Call phone_finish when done.',
].join('\n');

/** Plain-language setup steps for each missing piece reported by the Android `diagnose` action. */
export function phoneSetupSteps(r: Data): string[] {
  const steps: string[] = [];
  if (r.supported === false) return ['This phone runs Android ' + String(r.sdk ?? '?') + ' (API level); phone control needs Android 13 or newer. Tell the user it is not available on this device.'];
  if (r.helper === false) steps.push('Phone helper not running: ask the user to open 设置 → 设置手机操作 and pair the built-in wireless-debugging helper (stay on Wi-Fi, wireless debugging on). Shizuku is NOT needed.');
  if (r.accessibility === false) steps.push('Accessibility off: ask the user to enable the 「Hoshino 手机操作」 accessibility service in 设置 → 设置手机操作.');
  if (r.locked === true) steps.push('The phone is locked: actions wait until the user unlocks it.');
  return steps;
}

/** phone_status output: the Android report plus, when not ready, clear setup guidance and what to do next. */
export function phoneStatusText(r: Data): string {
  const steps = r.ready === false || r.locked === true ? phoneSetupSteps(r) : [];
  if (!steps.length) return render({...r, next: r.ready === false ? 'Phone control is not ready; ask the user to open 设置 → 设置手机操作.' : 'Ready. Open apps with phone_launch_app (background virtual display, created on demand).'});
  return render({...r, setup: '设置 → 设置手机操作', next: steps});
}

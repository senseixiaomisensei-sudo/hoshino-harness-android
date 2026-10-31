import { defineTool } from '@deepseek-ai/dsh-tools';
import type { ImageAttachmentRef } from '@deepseek-ai/dsh-attachment';
import type { Context } from '../../node-host/src/platform.js';
import type {} from '../android-policy/index.js';
import { checkSteps, phoneError, render, runSteps, stepFor, PHONE_PROMPT, type Data } from './steps.js';

/**
 * Phone control tools (m2.8). Every tool is a thin client of the Android single execution entry
 * (PhoneController, bridge method `phone`). Approval: all phone_* tools go through android-policy's
 * existing approval seam (default manual) and share one "allow for this session" decision; irreversible steps
 * additionally need a one-shot approval bound to that step on that screen (see steps.ts).
 * Approval modes (hoshino-m2 resolveApproval): irreversible steps count as high risk, so with 高危确认 on they are asked
 * per step even in auto mode; in the user-enabled no-approval mode they proceed and are audited (approver=auto-none,
 * risk=irreversible:<kind>). Payment and password/captcha/verification-code screens never reach the approval seam:
 * they stop on the Android side and go to phone_handoff in every mode (docs/decisions.md 2026-10-31 M2.8).
 */
export const name = 'android-phone';
export const inject = ['tools', 'androidBridge', 'attachments', 'llm', 'systemPrompt', 'agents'];

const target = {ref: {type: 'string', description: 'Element ref from the last phone_observe (e.g. e12)'}, target: {type: 'string', description: 'Visible text of the element'}, x: {type: 'number', description: '0..999 from left'}, y: {type: 'number', description: '0..999 from top'}};
const expect = {expect: {type: 'object', description: 'Wait by code after the action: {text?, gone?, app?, quiet?, timeout_ms?}'}};

export function apply(ctx: Context): void {
  const call = (method: string, body: Data, signal?: AbortSignal) => ctx.androidBridge.call<Data>(method, body, signal);
  const approve = (action: Data, signal?: AbortSignal) => ctx.androidBridge.call<{approved: boolean; remember?: boolean}>('approve', action, signal);
  const sid = (exec: {agent?: {id: string}}) => exec.agent?.id ?? '';
  const phone = async (action: string, args: Data, exec: {agent?: {id: string}; signal: AbortSignal}) => {
    const r = await call('phone', {action, sessionId: sid(exec), ...args}, exec.signal);
    if (r.ok === false && r.error && action !== 'handoff') throw new Error(phoneError(r));
    return render(r);
  };
  const text = (name: string, description: string, parameters: Data, run: (args: Data, exec: any) => Promise<string>) =>
    ctx.tools.register(defineTool({name, description, parameters: parameters as any, output: {schema: {type: 'string'}, render: (_, value) => [{type: 'text', text: value}]}, execute: run as any}));

  text('phone_status', 'Phone control readiness and diagnosis (helper, accessibility, OCR, Android version, lock state). No screen content. Call it first when a phone_* tool reports that phone control is not ready.', {}, (_, exec) => phone('diagnose', {}, exec));
  text('phone_observe', 'Read the agent display (a background screen the user does not see): compressed elements with stable refs, or only what changed since your last read. Set full=true for the whole screen.', {full: {type: 'boolean'}}, (a, exec) => phone('observe', {full: a.full === true}, exec));
  text('phone_list_apps', 'List launcher apps (label and package).', {query: {type: 'string'}}, (a, exec) => phone('list_apps', {query: a.query ?? ''}, exec));
  text('phone_handoff', 'Hand the current app to the user (login, captcha, payment, password, anything you must not do). Waits until the user taps 处理好了 or 跳过; skipped means NOT done.', {reason: {type: 'string', required: true}}, (a, exec) => phone('handoff', {reason: String(a.reason ?? '')}, exec));
  text('phone_finish', 'Release the agent display when the phone task is finished (restores muted apps).', {}, (_, exec) => phone('release', {}, exec));

  const single: Array<[string, string, string, Data]> = [
    ['phone_tap', 'tap', 'Tap an element (ref or visible text) or a 0..999 point on the agent display.', {...target, ...expect}],
    ['phone_long_press', 'long_press', 'Long-press an element or point.', {...target, ...expect}],
    ['phone_type_text', 'type', 'Type text into a field (ref/target, else the focused field). Replaces its content. enter=true submits.', {...target, text: {type: 'string', required: true}, enter: {type: 'boolean'}, ...expect}],
    ['phone_clear_text', 'clear', 'Clear a text field.', {...target}],
    ['phone_scroll', 'scroll', 'Scroll a list (ref) or the screen: direction up/down/left/right.', {...target, direction: {type: 'string', required: true}, ...expect}],
    ['phone_swipe', 'swipe', 'Straight swipe from x,y to x2,y2 (0..999).', {x: {type: 'number', required: true}, y: {type: 'number', required: true}, x2: {type: 'number', required: true}, y2: {type: 'number', required: true}, duration_ms: {type: 'number'}, ...expect}],
    ['phone_back', 'back', 'Press Back on the agent display.', {...expect}],
    ['phone_press_key', 'key', 'Press a navigation key: enter, tab, del, search, up, down, left, right, page_up, page_down, escape, move_home, move_end, space, forward_del, center, back.', {key: {type: 'string', required: true}, ...expect}],
    ['phone_launch_app', 'open_app', 'Open an app (e.g. 抖音, WeChat) on the agent display by package or label (never Hoshino itself). This is how to open apps; it needs no Shizuku and no full-access.', {app: {type: 'string', required: true}, ...expect}],
    ['phone_wait', 'wait', 'Wait until text appears/disappears, an app is in front, or the screen is quiet.', {expect: {type: 'object', required: true}}],
  ];
  for (const [tool, action, description, parameters] of single) text(tool, description, parameters, async (a, exec) => render(await runSteps(call, approve, sid(exec), checkSteps([stepFor(action, a)]), undefined, exec.signal)));
  text('phone_do_steps', 'Run 1-20 known actions in a row without a round-trip per step; each step: {action: tap|long_press|type|clear|scroll|swipe|back|key|open_app|wait, ref?, target?, x?, y?, text?, enter?, direction?, key?, app?, expect?}. Stops early on choosers, screens that need the user, missing targets, or an irreversible step that needs approval.',
    {steps: {type: 'array', required: true, items: {type: 'object'}}, app_label: {type: 'string'}},
    async (a, exec) => render(await runSteps(call, approve, sid(exec), checkSteps(a.steps), a.app_label ? String(a.app_label) : undefined, exec.signal)));

  ctx.tools.register(defineTool({name: 'phone_screenshot', description: 'Picture of the agent display (requires an image-capable model). Secure screens cannot be captured.', parameters: {} as any,
    output: {schema: {type: 'string'}, render: (_, value) => [{type: 'image', attachment: JSON.parse(String(value)) as ImageAttachmentRef}]},
    async execute(_, exec) {
      const route = exec.agent?.session.requestHeader()?.config; const provider = route?.provider ?? exec.agent?.options.provider; const model = route?.model ?? exec.agent?.options.model;
      if (!provider || !model || !(await ctx.llm.resolveModelInfo(provider, model, exec.signal)).inputModalities?.includes('image')) throw new Error('Current model does not declare image input; use phone_observe');
      const image = await call('phone', {action: 'screenshot', sessionId: sid(exec)}, exec.signal);
      if (image.ok === false) throw new Error(phoneError({error: 'screenshot failed', ...image}));
      return JSON.stringify(await ctx.attachments.saveImage({data: Buffer.from(String(image.data), 'base64'), mediaType: 'image/jpeg', name: 'phone-screen.jpg'}));
    },
  }));

  ctx.systemPrompt.context({name: 'hoshino-phone', order: 520, required: false, text: () => PHONE_PROMPT});
}

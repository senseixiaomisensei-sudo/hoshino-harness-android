// m2.9.3 intent gate (RT10) regressions: explicit instructions — including polite questions, app names and interjections —
// are never judged DISCUSS; the verdict follows the newest human message of the turn; non-human messages never count.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { ruleIntent, parseIntent, LOOKUP_TOOLS, DISCUSS_REMINDER, INTENT_PROMPT } from '../dist/plugins/hoshino-turn-policy/rules.js';
import { humanMessages, judgeTurnIntent } from '../dist/plugins/hoshino-turn-policy/intent.js';

const NEVER_DISCUSS = [
  '打开微信', '帮我打开微信看看', '能不能打开微信', '你能帮我在微信里给妈妈发个消息吗', '去微信发个消息',
  '打开微信，万一有新消息就告诉我', '用微信给张三发个消息', '微信里回复一下老王', '进抖音看看', '可不可以帮我打开支付宝',
  '如果微信有红包就帮我点开', '截个图', '往下滑一下', '打开微信。', '帮我打开设置', 'open WeChat', 'Can you open WeChat?',
];

test('m2.9.3 RT10: explicit phone instructions are decided by rules as ACT/READ, never DISCUSS', () => {
  for (const t of NEVER_DISCUSS) {
    const got = ruleIntent(t);
    assert.ok(got === 'ACT' || got === 'READ', `${t} → ${got}`);
  }
  // Unchanged: hypothetical/future questions still go to the model, pure discussion stays DISCUSS.
  assert.equal(ruleIntent('以后可以帮我同步发布，对吧？'), null);
  assert.equal(ruleIntent('假如我要发微信，你会怎么做？'), null);
  assert.equal(ruleIntent('什么是闭包？'), 'DISCUSS');
  assert.equal(ruleIntent('你好'), 'DISCUSS');
  assert.equal(parseIntent('ACT'), 'ACT');
});

test('m2.9.3 RT10: reading phone tools are lookups (never gated)', () => {
  for (const name of ['phone_observe', 'phone_status', 'phone_list_apps', 'phone_screenshot']) assert.ok(LOOKUP_TOOLS.has(name), name);
  assert.ok(!LOOKUP_TOOLS.has('phone_launch_app') && !LOOKUP_TOOLS.has('phone_tap'));
  assert.match(DISCUSS_REMINDER, /call the tool again and it will run/);
  assert.match(INTENT_PROMPT, /「打开微信」/);
});

const msg = (text, kind = 'user', extra = []) => ({ type: 'user/message', data: { source: { kind }, content: [{ type: 'text', text }, ...extra] } });

test('m2.9.3 RT10: only typed human messages are judged (no notices, stop notes, takeover or subagent text)', () => {
  const events = [
    { type: 'turn/start', data: { turn: 3 } },
    msg('[runtime] 如果需要可以以后再做', 'runtime-context'),
    msg('已停止 · 停止前的交代', 'hoshino-stop-note'),
    msg('打开微信', 'user', [{ type: 'file', receiptId: 'r1' }]),
    msg('子任务说明', 'agent-message'),
    { type: 'tool/result', data: { message: { content: [{ type: 'text', text: '用户手动接管了后台屏约 3 秒' }] } } },
  ];
  const humans = humanMessages(events);
  assert.deepEqual(humans.map(h => h.text), ['打开微信']);
});

test('m2.9.3 RT10: verdict over the whole turn — instruction wins, interjection re-judged, model sees context', async () => {
  const noModel = null;
  // Opening instruction, then a greeting interjection: still ACT.
  assert.deepEqual(await judgeTurnIntent([{ index: 1, text: '打开微信' }, { index: 5, text: '谢谢' }], ruleIntent, noModel), { intent: 'ACT', by: 'rule' });
  // A discussion turn that later receives an instruction: ACT (before m2.9.3 the first verdict stuck for the turn).
  assert.deepEqual(await judgeTurnIntent([{ index: 1, text: '什么是闭包？' }, { index: 4, text: '能不能打开微信' }], ruleIntent, noModel), { intent: 'ACT', by: 'rule' });
  // Pure discussion stays DISCUSS by rule.
  assert.deepEqual(await judgeTurnIntent([{ index: 1, text: '为什么天空是蓝的' }], ruleIntent, noModel), { intent: 'DISCUSS', by: 'rule' });
  // Undecided without a model: not gated.
  assert.deepEqual(await judgeTurnIntent([{ index: 1, text: '那就这样吧，按你说的来' }], ruleIntent, noModel), { intent: null, by: 'none' });
  // Undecided with a model: the prompt carries earlier messages and the current one.
  let seen = '';
  const model = async p => { seen = p; return 'ACT'; };
  assert.deepEqual(await judgeTurnIntent([{ index: 1, text: '为什么天空是蓝的' }, { index: 3, text: '那就这样吧，按你说的来' }], ruleIntent, model), { intent: 'ACT', by: 'model' });
  assert.match(seen, /Earlier messages from the user in this task:\n- 为什么天空是蓝的/);
  assert.match(seen, /The user's current message:\n那就这样吧，按你说的来$/);
  assert.deepEqual(await judgeTurnIntent([], ruleIntent, model), { intent: null, by: 'none' });
});

// ---- the gate itself (plugins/hoshino-turn-policy/index.ts apply) on a fake Cordis context
import { apply } from '../dist/plugins/hoshino-turn-policy/index.js';
import { DEFAULT_SETTINGS } from '../dist/plugins/hoshino-context/state.js';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

async function gate({ noApproval = false, discuss = 'remind' } = {}) {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-intent-'));
  const settings = structuredClone(DEFAULT_SETTINGS); settings.intent.model = false; settings.intent.discuss = discuss;
  const handlers = new Map();
  const ctx = {
    hoshinoContext: { settings, budgetOf: () => ({ sandboxMs: 0, failures: 0, advice: 0 }), notify: () => {} },
    on: (name, fn) => handlers.set(name, fn),
    get: name => name === 'mobileM2' ? { noApproval: () => noApproval, config: { root, get: () => ({}) } } : undefined,
  };
  apply(ctx);
  const events = [{ type: 'turn/start', data: { turn: 1 } }];
  const agent = { id: 'S1', session: { get seq() { return events.length; }, eventAt: i => events[i] } };
  const pre = handlers.get('tools/pre-execute');
  const call = async name => { let ran = false; const out = await pre({ agent, name, arguments: '{}', signal: new AbortController().signal }, async () => { ran = true; return { kind: 'allow' }; }); return ran ? 'ran' : out; };
  const say = text => events.push(msg(text));
  const audit = async () => { await new Promise(r => setTimeout(r, 50)); return (await readFile(join(root, 'audit', 'intent.jsonl'), 'utf8').catch(() => '')).trim().split('\n').filter(Boolean).map(l => JSON.parse(l)); };
  return { call, say, audit, done: () => rm(root, { recursive: true, force: true }) };
}

test('m2.9.3 RT10 gate: DISCUSS gets one soft reminder, the next gated call runs; both audited without user words', async () => {
  const g = await gate();
  g.say('什么是闭包？');
  const first = await g.call('write');
  assert.equal(first.kind, 'deny'); assert.equal(first.info.code, 'DISCUSS_REMINDER'); assert.match(first.reason, /call the tool again/);
  assert.equal(await g.call('read'), 'ran', 'lookups are never gated');
  assert.equal(await g.call('write'), 'ran', 'second gated call runs');
  const lines = await g.audit();
  assert.deepEqual(lines.map(l => l.action), ['remind', 'allow-after-remind']);
  assert.ok(lines.every(l => l.sessionId === 'S1' && l.gate === 'intent' && !JSON.stringify(l).includes('闭包')));
  await g.done();
});

test('m2.9.3 RT10 gate: an interjection with an instruction is judged at once; 打开微信 is never held', async () => {
  const g = await gate();
  g.say('打开微信');
  assert.equal(await g.call('phone_launch_app'), 'ran');
  const h = await gate();
  h.say('你觉得 Rust 和 Go 哪个好');
  assert.equal((await h.call('phone_launch_app')).info.code, 'DISCUSS_REMINDER');
  h.say('能不能打开微信');
  assert.equal(await h.call('phone_launch_app'), 'ran', 're-judged on the newer human message');
  await g.done(); await h.done();
});

test('m2.9.3 RT10 gate: no-approval sessions (incl. inherited) skip the gate entirely; deny stays opt-in', async () => {
  const g = await gate({ noApproval: true });
  g.say('什么是闭包？');
  assert.equal(await g.call('phone_launch_app'), 'ran');
  assert.equal(await g.call('write'), 'ran');
  assert.deepEqual((await g.audit()).map(l => l.action), ['skip-no-approval']);
  const d = await gate({ discuss: 'deny' });
  d.say('什么是闭包？');
  assert.equal((await d.call('write')).info.code, 'DISCUSS_INTENT');
  assert.equal((await d.call('write')).info.code, 'DISCUSS_INTENT');
  assert.deepEqual((await d.audit()).map(l => l.action), ['deny', 'deny']);
  await g.done(); await d.done();
});

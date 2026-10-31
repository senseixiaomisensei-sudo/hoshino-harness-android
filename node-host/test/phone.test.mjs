import {test} from 'node:test';
import assert from 'node:assert/strict';
import {runSteps, checkSteps, stepFor, render} from '../dist/plugins/android-phone/steps.js';

const fp = 'a'.repeat(32);
function fakePhone(script) {
  const calls = [];
  const call = async (method, body) => { calls.push({method, body: structuredClone(body)}); return script(method, body, calls); };
  return {calls, call};
}

test('irreversible step: one approval bound to the fingerprint, then continue from that step', async () => {
  let round = 0;
  const {calls, call} = fakePhone((method, body) => {
    if (body.action === 'grant') return {ok: true};
    round++;
    if (round === 1) return {ok: false, completed: false, results: [{index: 0, step: 'type e3 text(5 chars)', ok: true}], pending: {index: 1, step: 'tap "发送"', action: 'send', matched: '发送', fingerprint: fp, app: 'com.tencent.mm'}, screen: 'screen ...'};
    return {ok: true, completed: true, results: [{index: 0, step: 'tap "发送"', ok: true}], screen: 'screen sent'};
  });
  const asked = [];
  const approve = async (a) => { asked.push(a); return {approved: true, remember: true}; };
  const r = await runSteps(call, approve, 's1', checkSteps([{action: 'type', text: 'hello'}, {action: 'tap', target: '发送'}]));
  assert.equal(asked.length, 1);
  assert.equal(asked[0].tool, 'phone_irreversible');
  assert.equal(asked[0].arguments.once, true);
  assert.equal(calls.filter(c => c.method === 'phone' && c.body.action === 'grant').length, 1);
  const grant = calls.find(c => c.method === 'phone' && c.body.action === 'steps' && c.body.steps.length === 1);
  assert.ok(grant, 'second run starts at the pending step');
  assert.equal(calls[1].body.action, 'grant');
  assert.equal(calls[1].body.fingerprint, fp);
  assert.deepEqual(r.results.map(x => x.index), [0, 1], 'indexes refer to the original step list');
  assert.equal(r.completed, true);
});

test('declined irreversible step stops; remember is ignored so the next send asks again', async () => {
  const {calls, call} = fakePhone(() => ({ok: false, results: [], pending: {index: 0, step: 'tap', action: 'send', matched: '发送', fingerprint: fp}}));
  const r = await runSteps(call, async () => ({approved: false}), 's', checkSteps([{action: 'tap', target: '发送'}]));
  assert.equal(r.reason, 'declined');
  assert.equal(calls.filter(c => c.body.action === 'grant').length, 0);
});

test('payment is never approved by the agent: handed to the user', async () => {
  let asked = 0;
  const {call} = fakePhone(() => ({ok: false, results: [], pending: {index: 0, step: 'tap', action: 'pay', matched: '立即支付', fingerprint: fp}}));
  const r = await runSteps(call, async () => { asked++; return {approved: true}; }, 's', checkSteps([{action: 'tap', target: '立即支付'}]));
  assert.equal(asked, 0);
  assert.equal(r.reason, 'payment');
});

test('setup errors surface with the setup hint; steps are validated before reaching Android', async () => {
  const {call} = fakePhone(() => ({ok: false, error: 'Hoshino 的无障碍服务没有开启', code: 'accessibility', setup: '设置 → 设置手机操作'}));
  await assert.rejects(runSteps(call, async () => ({approved: true}), 's', checkSteps([{action: 'back'}])), /设置手机操作/);
  assert.throws(() => checkSteps([]), /1\.\.20/);
  assert.throws(() => checkSteps([{action: 'rm'}]), /unknown action/);
  assert.throws(() => checkSteps(Array.from({length: 21}, () => ({action: 'back'}))), /1\.\.20/);
  assert.deepEqual(stepFor('type', {text: 'hi', enter: true, ref: 'e2', junk: 1}), {action: 'type', ref: 'e2', text: 'hi', enter: true});
  // WP-2 typeAt: a point reaches Android for type/clear (the field may be missing from the element list).
  assert.deepEqual(stepFor('type', {text: 'hi', x: 500, y: 300}), {action: 'type', x: 500, y: 300, text: 'hi'});
  assert.deepEqual(stepFor('clear', {x: 10, y: 20}), {action: 'clear', x: 10, y: 20});
  // WP-1 A-5: cold reaches Android only for open_app.
  assert.deepEqual(stepFor('open_app', {app: 'com.x', cold: true}), {action: 'open_app', app: 'com.x', cold: true});
  assert.deepEqual(stepFor('open_app', {app: 'com.x'}), {action: 'open_app', app: 'com.x'});
  assert.deepEqual(stepFor('tap', {ref: 'e1', cold: true}), {action: 'tap', ref: 'e1'});
  assert.equal(render({ok: true, screen: 'screen x'}), '{"ok":true}\nscreen x');
});

// M2.8 approval semantics for phone steps, through the same hoshino-m2 approval seam every plugin uses.
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {Configuration, resolveApproval, withApprovalMode, riskOf, PHONE_IRREVERSIBLE_TOOL} from '../dist/plugins/hoshino-m2/config.js';
import {approval} from '../dist/plugins/hoshino-m2/approval.js';

const irreversible = {sessionId: 's', tool: PHONE_IRREVERSIBLE_TOOL, command: 'send: tap "发送" @ com.tencent.mm', arguments: {step: 'type e3 text(5 chars)', action: 'send', matched: '发送', app: 'com.tencent.mm', once: true}};

test('irreversible phone steps: asked by default and under 高危确认 even in auto; no-approval lets them through', () => {
  assert.equal(resolveApproval({mode: 'manual', highRiskAsk: true}, irreversible).decision, 'ask');
  assert.equal(resolveApproval({mode: 'auto', highRiskAsk: true}, irreversible).decision, 'ask');
  assert.equal(resolveApproval({mode: 'rules', highRiskAsk: true, rules: [{tool: PHONE_IRREVERSIBLE_TOOL, decision: 'allow'}]}, irreversible).decision, 'ask');
  assert.equal(resolveApproval({mode: 'auto', highRiskAsk: false}, irreversible).decision, 'allow');
  const none = resolveApproval({mode: 'none', highRiskAsk: true}, irreversible);
  assert.equal(none.decision, 'allow'); assert.equal(none.approver, 'auto-none');
  // The user's own deny rule is still a guard in no-approval mode.
  assert.equal(resolveApproval({mode: 'none', rules: [{tool: PHONE_IRREVERSIBLE_TOOL, decision: 'deny'}]}, irreversible).decision, 'deny');
  // Audit label carries kind and package only, never the step text or matched words.
  assert.deepEqual(riskOf(irreversible), {risk: 'irreversible:send', app: 'com.tencent.mm', once: true});
  assert.equal(riskOf({tool: 'bash'}), undefined);
});

test('no-approval: an irreversible step proceeds without a card and is audited; payment still goes to the user', async () => {
  const root = await mkdtemp(join(tmpdir(), 'hh-phone-none-'));
  try {
    const config = new Configuration(root); await config.reload();
    await config.write('approval.json', withApprovalMode(config.get('approval.json'), 'none', 's'));
    const ctx = {mobilePolicy: {options: new Map()}, androidBridge: {bootstrap: {workspace: '/work'}}};
    let cards = 0;
    const approve = (action) => approval(ctx, config, action, async () => { cards++; return {approved: false}; });
    let round = 0;
    const {calls, call} = fakePhone((method, body) => {
      if (body.action === 'grant') return {ok: true};
      round++;
      if (round === 1) return {ok: false, results: [], pending: {index: 0, step: 'tap "发送"', action: 'send', matched: '发送', fingerprint: fp, app: 'com.tencent.mm'}};
      return {ok: true, completed: true, results: [{index: 0, step: 'tap "发送"', ok: true}]};
    });
    const r = await runSteps(call, approve, 's', checkSteps([{action: 'tap', target: '发送'}]));
    assert.equal(r.completed, true); assert.equal(cards, 0);
    assert.equal(calls.filter(c => c.body.action === 'grant').length, 1);
    const audit = (await readFile(join(root, 'audit/approvals.jsonl'), 'utf8')).trim().split('\n').map(l => JSON.parse(l));
    assert.equal(audit.length, 1);
    assert.equal(audit[0].approver, 'auto-none'); assert.equal(audit[0].risk, 'irreversible:send'); assert.equal(audit[0].app, 'com.tencent.mm');
    assert.equal(JSON.stringify(audit[0]).includes('发送'), false, 'no screen words in the audit');
    // Payment: never sent to the approval seam, even in no-approval.
    const pay = fakePhone(() => ({ok: false, results: [], pending: {index: 0, step: 'tap', action: 'pay', matched: '立即支付', fingerprint: fp}}));
    const p = await runSteps(pay.call, approve, 's', checkSteps([{action: 'tap', target: '立即支付'}]));
    assert.equal(p.reason, 'payment'); assert.equal(pay.calls.filter(c => c.body.action === 'grant').length, 0);
    assert.equal((await readFile(join(root, 'audit/approvals.jsonl'), 'utf8')).trim().split('\n').length, 1, 'payment did not reach the approval seam');
    // Another session still on the shipped default (manual) gets the card.
    const other = await approve({...irreversible, sessionId: 'other'});
    assert.equal(cards, 1); assert.equal(other.approved, false);
  } finally { await rm(root, {recursive: true, force: true}); }
});

// WP-4 (B-1): composite read tools — registration (strict schemas, the plugin must still load), argument checks before
// Android is called, and the body that reaches the Android `phone` entry.
import {apply as applyPhone} from '../dist/plugins/android-phone/index.js';
import {READ_TOOLS, readArgs} from '../dist/plugins/android-phone/read.js';

function phonePlugin(reply = () => ({ok: true, screen: 'e9 btn "加入购物车" @840,440 click'})) {
  const tools = new Map(); const calls = [];
  const ctx = {
    tools: {register: t => tools.set(t.name, t)},
    androidBridge: {call: async (method, body) => { calls.push({method, body: structuredClone(body)}); return reply(body); }},
    attachments: {}, llm: {}, agents: {}, systemPrompt: {context: () => {}},
  };
  applyPhone(ctx);
  return {tools, calls};
}
const exec = {agent: {id: 'sess-1'}, signal: new AbortController().signal};
const READ_NAMES = ['phone_query', 'phone_extract', 'phone_find', 'phone_collect'];

test('WP-4 read tools register with strict schemas next to the existing phone tools', () => {
  const {tools} = phonePlugin();
  for (const name of [...READ_NAMES, 'phone_observe', 'phone_do_steps', 'phone_launch_app']) assert.ok(tools.has(name), `${name} registered`);
  // dsh-tools builds the top-level object itself; every object nested in it must declare additionalProperties (JsonSchemaError otherwise).
  const strict = (schema, path, top = false) => {
    if (!top && schema && typeof schema === 'object' && schema.type === 'object') assert.ok('additionalProperties' in schema, `${path} must declare additionalProperties`);
    for (const [k, v] of Object.entries(schema?.properties ?? {})) strict(v, `${path}.${k}`);
    if (schema?.items) strict(schema.items, `${path}[]`);
  };
  for (const name of READ_NAMES) {
    const t = tools.get(name);
    strict(t.parameters, name, true);
    for (const [k, v] of Object.entries(t.parameters.properties)) assert.ok(['string', 'number'].includes(v.type), `${name}.${k} is a flat string/number argument`);
    assert.ok(t.description.length <= 240, `${name} description stays short (${t.description.length})`);
    assert.equal(t.parameters.required, undefined, `${name}: every argument is optional`);
  }
  assert.deepEqual(tools.get('phone_collect').parameters.properties.direction.enum, ['down', 'up']);
  assert.deepEqual(tools.get('phone_extract').parameters.properties.mode.enum, ['text', 'rows', 'form']);
  assert.deepEqual(READ_TOOLS.map(t => t.name), READ_NAMES);
});

test('WP-4 read tools: schema rejects mistyped arguments before anything runs; unknown ones never reach Android', async () => {
  const {tools, calls} = phonePlugin();
  await assert.rejects(tools.get('phone_collect').execute({max_items: 'many'}, exec), /max_items|number/i);
  await assert.rejects(tools.get('phone_collect').execute({direction: 'left'}, exec), /direction|enum|one of/i);
  assert.equal(calls.length, 0, 'nothing reached Android');
  await tools.get('phone_query').execute({text: '发送', junk: 1, sessionId: 'other'}, exec);
  assert.deepEqual(calls.at(-1).body, {action: 'query', sessionId: 'sess-1', text: '发送'});
});

test('WP-4 read tools: plugin-side checks mirror Android (ScreenQueries.kt) and the body carries only what was given', async () => {
  const {tools, calls} = phonePlugin();
  await assert.rejects(tools.get('phone_query').execute({}, exec), /at least one of text, id, role, inside/);
  await assert.rejects(tools.get('phone_find').execute({role: 'btn', max_scrolls: 31}, exec), /max_scrolls must be an integer 0\.\.30/);
  await assert.rejects(tools.get('phone_collect').execute({max_items: 1.5}, exec), /max_items must be an integer/);
  await assert.rejects(tools.get('phone_collect').execute({max_items: 301}, exec), /1\.\.300/);
  await assert.rejects(tools.get('phone_extract').execute({from_y: 500, to_y: 520}, exec), /at least 50 above/);
  await assert.rejects(tools.get('phone_collect').execute({cursor: 'c1.down.xyz'}, exec), /bad cursor/);
  await assert.rejects(tools.get('phone_collect').execute({direction: 'down', cursor: 'c1.up.0123abcd'}, exec), /other direction/);
  assert.equal(calls.length, 0);

  const out = await tools.get('phone_query').execute({text: '加入购物车', inside: '商品B'}, exec);
  assert.equal(out, '{"ok":true}\ne9 btn "加入购物车" @840,440 click');
  assert.deepEqual(calls.at(-1), {method: 'phone', body: {action: 'query', sessionId: 'sess-1', text: '加入购物车', inside: '商品B'}});
  await tools.get('phone_collect').execute({cursor: 'c1.up.0123abcd.89abcdef', max_items: 40}, exec);
  assert.deepEqual(calls.at(-1).body, {action: 'collect', sessionId: 'sess-1', direction: 'up', max_items: 40, cursor: 'c1.up.0123abcd.89abcdef'}, 'direction follows the cursor');
  await tools.get('phone_extract').execute({}, exec);
  assert.deepEqual(calls.at(-1).body, {action: 'extract', sessionId: 'sess-1'});
  await tools.get('phone_find').execute({text: '设置', direction: 'up'}, exec);
  assert.deepEqual(calls.at(-1).body, {action: 'find', sessionId: 'sess-1', text: '设置', direction: 'up'});
  assert.deepEqual(readArgs('collect', {from_y: 100, to_y: 900}), {action: 'collect', from_y: 100, to_y: 900});
  assert.throws(() => readArgs('scroll', {}), /unknown read action/);
});

test('WP-4 read tools: a collect result renders as one JSON line plus the rows; setup errors keep the setup hint', async () => {
  const rows = 'a1b2c3d4 张三: 明天几点？\n0f0f0f0f 10点\n0f0f0f0f-2 10点';
  const {tools} = phonePlugin(() => ({ok: true, items: 3, screens: 2, scrolls: 1, stop: 'end', app: 'com.tencent.mm', screen: rows}));
  const out = await tools.get('phone_collect').execute({direction: 'up'}, exec);
  const [head, ...lines] = out.split('\n');
  assert.deepEqual(JSON.parse(head), {ok: true, items: 3, screens: 2, scrolls: 1, stop: 'end', app: 'com.tencent.mm'});
  assert.equal(lines.length, 3);
  const notReady = phonePlugin(() => ({ok: false, error: 'Hoshino 的无障碍服务没有开启', code: 'accessibility', setup: '设置 → 设置手机操作'}));
  await assert.rejects(notReady.tools.get('phone_collect').execute({}, exec), /设置手机操作/);
  // find that did not find is a normal result for the model, not an exception.
  const miss = phonePlugin(() => ({ok: false, found: false, total: 0, scrolls: 4, stop: 'end', detail: 'content stopped moving'}));
  assert.match(await miss.tools.get('phone_find').execute({text: '不存在'}, exec), /"found":false/);
});

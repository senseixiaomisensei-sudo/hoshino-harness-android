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
  assert.equal(render({ok: true, screen: 'screen x'}), '{"ok":true}\nscreen x');
});

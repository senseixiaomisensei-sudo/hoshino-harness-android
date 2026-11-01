import {test} from 'node:test';
import assert from 'node:assert/strict';
import {withUnlockWait, runSteps, checkSteps, MAX_UNLOCK_RECALLS} from '../dist/plugins/android-phone/steps.js';

// m2.10: Android waits for unlock in slices; a slice that ends still locked answers waiting_unlock + retry with nothing done.
const waiting = {ok: false, code: 'waiting_unlock', retry: true, error: 'waiting_unlock: the phone is locked; nothing was done yet'};

test('steps re-call transparently while Android answers waiting_unlock, then return the real result', async () => {
  const calls = [];
  let n = 0;
  const call = withUnlockWait(async (method, body) => {
    calls.push(body.action);
    return body.action === 'steps' && ++n < 3 ? waiting : {ok: true, completed: true, results: [{index: 0, step: 'tap', ok: true}]};
  });
  const r = await runSteps(call, async () => ({approved: true}), 's1', checkSteps([{action: 'tap', target: '发送'}]));
  assert.equal(r.completed, true);
  assert.deepEqual(calls, ['steps', 'steps', 'steps']);
});

test('find / collect are gated too; observe and other actions are passed through once', async () => {
  for (const action of ['find', 'collect']) {
    let n = 0;
    const call = withUnlockWait(async () => (++n === 1 ? waiting : {ok: true, found: true}));
    const r = await call('phone', {action, sessionId: 's'});
    assert.equal(r.ok, true);
    assert.equal(n, 2);
  }
  let n = 0;
  const call = withUnlockWait(async () => { n++; return waiting; });
  const r = await call('phone', {action: 'observe', sessionId: 's'});
  assert.equal(n, 1, 'observe is not re-called');
  assert.equal(r.code, 'waiting_unlock');
});

test('locked after the Android-side cap is returned as is (honest failure, no endless loop)', async () => {
  let n = 0;
  const call = withUnlockWait(async () => (++n < 3 ? waiting : {ok: false, code: 'locked', error: 'locked: the phone stayed locked or dark for 10 min; nothing was done.'}));
  await assert.rejects(runSteps(call, async () => ({approved: true}), 's', checkSteps([{action: 'back'}])), /locked/);
  assert.equal(n, 3);
});

test('a bridge that keeps answering waiting_unlock stops after the safety cap', async () => {
  let n = 0;
  const call = withUnlockWait(async () => { n++; return waiting; });
  const r = await call('phone', {action: 'steps', sessionId: 's', steps: []});
  assert.equal(r.code, 'waiting_unlock');
  assert.equal(n, MAX_UNLOCK_RECALLS + 1);
});

test('aborting the tool call while Android waits tells Android to stop the stale action', async () => {
  const seen = [];
  const ac = new AbortController();
  const call = withUnlockWait(async (method, body, signal) => {
    seen.push({action: body.action, sessionId: body.sessionId, signal: signal !== undefined});
    if (body.action === 'stop') return {ok: true};
    ac.abort();
    return waiting;
  });
  const r = await call('phone', {action: 'steps', sessionId: 's9', steps: []}, ac.signal);
  assert.equal(r.code, 'waiting_unlock', 'no re-call after abort');
  await new Promise(resolve => setImmediate(resolve));
  assert.deepEqual(seen.map(x => x.action), ['steps', 'stop']);
  assert.equal(seen[1].sessionId, 's9');
  assert.equal(seen[1].signal, false, 'the stop itself is not tied to the aborted signal');
});

// m2.8.1: opening apps goes through phone_* tools, never "configure Shizuku"; failed callbacks carry their reason.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {tierGate, tierHiddenTools, SYSTEM_SHELL_DENY_REASON, FULL_ACCESS_PROMPT} from '../dist/plugins/android-policy/tier.js';
import {sessionToolDeny} from '../dist/plugins/hoshino-m2/tool-mask.js';
import {phoneError, runSteps, PHONE_PROMPT} from '../dist/plugins/android-phone/steps.js';
import {callbackError, AndroidBridge} from '../dist/node-host/src/platform.js';
import {createServer} from 'node:http';

test('system_shell outside full-access is denied with phone_* guidance, not a Shizuku request', () => {
  for (const tier of [undefined, 'read-only', 'workspace-write']) {
    const gate = tierGate(tier, false, 'system_shell');
    assert.equal(gate.kind, 'deny');
    assert.equal(gate.reason, SYSTEM_SHELL_DENY_REASON);
  }
  assert.match(SYSTEM_SHELL_DENY_REASON, /phone_launch_app/);
  assert.match(SYSTEM_SHELL_DENY_REASON, /phone_status/);
  assert.match(SYSTEM_SHELL_DENY_REASON, /设置 → 设置手机操作/);
  assert.match(SYSTEM_SHELL_DENY_REASON, /Do not ask the user to install or configure Shizuku/);
  assert.deepEqual(tierGate('full-access', false, 'system_shell'), {kind: 'continue'});
  // Phone tools are not gated by the tier (they still go through approval in android-policy).
  assert.deepEqual(tierGate('workspace-write', false, 'phone_launch_app'), {kind: 'continue'});
  // Read-only still refuses phone actions and plan mode refuses system_shell even in full-access.
  assert.equal(tierGate('read-only', false, 'phone_launch_app').kind, 'deny');
  assert.equal(tierGate('full-access', true, 'system_shell').kind, 'deny');
  assert.match(FULL_ACCESS_PROMPT, /built-in wireless-debugging helper/);
  assert.match(FULL_ACCESS_PROMPT, /phone_launch_app/);
});

test('system_shell is hidden from the tool list unless the session is full-access', () => {
  const all = ['read', 'bash', 'system_shell', 'phone_launch_app', 'phone_status'];
  assert.deepEqual(tierHiddenTools('workspace-write'), ['system_shell']);
  assert.deepEqual(tierHiddenTools(undefined), ['system_shell']);
  assert.deepEqual(tierHiddenTools('full-access'), []);
  assert.deepEqual(sessionToolDeny(all, undefined, 'workspace-write'), ['system_shell']);
  assert.deepEqual(sessionToolDeny(all, undefined, undefined), ['system_shell']);
  assert.deepEqual(sessionToolDeny(all, undefined, 'full-access'), []);
  // A mode allow-list still applies on top of the tier.
  assert.deepEqual(sessionToolDeny(all, ['read', 'system_shell'], 'full-access'), ['bash', 'phone_launch_app', 'phone_status']);
  assert.deepEqual(sessionToolDeny(all, ['read', 'system_shell'], 'workspace-write'), ['bash', 'system_shell', 'phone_launch_app', 'phone_status']);
});

test('phone setup errors are readable, point at the setup page and rule out Shizuku', () => {
  const helper = phoneError({ok: false, error: '手机助手没有运行', code: 'helper', setup: '设置 → 设置手机操作'});
  assert.match(helper, /Phone control is not ready: 手机助手没有运行 \[helper\]/);
  assert.match(helper, /设置 → 设置手机操作/);
  assert.match(helper, /phone_status/);
  assert.match(helper, /Shizuku and full-access are NOT required/);
  assert.match(phoneError({ok: false, error: 'a11y off', code: 'accessibility'}), /设置 → 设置手机操作/);
  // Non-setup problems keep their own text (no misleading setup advice).
  assert.equal(phoneError({ok: false, error: '另一个会话正在使用手机'}), '另一个会话正在使用手机');
  assert.equal(phoneError({ok: false, error: '手机锁屏中', code: 'locked', setup: '设置 → 设置手机操作'}), '手机锁屏中 [locked] (设置 → 设置手机操作)');
});

test('runSteps surfaces the readable setup error', async () => {
  const call = async () => ({ok: false, error: '手机助手没有运行', code: 'helper', setup: '设置 → 设置手机操作'});
  await assert.rejects(runSteps(call, async () => ({approved: true}), 's', [{action: 'open_app', app: '抖音'}]), /not ready[\s\S]*NOT required/);
});

test('phone prompt sends "open an app" to phone_launch_app and never to Shizuku', () => {
  assert.match(PHONE_PROMPT, /phone_launch_app/);
  assert.match(PHONE_PROMPT, /need no Shizuku, no system_shell and no full-access/);
  assert.match(PHONE_PROMPT, /设置 → 设置手机操作/);
  assert.match(PHONE_PROMPT, /Never tell the user to install or configure Shizuku/);
  // m2.8 safety rules stay.
  assert.match(PHONE_PROMPT, /phone_handoff/);
  assert.match(PHONE_PROMPT, /Payment, passwords, captchas and verification codes/);
});

test('callbackError reads the redacted message from a 500 body', () => {
  assert.equal(callbackError(500, JSON.stringify({error: 'Shizuku 未运行/未授权，且内置无线调试助手未就绪'})), 'Android callback failed (500): Shizuku 未运行/未授权，且内置无线调试助手未就绪');
  assert.equal(callbackError(500, ''), 'Android callback failed (500)');
  assert.equal(callbackError(500, 'not json'), 'Android callback failed (500)');
  assert.equal(callbackError(401, JSON.stringify({error: 42})), 'Android callback failed (401)');
  assert.equal(callbackError(500, JSON.stringify({error: 'x'.repeat(5000)})).length, 'Android callback failed (500): '.length + 2000);
});

test('AndroidBridge.call throws the Android error message end to end', async () => {
  const server = createServer((req, res) => {
    req.resume();
    req.on('end', () => {
      if (req.url === '/system-shell') { res.writeHead(500, {'Content-Type': 'application/json'}); res.end(JSON.stringify({error: '系统命令仅允许在已授权的完全访问会话中执行'})); }
      else { res.writeHead(200, {'Content-Type': 'application/json'}); res.end('{"ok":true}'); }
    });
  });
  await new Promise(r => server.listen(0, '127.0.0.1', r));
  try {
    const bridge = new AndroidBridge({callbackPort: server.address().port, callbackToken: 't', workspace: '/w', home: '/h'});
    assert.deepEqual(await bridge.call('ok'), {ok: true});
    await assert.rejects(bridge.call('system-shell', {}), {message: 'Android callback failed (500): 系统命令仅允许在已授权的完全访问会话中执行'});
  } finally { server.close(); }
});

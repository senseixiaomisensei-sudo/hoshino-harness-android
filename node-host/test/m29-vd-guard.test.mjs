// m2.9: app operations only on the background agent display. system_shell commands that would open or operate apps on
// the user's main screen are refused and pointed at phone_*; the phone_* plugin activates and phone_status gives setup steps.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {guardSystemShell, screenCommands, splitShell, touchesScreen, LAUNCH_DENY, INPUT_DENY} from '../dist/plugins/android-policy/shell-guard.js';
import {FULL_ACCESS_PROMPT} from '../dist/plugins/android-policy/tier.js';
import {PHONE_PROMPT, phoneStatusText, phoneSetupSteps} from '../dist/plugins/android-phone/steps.js';
import {apply as applyPhone, EXPECT_SCHEMA, STEP_SCHEMA} from '../dist/plugins/android-phone/index.js';
import {modeAllows, modeAllowList, inactiveBuiltinPlugins} from '../dist/plugins/hoshino-m2/tool-mask.js';

const deny = (command, display = null) => guardSystemShell(command, display);

test('the reported command (monkey -p 抖音) is refused and points at phone_launch_app', () => {
  const r = deny('monkey -p com.ss.android.ugc.aweme 1');
  assert.equal(r.kind, 'deny');
  assert.equal(r.hit.kind, 'launch');
  assert.match(r.reason, /phone_launch_app/);
  assert.match(r.reason, /background agent display/);
  assert.ok(r.reason.startsWith(LAUNCH_DENY));
  // monkey has no display option: refused even when an agent display exists.
  assert.equal(deny('monkey -p com.ss.android.ugc.aweme -c android.intent.category.LAUNCHER 1', 7).kind, 'deny');
});

test('launch commands without a display are refused in every common spelling', () => {
  for (const cmd of [
    'am start -n com.tencent.mm/.ui.LauncherUI',
    'am start-activity -a android.intent.action.VIEW -d https://example.com',
    'cmd activity start -n com.tencent.mm/.ui.LauncherUI',
    '/system/bin/am start -W -n a/.B',
    'sh -c "am start -n a/.B"',
    "su -c 'monkey -p a 1'",
    'su 0 am start -n a/.B',
    'adb shell am start -n a/.B',
    'adb -s 1234 shell monkey -p a 1',
    'timeout 5 am start -n a/.B',
    'env FOO=1 nohup am start -n a/.B',
    'busybox nohup am start -n a/.B',
    'pm list packages; am start -n a/.B',
    'true && monkey -p a 1',
    'echo $(am start -n a/.B)',
    'pm path a\nam start -n a/.B',
  ]) {
    const r = deny(cmd);
    assert.equal(r.kind, 'deny', cmd);
    assert.equal(r.hit.kind, 'launch', cmd);
  }
});

test('input events and statusbar on the main screen are refused and point at phone_tap / phone_do_steps', () => {
  for (const cmd of ['input tap 500 900', 'input swipe 1 2 3 4', 'input text hello', 'input keyevent KEYCODE_HOME', 'cmd input tap 1 1', 'input touchscreen tap 1 1', 'input -d 0 tap 1 1']) {
    const r = deny(cmd, 9);
    assert.equal(r.kind, 'deny', cmd);
    assert.equal(r.hit.kind, 'input', cmd);
    assert.ok(r.reason.startsWith(INPUT_DENY), cmd);
    assert.match(r.reason, /phone_tap/);
    assert.match(r.reason, /phone_do_steps/);
  }
  assert.match(deny('input -d 0 tap 1 1', 9).reason, /Display 0 is the user's main screen/);
  for (const cmd of ['cmd statusbar expand-notifications', 'cmd statusbar collapse']) assert.equal(deny(cmd).hit.kind, 'statusbar');
  assert.equal(deny('am display move-stack 5 0').hit.kind, 'move');
});

test('explicit --display / -d targeting the agent display is allowed; any other display is not', () => {
  assert.deepEqual(deny('am start --display 7 -n a/.B', 7), {kind: 'continue'});
  assert.deepEqual(deny('cmd activity start-activity --display 7 -n a/.B', 7), {kind: 'continue'});
  assert.deepEqual(deny('input -d 7 tap 10 10', 7), {kind: 'continue'});
  assert.deepEqual(deny('input touchscreen -d 7 swipe 1 2 3 4', 7), {kind: 'continue'});
  const wrong = deny('am start --display 3 -n a/.B', 7);
  assert.equal(wrong.kind, 'deny'); assert.match(wrong.reason, /Display 3 is not the agent display/);
  const main = deny('am start --display 0 -n a/.B', 0);
  assert.equal(main.kind, 'deny', 'display 0 is never accepted, even if a caller passed 0 as the agent display');
  const none = deny('am start --display 7 -n a/.B', null);
  assert.equal(none.kind, 'deny'); assert.match(none.reason, /no agent display right now/);
  // One bad command in a chain refuses the whole line.
  assert.equal(deny('input -d 7 tap 1 1; input tap 1 1', 7).kind, 'deny');
  // A refused command names the agent display so a raw command can target it explicitly.
  assert.match(deny('am start -n a/.B', 7).reason, /am start --display 7/);
  // am start -d is the data URI, not a display.
  assert.equal(deny('am start -d 7 -a android.intent.action.VIEW', 7).hit.display, null);
});

test('system commands that touch no screen pass untouched', () => {
  for (const cmd of ['id -u; pwd', 'pm list packages', 'settings get secure enabled_accessibility_services', 'dumpsys display', 'am force-stop com.x', 'am broadcast -a x', 'am startservice -n a/.S',
    'cmd package list packages', 'echo "input tap 1 1"', "echo 'am start -n a/.B'", 'grep monkey /proc/1/cmdline', 'input', 'logcat -d | grep input']) {
    assert.deepEqual(deny(cmd), {kind: 'continue'}, cmd);
    assert.equal(touchesScreen(cmd), false, cmd);
  }
  assert.deepEqual(splitShell(`a 'b c' "d \\"e\\"" f\\ g; h && i | j`), [['a', 'b c', 'd "e"', 'f g'], ['h'], ['i'], ['j']]);
  assert.equal(screenCommands('sh -c "sh -c \'input tap 1 1\'"').length, 1, 'nested scripts are inspected');
});

test('prompts say: app operations only on the background virtual display, never via system_shell; display exists only during a task', () => {
  assert.match(PHONE_PROMPT, /ONLY on that background virtual display, concurrently with the user's main screen/);
  assert.match(PHONE_PROMPT, /keeps running while Hoshino itself is in the background/);
  assert.match(PHONE_PROMPT, /do not use system_shell \(monkey, am start/);
  assert.match(PHONE_PROMPT, /created only while a phone task runs/);
  assert.match(PHONE_PROMPT, /does NOT mean the feature is missing: just call phone_launch_app/);
  assert.match(FULL_ACCESS_PROMPT, /Never use system_shell for that/);
  assert.match(FULL_ACCESS_PROMPT, /concurrently with the user's main screen/);
});

test('phone_status gives clear setup steps when phone control is not set up', () => {
  const out = phoneStatusText({ok: true, sdk: 35, supported: true, accessibility: false, helper: false, ready: false, locked: false});
  assert.match(out, /设置 → 设置手机操作/);
  assert.match(out, /pair the built-in wireless-debugging helper/);
  assert.match(out, /Hoshino 手机操作/);
  assert.match(out, /Shizuku is NOT needed/);
  assert.deepEqual(phoneSetupSteps({supported: true, helper: true, accessibility: true, locked: false}), []);
  assert.match(phoneSetupSteps({supported: false, sdk: 31})[0], /Android 13/);
  assert.match(phoneStatusText({ok: true, ready: true, locked: false}), /phone_launch_app/);
  assert.match(phoneStatusText({ok: true, ready: true, locked: true, helper: true, accessibility: true}), /locked/);
});

test('android-phone activates: every phone_* tool registers with dsh-tools strict schemas', () => {
  const tools = []; let prompt;
  const ctx = {tools: {register: def => tools.push(def)}, systemPrompt: {context: c => { prompt = c; }}, androidBridge: {call: async () => ({})}, attachments: {}, llm: {}, agents: {}};
  applyPhone(ctx);
  const names = tools.map(t => t.name);
  for (const n of ['phone_status', 'phone_observe', 'phone_list_apps', 'phone_handoff', 'phone_finish', 'phone_tap', 'phone_long_press', 'phone_type_text', 'phone_clear_text', 'phone_scroll', 'phone_swipe', 'phone_back', 'phone_press_key', 'phone_launch_app', 'phone_wait', 'phone_do_steps', 'phone_screenshot'])
    assert.ok(names.includes(n), n);
  assert.equal(EXPECT_SCHEMA.additionalProperties, false);
  assert.equal(STEP_SCHEMA.additionalProperties, false);
  assert.equal(prompt.name, 'hoshino-phone');
});

test('mode allow-lists: legacy lists never hide phone_*, [] means all except chat, doctor reports inactive built-in plugins', () => {
  assert.equal(modeAllowList({id: 'standard', tools: []}), undefined);
  assert.equal(modeAllowList({id: 'custom', tools: undefined}), undefined);
  assert.deepEqual([...modeAllowList({id: 'chat', tools: []})], []);
  assert.equal(modeAllows({id: 'custom', tools: ['read']}, 'phone_launch_app'), true);
  assert.equal(modeAllows({id: 'custom', tools: ['read']}, 'write'), false);
  assert.equal(modeAllows({id: 'chat', tools: []}, 'phone_launch_app'), false);
  assert.equal(modeAllows({id: 'standard', tools: []}, 'write'), true);
  assert.deepEqual(inactiveBuiltinPlugins(['phone_launch_app', 'system_shell', 'app_screenshot', 'get_device_info', 'self_doctor']), []);
  assert.deepEqual(inactiveBuiltinPlugins(['system_shell', 'app_screenshot', 'get_device_info', 'self_doctor']), [{plugin: 'android-phone', missingTool: 'phone_launch_app'}]);
});

test('self-document boot registry (npm run build output) lists the phone_* tools of the android-phone plugin', async () => {
  const {readFile} = await import('node:fs/promises');
  const manifest = JSON.parse(await readFile(new URL('../dist/generated-self-root/self/manifest.json', import.meta.url), 'utf8'));
  const names = manifest.tools.map(t => t.name ?? t);
  assert.ok(JSON.stringify(manifest.plugins).includes('android-phone'));
  for (const tool of ['phone_launch_app', 'phone_status', 'phone_observe', 'phone_do_steps']) assert.ok(names.includes(tool), `${tool} missing from the boot registry`);
});

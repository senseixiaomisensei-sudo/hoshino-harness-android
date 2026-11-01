// m2.10 skill_save 加固（移植 Mika 5b97ea4 的规则）：self_create_skill 的结构校验、内置名保护、版本指纹防覆盖、写后读回核对。
// 纯函数用 dist 里的 skill-check.js 直接测；整条路径起原版 dsh 内核，经 /mobile/control 走真实的 hoshino-self / hoshino-m2 插件。
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';
import { createHash } from 'node:crypto';
import { validateSkill, sensitiveHit, skillVersion, renderSkill, MAX_SKILL_CHARS } from '../dist/plugins/hoshino-self/skill-check.js';

const TOOLS = new Set(['phone_launch_app', 'phone_tap', 'read', 'web_search']);
const BODY = ['## 入口', '用户说「查快递」时。', '## 步骤', '1. 用 `phone_launch_app` 打开快递 App。', '2. 用 `phone_tap` 点「我的快递」。',
  '## 容易出错的地方', '首页有广告弹窗，先关掉。', '## 怎样算办成', '看到运单状态并读给用户。', '## 什么时候停下交给用户', '要登录或付款时。'].join('\n');
const ok = (o = {}) => validateSkill({ name: 'track-parcel', description: '查快递进度时用', content: BODY, toolNames: TOOLS, ...o });

test('skill structure: name, description, five sections, length, tool names', () => {
  assert.equal(ok(), null);
  assert.equal(ok({ name: 'Track Parcel' }), 'invalid_name');
  assert.equal(ok({ name: '../x' }), 'invalid_name');
  assert.equal(ok({ description: '  ' }), 'missing_description');
  assert.equal(ok({ description: 'a\nb' }), 'description_too_long');
  assert.equal(ok({ description: 'x'.repeat(301) }), 'description_too_long');
  assert.equal(ok({ content: BODY.replace('## 容易出错的地方', '## 注意') }), 'missing_section');
  assert.equal(ok({ content: BODY + '\n' + '长'.repeat(MAX_SKILL_CHARS) }), 'too_long');
  assert.equal(ok({ content: BODY.replace('`phone_tap`', '`phone_click`') }), 'unknown_tool');
  // 英文小节标题也认
  const en = ['## Entry', 'x', '## Steps', 'use `read`', '## Pitfalls', 'x', '## Done when', 'x', '## Hand off', 'x'].join('\n');
  assert.equal(ok({ content: en }), null);
  // 正文自带 front matter 时去掉，name 只认参数
  const md = renderSkill('track-parcel', 'd', '---\nname: evil\n---\n' + BODY);
  assert.match(md, /^---\nname: track-parcel\n/);
  assert.doesNotMatch(md, /name: evil/);
});

test('skill sensitive content is refused', () => {
  for (const [text, kind] of [
    ['key sk-' + 'a'.repeat(24), 'api_key'], ['token ghp_' + 'A'.repeat(30), 'api_key'], ['Authorization: Bearer abcdefghijkl', 'api_key'],
    ['"api_key": "x"', 'api_key'], ['支付密码是 123456', 'password'], ['验证码 8 8 1 2', 'password'], ['password is hunter22', 'password'],
    ['卡号 4111 1111 1111 1111', 'card_number'], ['身份证 11010519491231002X', 'id_number'],
  ]) assert.equal(sensitiveHit(text), kind, text);
  for (const text of ['打开设置里的密码管理页面', 'pinned 3 messages', '共 2026 年 11 月', BODY]) assert.equal(sensitiveHit(text), null, text);
  assert.equal(ok({ content: BODY + '\n登录密码：abcd1234' }), 'sensitive');
});

test('skill version is the first 12 hex of SHA-256 of the whole file', () => {
  const md = renderSkill('a', 'b', BODY);
  assert.equal(skillVersion(md), createHash('sha256').update(md, 'utf8').digest('hex').slice(0, 12));
  assert.notEqual(skillVersion(md), skillVersion(md + ' '));
});

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

test('self_create_skill end to end: validates, protects built-ins, needs version to change, reads back', { timeout: 120000 }, async t => {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-skill-'));
  const workspace = join(root, 'workspace'); await mkdir(workspace);
  let ready; const kernelReady = new Promise(r => ready = r); let changed = 0;
  const callback = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer skill-test-secret') { res.writeHead(401).end(); return; }
    let raw = ''; for await (const b of req) raw += b; const body = raw ? JSON.parse(raw) : {};
    let result = {};
    if (req.url === '/providers') result = [];
    if (req.url === '/self-changed') changed++;
    if (req.url === '/ready') ready(body);
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
  });
  const callbackPort = await listen(callback);
  let logs = '';
  const child = spawn(process.execPath, ['dist/node-host/src/main.js'], { cwd: resolve('.'), stdio: ['pipe', 'pipe', 'pipe'] });
  child.stdout.on('data', b => logs += b); child.stderr.on('data', b => logs += b);
  child.stdin.end(JSON.stringify({ callbackPort, callbackToken: 'skill-test-secret', workspace, home: join(root, 'home') }) + '\n');
  t.after(async () => { if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'exit'); child.kill(); await stopped; } callback.closeAllConnections(); await new Promise(r => callback.close(r)); await rm(root, { recursive: true, force: true }); });
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }), new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 60000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
  const exchange = await fetch(`${base}/mobile/connect?token=${info.token}`, { redirect: 'manual' });
  const cookie = exchange.headers.get('set-cookie').split(';')[0];
  const control = async (action, request) => { const r = await fetch(`${base}/mobile/control`, { method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ action, request }) }); return { status: r.status, body: await r.json() }; };
  const SAFE = BODY.replaceAll('`phone_tap`', '`read`').replaceAll('`phone_launch_app`', '`web_search`');
  const create = request => control('create-skill', { name: 'track-parcel', description: '查快递进度时用', content: SAFE, ...request });
  const skillFile = join(root, 'home', 'hoshino', 'skills', 'track-parcel', 'SKILL.md');

  // 结构不对：不写文件、不回 ok
  const bad = await create({ content: '随便写写' });
  assert.equal(bad.status, 400); assert.match(bad.body.error, /missing_section/); assert.match(bad.body.error, /not saved/);
  await assert.rejects(readFile(skillFile, 'utf8'));
  assert.match((await create({ content: SAFE.replace('`read`', '`no_such_tool`') })).body.error, /unknown_tool/);
  assert.match((await create({ content: SAFE + '\nAPI token ghp_' + 'B'.repeat(30) })).body.error, /sensitive/);
  // 内置技能名不能占
  assert.match((await create({ name: 'hoshino-self' })).body.error, /builtin_name/);

  // 新建：读回一致才回 ok，返回版本
  const first = await create({});
  assert.equal(first.status, 200, JSON.stringify(first.body));
  assert.equal(first.body.ok, true); assert.equal(first.body.action, 'created');
  const onDisk = await readFile(skillFile, 'utf8');
  assert.equal(first.body.version, skillVersion(onDisk));
  assert.match(onDisk, /^---\nname: track-parcel\ndescription: "查快递进度时用"\n---\n## 入口/);

  // 改已有的：没带 version / version 过期都拒绝，文件不变
  assert.match((await create({ description: '新描述' })).body.error, /\[exists\]/);
  assert.match((await create({ description: '新描述', version: '000000000000' })).body.error, /changed_since_read/);
  assert.equal(await readFile(skillFile, 'utf8'), onDisk);
  // self_read 给出同一个版本指纹；带上它才能改
  const read = await control('m2', { operation: 'read', path: 'skills/track-parcel/SKILL.md' });
  assert.equal(read.status, 200, JSON.stringify(read.body)); assert.equal(read.body.version, first.body.version);
  const second = await create({ description: '新描述', version: read.body.version });
  assert.equal(second.status, 200, JSON.stringify(second.body)); assert.equal(second.body.action, 'updated');
  assert.notEqual(second.body.version, first.body.version);
  assert.match(await readFile(skillFile, 'utf8'), /description: "新描述"/);
  // 旧版本号不能再用（别人改过之后）
  assert.match((await create({ description: '第三次', version: first.body.version })).body.error, /changed_since_read/);
  // 通用 self_write 不能绕过校验写 SKILL.md
  const bypass = await control('m2', { operation: 'write', path: 'skills/track-parcel/SKILL.md', content: 'x' });
  assert.equal(bypass.status, 400); assert.match(bypass.body.error, /self_create_skill/);
  assert.match(await readFile(skillFile, 'utf8'), /description: "新描述"/);
  assert.ok(changed >= 2, 'self-changed is reported after each successful save');
});

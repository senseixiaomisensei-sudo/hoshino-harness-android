// EV3 提示词金样：用 mock 模型起原版 dsh（node-host 的真实启动路径、真实插件），截取发给模型的第一个请求，
// 把系统提示词、工具定义和附加的上下文消息与 test/prompt-surface/ 下的金样逐字比对，并检查大小门槛。
// 四种配置对应 Mika 的「收着 / 只开手机 / 只开网页 / 全开」：Hoshino 用 Agent 模式（modes.json）收放工具——
//   collapsed = 内置「聊天」模式（不带任何工具定义）；phone = 只留手机/设备类工具；web = web_* 工具（外加 #11 规定始终放行的 phone_*）；all = 内置「Agent」模式（全部工具）。
// phone/web 两个模式是本测试写进 modes.json 的（用户也能这样配），工具名单从构建时生成的真实注册表（self/manifest.json）按名字规则取，
// 所以 m2.8 合进 phone_* 工具后金样会出现差异，PR 里一目了然。
// 更新金样：UPDATE_PROMPT_GOLDEN=1 node --test test/prompt-surface.test.mjs（改了提示词或工具的 PR 要附上金样 diff 与大小变化）。
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {mkdtemp, mkdir, readFile, writeFile, rm} from 'node:fs/promises';
import {existsSync} from 'node:fs';
import {tmpdir} from 'node:os';
import {join, resolve, dirname} from 'node:path';
import {fileURLToPath} from 'node:url';
import {once} from 'node:events';
import {createMockLlm} from '../../tools/mock-llm/server.mjs';

const here = dirname(fileURLToPath(import.meta.url));
const goldenDir = join(here, 'prompt-surface');
const update = process.env.UPDATE_PROMPT_GOLDEN === '1';

/** 工具按名字分组（与 core-bridge HoshinoCapabilities / ToolKinds 的口径一致）。 */
export const PHONE_TOOL = /^(phone_|get_device_info$|app_screenshot$|clipboard_|share_(text|file)$|system_shell$)/;
export const WEB_TOOL = /^web_/;

export const CONFIGS = [
  {id: 'collapsed', mode: 'chat', label: '收着：聊天模式，不带工具定义'},
  {id: 'phone', mode: 'eval-phone', label: '只开手机：设备与手机类工具'},
  {id: 'web', mode: 'eval-web', label: '只开网页：web_* 工具'},
  {id: 'all', mode: 'standard', label: '全开：Agent 模式，全部工具'},
];

/** 去掉每次运行都会变的东西（临时目录、日期时间、uuid），其余逐字保留。 */
export function normalize(value, root) {
  let s = JSON.stringify(value);
  if (root) s = s.split(root).join('<ROOT>');
  // time-context（WP9-B 装载的原版子包）每轮附一行本地时刻：星期、时刻、时区随运行机器变，整段替换。
  s = s.replace(/\b(?:Mon|Tue|Wed|Thu|Fri|Sat|Sun) \d{4}-\d{2}-\d{2} \d{1,2}:\d{2}(?::\d{2})? ?(?:[AP]M)? ?(?:\([^)]*\))?(?:\[[^\]]*\])?/g, '<LOCALTIME>');
  s = s.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(?::\d{2}(?:\.\d+)?)?[+-]\d{2}:\d{2}(?:\[[^\]]*\])?/g, '<LOCALTIME>');
  s = s.replace(/\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(?:\.\d+)?Z/g, '<TIME>')
    .replace(/\b\d{4}-\d{2}-\d{2}\b/g, '<DATE>')
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}/gi, '<UUID>');
  return JSON.parse(s);
}

/** OpenAI 兼容请求 → 提示词全貌：system、tools、除用户原话以外的消息（dsh 在用户原话后附加的上下文）。 */
export function surface(body, userText) {
  const messages = body.messages ?? [];
  const system = messages.filter(m => m.role === 'system').map(m => typeof m.content === 'string' ? m.content : JSON.stringify(m.content));
  const context = messages.filter(m => m.role !== 'system').map(m => ({role: m.role, content: typeof m.content === 'string' ? m.content : m.content}))
    .filter(m => !(m.role === 'user' && (m.content === userText || (Array.isArray(m.content) && m.content.length === 1 && m.content[0]?.text === userText))));
  const tools = (body.tools ?? []).map(t => ({name: t.function?.name ?? t.name, description: t.function?.description ?? t.description, parameters: t.function?.parameters ?? t.input_schema}));
  const systemChars = system.join('\n').length;
  const toolsChars = JSON.stringify(tools).length;
  const contextChars = JSON.stringify(context).length;
  // 大小用字符数和粗估 token（字符/4，与 hoshino-m2 prompt-preview 的 tokenEstimate 同口径），不依赖分词器。
  return {system, tools, context, size: {systemChars, toolsChars, contextChars, tools: tools.length, estimatedTokens: Math.ceil((systemChars + toolsChars + contextChars) / 4)}};
}

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

test('EV3 prompt surface golden for four tool configurations (original dsh, mock model)', {timeout: 240000}, async t => {
  const manifestPath = resolve(here, '../dist/generated-self-root/self/manifest.json');
  assert.ok(existsSync(manifestPath), 'run `npm run build` first: the tool registry manifest is generated from the real boot');
  const registered = JSON.parse(await readFile(manifestPath, 'utf8')).tools.map(x => x.name);
  const phoneTools = registered.filter(n => PHONE_TOOL.test(n));
  const webTools = registered.filter(n => WEB_TOOL.test(n));
  assert.ok(webTools.length > 0, 'registry has no web_* tools');
  assert.ok(phoneTools.length > 0, 'registry has no phone/device tools');

  const root = await mkdtemp(join(tmpdir(), 'hh-prompt-'));
  const workspace = join(root, 'workspace'); const home = join(root, 'home');
  await mkdir(workspace, {recursive: true});
  // 两个评测模式写进用户可配的 modes.json；内置模式原样保留（chat、standard 用内置的定义）。
  const {modes} = await import('../dist/plugins/hoshino-m2/config.js');
  await mkdir(join(home, 'hoshino', 'config'), {recursive: true});
  await writeFile(join(home, 'hoshino', 'config', 'modes.json'), JSON.stringify({modes: [...modes,
    {id: 'eval-phone', name: '评测:只开手机', description: 'EV3', tools: phoneTools, systemPrompt: 'Complete the requested task using the available tools.'},
    {id: 'eval-web', name: '评测:只开网页', description: 'EV3', tools: webTools, systemPrompt: 'Complete the requested task using the available tools.'}]}, null, 2));

  const mock = createMockLlm(); const mockPort = await listen(mock.server);
  let ready; const kernelReady = new Promise(r => ready = r);
  const callback = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer prompt-surface-secret') { res.writeHead(401).end(); return; }
    let raw = ''; for await (const b of req) raw += b; const body = raw ? JSON.parse(raw) : {};
    let result = {};
    if (req.url === '/providers') result = [{id: 'mock', type: 'openai-compatible', baseURL: `http://127.0.0.1:${mockPort}/v1/chat/completions`, apiKey: 'KEY_MOCK', models: ['mock-model'], metadata: {models: [{id: 'mock-model', reasoning: false}]}}];
    if (req.url === '/credential') result = {value: 'sk-prompt-surface-not-a-secret', source: 'test-memory'};
    if (req.url === '/approve') result = {approved: true};
    if (req.url === '/system-access') result = {authorized: false};
    if (req.url === '/self-status') result = {currentModel: 'mock-model', provider: 'mock', logs: []};
    if (req.url === '/ready') ready(body);
    res.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(result));
  });
  const callbackPort = await listen(callback);
  let logs = '';
  // TZ 固定为 UTC：time-context 的时刻行长度随时区名变（大小也进金样），不同机器跑出来要一致。
  const child = spawn(process.execPath, ['dist/node-host/src/main.js'], {cwd: resolve(here, '..'), stdio: ['pipe', 'pipe', 'pipe'], env: {...process.env, TZ: 'UTC'}});
  child.stdout.on('data', b => logs += b); child.stderr.on('data', b => logs += b);
  child.stdin.end(JSON.stringify({callbackPort, callbackToken: 'prompt-surface-secret', workspace, home}) + '\n');
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'exit'); child.kill(); await stopped; }
    mock.server.closeAllConnections(); callback.closeAllConnections();
    await Promise.all([new Promise(r => mock.server.close(r)), new Promise(r => callback.close(r))]);
    await rm(root, {recursive: true, force: true});
  });
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }),
    new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 90000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
  const exchange = await fetch(`${base}/mobile/connect?token=${info.token}`, {redirect: 'manual'});
  assert.equal(exchange.status, 302); const cookie = exchange.headers.get('set-cookie').split(';')[0];
  const rpc = async (method, request) => {
    const response = await fetch(`${base}/api/${method}`, {method: 'POST', headers: {Cookie: cookie, 'Content-Type': 'application/json'},
      body: JSON.stringify({type: 'client-request', rpcId: crypto.randomUUID(), method, payload: {args: {request}}})});
    const json = await response.json(); assert.equal(json.result.ok, true, JSON.stringify(json)); return json.result.value;
  };
  const control = async request => {
    const r = await fetch(`${base}/mobile/control`, {method: 'POST', headers: {Cookie: cookie, 'Content-Type': 'application/json'}, body: JSON.stringify({action: 'session-options', request})});
    assert.equal(r.status, 200, await r.text());
  };

  // 子代理、目标、定时这几组插件在就绪 1 秒后才后台装载，装完写 self/manifest.json；等它写好，四种配置看到的是同一份完整注册表。
  const selfManifest = join(home, 'hoshino', 'self', 'manifest.json');
  for (const end = Date.now() + 60000; !existsSync(selfManifest);) {
    if (Date.now() > end || /optional background initialization deferred/.test(logs)) throw new Error(`deferred plugins did not load: ${logs.slice(-4000)}`);
    await new Promise(r => setTimeout(r, 100));
  }

  const user = '你好';
  const results = {};
  for (const config of CONFIGS) {
    const {sessionId} = await rpc('session/create', {cwd: workspace});
    await control({sessionId, tier: 'workspace-write', mode: config.mode, planMode: false});
    await rpc('session/selectModel', {sessionId, provider: 'mock', model: 'mock-model'});
    const before = mock.requests.length;
    await rpc('session/prompt', {sessionId, requestId: crypto.randomUUID(), mode: 'queue', content: [{type: 'text', text: user}]});
    const deadline = Date.now() + 60000;
    while (mock.requests.length === before) {
      if (Date.now() > deadline) throw new Error(`no model request for ${config.id}; logs=${logs.slice(-4000)}`);
      await new Promise(r => setTimeout(r, 100));
    }
    const request = mock.requests[before].body;
    results[config.id] = {config: {id: config.id, mode: config.mode, label: config.label}, ...normalize(surface(request, user), root)};
  }

  // 配置之间的关系先于金样检查：收着不带工具；手机/网页只带各自的工具；全开是两者的超集。
  const names = id => results[id].tools.map(x => x.name);
  assert.deepEqual(names('collapsed'), [], 'chat mode must not send tool definitions');
  assert.ok(names('phone').length > 0 && names('phone').every(n => PHONE_TOOL.test(n)), `phone config leaked: ${names('phone')}`);
  // #11（m2.8.2）起 phone_* 不受模式白名单限制（旧白名单不得藏掉手机工具，只有聊天模式不带工具），所以「只开网页」= web_* + phone_*。
  const {ALWAYS_ALLOWED_PREFIXES} = await import('../dist/plugins/hoshino-m2/tool-mask.js');
  const alwaysAllowed = n => ALWAYS_ALLOWED_PREFIXES.some(p => n.startsWith(p));
  assert.ok(names('web').some(n => WEB_TOOL.test(n)) && names('web').every(n => WEB_TOOL.test(n) || alwaysAllowed(n)), `web config leaked: ${names('web')}`);
  assert.deepEqual(names('web').filter(alwaysAllowed).sort(), registered.filter(alwaysAllowed).sort(), 'every phone_* tool must reach a non-chat mode (#11)');
  for (const n of [...names('phone'), ...names('web')]) assert.ok(names('all').includes(n), `all config misses ${n}`);
  assert.ok(results.all.size.estimatedTokens > results.web.size.estimatedTokens);

  const budgets = JSON.parse(await readFile(join(goldenDir, 'budgets.json'), 'utf8').catch(() => '{}'));
  if (update) {
    await mkdir(goldenDir, {recursive: true});
    for (const [id, r] of Object.entries(results)) await writeFile(join(goldenDir, `${id}.json`), JSON.stringify(r, null, 2) + '\n');
    await writeFile(join(goldenDir, 'sizes.json'), JSON.stringify(Object.fromEntries(Object.entries(results).map(([id, r]) => [id, r.size])), null, 2) + '\n');
  }
  for (const [id, r] of Object.entries(results)) {
    const goldenPath = join(goldenDir, `${id}.json`);
    assert.ok(existsSync(goldenPath), `missing golden ${goldenPath}; run with UPDATE_PROMPT_GOLDEN=1 and commit it`);
    const golden = JSON.parse(await readFile(goldenPath, 'utf8'));
    assert.deepEqual(r, golden, `prompt surface "${id}" changed; review the diff and run with UPDATE_PROMPT_GOLDEN=1 to accept`);
    // 大小门槛：budgets.json 是人改的上限（金样更新不会自动放宽）。
    const budget = budgets[id];
    assert.ok(budget, `no token budget for ${id} in budgets.json`);
    assert.ok(r.size.estimatedTokens <= budget.estimatedTokens, `${id}: ${r.size.estimatedTokens} estimated tokens > budget ${budget.estimatedTokens}`);
  }
});

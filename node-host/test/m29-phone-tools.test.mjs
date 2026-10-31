// m2.9 regression: phone_* tools must be in the session tool list the model receives, in the standard mode,
// the Agent/auto modes, and under a user preset saved by an older app (allow-list without phone_* or with [] tools).
// Only the chat mode stays tool-less. Boots the real dsh kernel with a mock LLM and reads the tools it sends.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {createServer} from 'node:http';
import {spawn} from 'node:child_process';
import {mkdtemp, mkdir, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join, resolve} from 'node:path';
import {once} from 'node:events';
import WebSocket from 'ws';
import {createMockLlm} from '../../tools/mock-llm/server.mjs';

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };
const PHONE = ['phone_launch_app', 'phone_status', 'phone_observe', 'phone_do_steps', 'phone_tap'];

test('phone_* tools reach the model in standard, Agent/auto and legacy custom modes; chat has none', {timeout: 150000}, async t => {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-m29-'));
  const workspace = join(root, 'workspace'); await mkdir(workspace);
  const mock = createMockLlm(); const mockPort = await listen(mock.server);
  let ready; const kernelReady = new Promise(r => ready = r);
  const callback = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer test-callback-secret') { res.writeHead(401).end(); return; }
    let raw = ''; for await (const b of req) raw += b; const body = raw ? JSON.parse(raw) : {};
    let result = {};
    if (req.url === '/providers') result = [{id: 'compatible', type: 'openai-compatible', baseURL: `http://127.0.0.1:${mockPort}/v1/chat/completions`, apiKey: 'KEY_C', models: ['mock-model'], metadata: {models: [{id: 'mock-model', reasoning: false}]}}];
    if (req.url === '/credential') result = {value: 'sk-m29-fixture', source: 'test-memory'};
    if (req.url === '/approve') result = {approved: true};
    if (req.url === '/system-access') result = {authorized: false};
    if (req.url === '/ready') ready(body);
    res.writeHead(200, {'Content-Type': 'application/json'}).end(JSON.stringify(result));
  });
  const callbackPort = await listen(callback);
  let logs = '';
  const child = spawn(process.execPath, ['dist/node-host/src/main.js'], {cwd: resolve('.'), stdio: ['pipe', 'pipe', 'pipe']});
  child.stdout.on('data', b => logs += b); child.stderr.on('data', b => logs += b);
  child.stdin.end(JSON.stringify({callbackPort, callbackToken: 'test-callback-secret', workspace, home: join(root, 'home')}) + '\n');
  t.after(async () => {
    if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'exit'); child.kill(); await stopped; }
    mock.server.closeAllConnections(); callback.closeAllConnections();
    await Promise.all([new Promise(r => mock.server.close(r)), new Promise(r => callback.close(r))]);
    await rm(root, {recursive: true, force: true});
  });
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }), new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 60000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
  const cookie = (await fetch(`${base}/mobile/connect?token=${info.token}`, {redirect: 'manual'})).headers.get('set-cookie').split(';')[0];
  const rpc = async (method, request, allowFailure = false) => {
    const response = await fetch(`${base}/api/${method}`, {method: 'POST', headers: {Cookie: cookie, 'Content-Type': 'application/json'}, body: JSON.stringify({type: 'client-request', rpcId: crypto.randomUUID(), method, payload: {args: {request}}})});
    const json = await response.json(); if (allowFailure) return json.result; assert.equal(json.result.ok, true, JSON.stringify(json)); return json.result.value;
  };
  const control = async (action, request) => {
    const response = await fetch(`${base}/mobile/control`, {method: 'POST', headers: {Cookie: cookie, 'Content-Type': 'application/json'}, body: JSON.stringify({action, request})});
    assert.equal(response.status, 200, await response.clone().text()); return response.json();
  };
  const socket = new WebSocket(base.replace('http', 'ws') + '/api/remote.mux', {headers: {Cookie: cookie}}); await once(socket, 'open'); t.after(() => socket.terminate());
  const frames = []; socket.on('message', data => frames.push(JSON.parse(data.toString())));
  const wait = async predicate => { const deadline = Date.now() + 20000; while (!predicate()) { if (Date.now() > deadline) throw new Error(`Timed out; logs=${logs}`); await new Promise(r => setTimeout(r, 50)); } };

  /** Tool names the model received on a fresh session in `mode`. */
  const toolsFor = async mode => {
    const {sessionId} = await rpc('session/create', {cwd: workspace});
    socket.send(JSON.stringify({type: 'open', streamId: `f-${sessionId}`, endpoint: 'session/follow', payload: {args: {request: {address: {kind: 'session', sessionId}, assistantStream: true}}}}));
    // The provider list is read from Android lazily; retry until the model is known.
    for (let i = 0; ; i++) { const r = await rpc('session/selectModel', {sessionId, provider: 'compatible', model: 'mock-model'}, true); if (r.ok) break; if (i > 40) assert.fail(JSON.stringify(r)); await new Promise(r => setTimeout(r, 250)); }
    await control('session-options', {sessionId, tier: 'workspace-write', mode});
    const before = frames.length; const requests = mock.requests.length;
    await rpc('session/prompt', {sessionId, requestId: crypto.randomUUID(), mode: 'queue', content: [{type: 'text', text: '你好'}]});
    await wait(() => frames.slice(before).some(f => f.value?.event?.type === 'turn/end' && JSON.stringify(f).includes(sessionId)) || mock.requests.length > requests && frames.slice(before).some(f => f.value?.event?.type === 'turn/end'));
    const body = mock.requests.slice(requests).at(-1).body;
    return (body.tools ?? []).map(x => x.function?.name ?? x.name);
  };

  for (const mode of ['standard', 'auto', 'goal']) {
    const names = await toolsFor(mode);
    for (const tool of PHONE) assert.ok(names.includes(tool), `${mode}: ${tool} missing from ${JSON.stringify(names)} LOGS=${logs.slice(-6000)}`);
    assert.ok(!names.includes('system_shell'), `${mode}: system_shell stays hidden outside full-access`);
  }
  assert.deepEqual(await toolsFor('chat'), [], 'chat mode sends no tools');

  // Presets saved by m2.8.1 and earlier apps: an edited built-in written back with "tools": [] and a custom preset whose
  // allow-list predates phone_* (and uses UI labels). Neither may hide the phone tools; the old allow-list still applies otherwise.
  const state = await control('m2', {operation: 'config', name: 'modes.json'});
  const legacy = [...state.modes.filter(m => m.id !== 'standard'),
    {id: 'standard', name: 'Agent', description: '通用任务', systemPrompt: 'Complete the requested task using the available tools.', tools: []},
    {id: 'custom-legacy', name: '旧自定义', description: '', systemPrompt: 'x', tools: ['read', 'bash', 'shell', '文件编辑']}];
  await control('m2', {operation: 'config', name: 'modes.json', value: {modes: legacy}});
  const edited = await toolsFor('standard');
  for (const tool of PHONE) assert.ok(edited.includes(tool), `edited standard ([] tools): ${tool} missing from ${JSON.stringify(edited)}`);
  assert.ok(edited.includes('write'), 'an empty list on a non-chat mode means all tools');
  const custom = await toolsFor('custom-legacy');
  for (const tool of PHONE) assert.ok(custom.includes(tool), `legacy custom allow-list: ${tool} missing from ${JSON.stringify(custom)}`);
  assert.ok(custom.includes('read') && custom.includes('bash'));
  assert.ok(!custom.includes('write'), 'the old allow-list still excludes tools it did not pick');
  assert.deepEqual(await toolsFor('chat'), [], 'chat mode stays tool-less after the rewrite');
});

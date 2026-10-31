import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readdir, readFile, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createMockLlm } from '../../tools/mock-llm/server.mjs';
const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

/**
 * End to end through the pinned dsh + pi-ai: the Sign-in-with-ChatGPT provider speaks standard OpenAI Responses, the plan
 * token comes from the Android credential callback on every request, hosted web search replaces Hoshino's search tool,
 * and the streamed web_search_call / url_citation output lands in the session as a search step and a sources footer.
 */
test('ChatGPT route: Responses with hosted web search, per-request Android credential, search step and sources in the session', { timeout: 120000 }, async t => {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-chatgpt-')); const workspace = join(root, 'workspace'); await mkdir(workspace);
  const mock = createMockLlm(); const mockPort = await listen(mock.server);
  const token = `plan-${crypto.randomUUID()}`; let credentialCalls = 0; const reports = [];
  let ready; const kernelReady = new Promise(r => ready = r);
  const callback = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer test-callback-secret') { res.writeHead(401).end(); return; }
    let raw = ''; for await (const b of req) raw += b; const body = JSON.parse(raw || '{}'); let result = {};
    if (req.url === '/providers') result = [{ id: 'openai-codex', type: 'openai-codex', baseURL: `http://127.0.0.1:${mockPort}/v1`, apiKey: 'KEY_OPENAI_CODEX', models: ['gpt-6.1-terra'],
      metadata: { name: 'ChatGPT', models: [{ id: 'gpt-6.1-terra', reasoning: true, vision: true, contextLength: 1050000, maxOutput: 128000, defaultEffort: 'Medium', reasoningLevels: ['none', 'low', 'medium', 'high', 'xhigh', 'max'] }] } }];
    if (req.url === '/credential') { credentialCalls++; assert.equal(body.ref, 'KEY_OPENAI_CODEX'); result = { value: token, source: 'chatgpt-sign-in' }; }
    if (req.url === '/approve') result = { approved: true };
    if (req.url === '/web-search-capability') reports.push(body);
    if (req.url === '/ready') ready(body);
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
  });
  const callbackPort = await listen(callback);
  let logs = '';
  const child = spawn(process.execPath, ['dist/node-host/src/main.js'], { cwd: resolve('.'), stdio: ['pipe', 'pipe', 'pipe'] });
  child.stdout.on('data', b => logs += b); child.stderr.on('data', b => logs += b);
  child.stdin.end(JSON.stringify({ callbackPort, callbackToken: 'test-callback-secret', workspace, home: join(root, 'home') }) + '\n');
  t.after(async () => { if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'exit'); child.kill(); await stopped; } mock.server.closeAllConnections(); callback.closeAllConnections(); await Promise.all([new Promise(r => mock.server.close(r)), new Promise(r => callback.close(r))]); await rm(root, { recursive: true, force: true }); });
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }), new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 60000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
  const exchange = await fetch(`${base}/mobile/connect?token=${info.token}`, { redirect: 'manual' }); const cookie = exchange.headers.get('set-cookie').split(';')[0];
  const rpc = async (method, request) => {
    const response = await fetch(`${base}/api/${method}`, { method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method, payload: { args: { request } } }) });
    const json = await response.json(); assert.equal(json.result.ok, true, JSON.stringify(json)); return json.result.value;
  };
  const socket = new WebSocket(base.replace('http', 'ws') + '/api/remote.mux', { headers: { Cookie: cookie } }); await once(socket, 'open'); t.after(() => socket.terminate());
  const { sessionId } = await rpc('session/create', { cwd: workspace });
  const frames = []; socket.on('message', data => frames.push(JSON.parse(data.toString())));
  socket.send(JSON.stringify({ type: 'open', streamId: 'follow', endpoint: 'session/follow', payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: true } } } }));
  const wait = async predicate => { const deadline = Date.now() + 30000; while (!predicate()) { if (Date.now() > deadline) throw new Error(`Timed out; frames=${JSON.stringify(frames).slice(-8000)}; logs=${logs}`); await new Promise(r => setTimeout(r, 50)); } };
  await rpc('session/selectModel', { sessionId, provider: 'openai-codex', model: 'gpt-6.1-terra', reasoningEffort: 'xhigh' });
  const turn = async text => {
    const before = frames.length;
    await rpc('session/prompt', { sessionId, requestId: crypto.randomUUID(), mode: 'queue', content: [{ type: 'text', text }] });
    await wait(() => frames.slice(before).some(f => f.value?.event?.type === 'turn/end'));
    const end = frames.slice(before).find(f => f.value?.event?.type === 'turn/end');
    assert.equal(end.value.event.data.reason.kind, 'completed', JSON.stringify(end) + logs);
    return frames.slice(before).filter(f => f.value?.event?.type === 'assistant/message').map(f => f.value.event.data.message);
  };
  const [message] = await turn('What is new today? search the web');
  const responsesCalls = mock.requests.filter(r => r.path === '/v1/responses');
  assert.equal(responsesCalls.length, 1, JSON.stringify(mock.requests.map(r => r.path)));
  const sent = responsesCalls[0];
  assert.equal(sent.authorization, `Bearer ${token}`, 'plan token from the Android credential callback');
  assert.ok(credentialCalls >= 1);
  assert.equal(sent.body.store, false); assert.equal(sent.body.stream, true);
  assert.equal(sent.body.reasoning.effort, 'xhigh');
  assert.ok(sent.body.include.includes('reasoning.encrypted_content'));
  assert.equal('max_output_tokens' in sent.body, false, 'no output cap on the plan route');
  assert.equal('temperature' in sent.body, false);
  assert.ok(sent.body.tools.some(tool => tool.type === 'web_search'), JSON.stringify(sent.body.tools));
  assert.equal(sent.body.tools.some(tool => tool.name === 'web_search'), false, 'Hoshino search is not offered beside hosted search');
  const reasoning = message.content.filter(c => c.type === 'reasoning').map(c => c.text).join('\n');
  const text = message.content.filter(c => c.type === 'text').map(c => c.text).join('');
  assert.match(reasoning, /^🌐 联网搜索：hoshino mock query/m, JSON.stringify(message));
  assert.match(text, /Hoshino mock answer from the web/);
  assert.match(text, /\*\*来源\*\*\n1\. \[Example Source A\]\(https:\/\/example\.com\/source-a\)/);
  // A second turn replays history: the synthetic search step must not be sent back as a reasoning item.
  await turn('thanks');
  const replay = mock.requests.filter(r => r.path === '/v1/responses').at(-1).body.input;
  assert.equal(replay.some(item => item.type === 'reasoning' && (item.hoshino_web_search || String(item.id).startsWith('ws_'))), false, JSON.stringify(replay));
  // Tokens never reach the session log.
  const sessionsDir = join(root, 'home'); const files = [];
  const walk = async dir => { for (const e of await readdir(dir, { withFileTypes: true }).catch(() => [])) { const p = join(dir, e.name); if (e.isDirectory()) await walk(p); else if (e.name.endsWith('.jsonl')) files.push(p); } };
  await walk(sessionsDir); assert.ok(files.length > 0);
  for (const file of files) assert.equal((await readFile(file, 'utf8')).includes(token), false);
  assert.equal(logs.includes(token), false);
});

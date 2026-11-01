// m2.10 T4 (Mika 0.1.24 2d5ca72): a stream that the upstream drops mid-reply is a transient failure — retried on the same
// route with the half reply discarded, and when it keeps dropping the failure says so honestly instead of blaming the Base URL.
// A reply whose finish_reason arrived is complete even without `data: [DONE]`. Runs the original dsh kernel (built dist)
// against tools/mock-llm/context-loop.mjs.
// m2.10 T1: a steer still in next-step is withdrawn with dsh's own session/updateQueue (remove); once the model took it the
// kernel answers session/queue-item-not-found, so a withdraw and a claim can never both succeed.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, readdir, readFile, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createContextLoopMock, CUT_HALF } from '../../tools/mock-llm/context-loop.mjs';
const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

test('m2.10 T1 steer withdraw via session/updateQueue; T4 mid-stream disconnect: retried, half reply not kept, honest wording; finish_reason without [DONE] completes', { timeout: 600000 }, async t => {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-cut-'));
  const workspace = join(root, 'workspace'); await mkdir(workspace);
  await writeFile(join(workspace, 'big.txt'), Array.from({ length: 120 }, (_, i) => `line ${i} ${'lorem ipsum dolor sit amet '.repeat(2)}`).join('\n'));
  const mock = createContextLoopMock(); const mockPort = await listen(mock.server);
  const canary = `sk-ctx-${crypto.randomUUID()}`;
  const notices = []; const approvals = []; let ready;
  const kernelReady = new Promise(r => ready = r);
  const callback = createServer(async (req, res) => {
    if (req.headers.authorization !== 'Bearer ctx-callback-secret') { res.writeHead(401).end(); return; }
    let raw = ''; for await (const b of req) raw += b; const body = JSON.parse(raw || '{}');
    let result = {};
    if (req.url === '/providers') result = [{ id: 'ctx', type: 'openai-compatible', baseURL: `http://127.0.0.1:${mockPort}/v1/chat/completions`, apiKey: 'KEY_CTX', models: ['ctx-model'], metadata: { models: [{ id: 'ctx-model', contextLength: 40000, maxOutput: 1024, reasoning: false }] } }];
    if (req.url === '/credential') result = { value: canary, source: 'test-memory' };
    if (req.url === '/approve') { approvals.push(body); result = { approved: true }; }
    if (req.url === '/context-notice') notices.push(body);
    if (req.url === '/ready') ready(body);
    res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(result));
  });
  const callbackPort = await listen(callback);
  let logs = '';
  const child = spawn(process.execPath, ['dist/node-host/src/main.js'], { cwd: resolve('.'), stdio: ['pipe', 'pipe', 'pipe'] });
  child.stdout.on('data', b => logs += b); child.stderr.on('data', b => logs += b);
  child.stdin.end(JSON.stringify({ callbackPort, callbackToken: 'ctx-callback-secret', workspace, home: join(root, 'home'), timeZone: 'Asia/Shanghai' }) + '\n');
  t.after(async () => { if (child.exitCode === null && child.signalCode === null) { const stopped = once(child, 'exit'); child.kill(); await stopped; } mock.server.closeAllConnections(); callback.closeAllConnections(); await Promise.all([new Promise(r => mock.server.close(r)), new Promise(r => callback.close(r))]); await rm(root, { recursive: true, force: true }); });
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }), new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 300000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
  // main.ts loads the deferred rows (subagent/goal/schedule tools) ~1 s after ready and then writes the self docs; those
  // tools change the system prompt/tool list once. Start the conversation after that so CX1 measures our plugins only.
  const manifest = join(root, 'home', 'hoshino', 'self', 'manifest.json');
  for (const deadline = Date.now() + 120000; ; await new Promise(r => setTimeout(r, 200))) { try { await access(manifest); break; } catch { if (Date.now() > deadline) throw new Error(`deferred kernel init did not finish: ${logs}`); } }
  const exchange = await fetch(`${base}/mobile/connect?token=${info.token}`, { redirect: 'manual' });
  const cookie = exchange.headers.get('set-cookie').split(';')[0];
  const rpc = async (method, request) => {
    const response = await fetch(`${base}/api/${method}`, { method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method, payload: { args: { ...(request === undefined ? {} : { request }) } } }) });
    const json = await response.json(); assert.equal(json.result.ok, true, JSON.stringify(json)); return json.result.value;
  };
  const context = async (body, status = 200) => { const r = await fetch(`${base}/mobile/context`, { method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' }, body: JSON.stringify(body) }); assert.equal(r.status, status, await r.clone().text()); return r.json(); };
  assert.equal((await fetch(`${base}/mobile/context`, { method: 'POST', body: '{}' })).status, 403, 'context control requires the authenticated cookie');
  const socket = new WebSocket(base.replace('http', 'ws') + '/api/remote.mux', { headers: { Cookie: cookie } }); await once(socket, 'open'); t.after(() => socket.terminate());
  const { sessionId } = await rpc('session/create', { cwd: workspace });
  await rpc('session/selectModel', { sessionId, provider: 'ctx', model: 'ctx-model' });
  const frames = []; socket.on('message', data => frames.push(JSON.parse(data.toString())));
  socket.send(JSON.stringify({ type: 'open', streamId: 'follow', endpoint: 'session/follow', payload: { args: { request: { address: { kind: 'session', sessionId }, assistantStream: false } } } }));
  const events = from => frames.slice(from).map(f => f.value?.event).filter(Boolean);
  const wait = async (predicate, ms = 60000) => { const deadline = Date.now() + ms; while (!predicate()) { if (Date.now() > deadline) throw new Error(`Timed out; events=${JSON.stringify(events(Math.max(0, frames.length - 40))).slice(-6000)}; logs=${logs.slice(-4000)}`); await new Promise(r => setTimeout(r, 50)); } };
  const prompt = async text => {
    const before = frames.length;
    await rpc('session/prompt', { sessionId, requestId: crypto.randomUUID(), mode: 'queue', content: [{ type: 'text', text }] });
    await wait(() => events(before).some(e => e.type === 'turn/end'));
    const turn = events(before);
    return { turn, end: turn.find(e => e.type === 'turn/end'), answer: turn.filter(e => e.type === 'assistant/message').map(e => e.data.message.content.filter(b => b.type === 'text').map(b => b.text).join('')).filter(Boolean).at(-1) ?? '', results: turn.filter(e => e.type === 'tool/result').map(e => e.data.message.content.map(b => b.text ?? '').join('')) };
  };

  const attempts = turn => turn.filter(e => e.type === 'assistant/attempt');
  // Durable stream records fold consecutive text deltas into `text-chunks`; single deltas stay as `chunk`.
  const streamText = e => (e.data.stream ?? []).map(r => r.type === 'text-chunks' ? r.texts.join('') : r.chunk?.type === 'text-delta' ? r.chunk.text : '').join('');

  // ---- finish_reason + clean close without `data: [DONE]` = a complete reply (no retry, no error)
  const noDone = await prompt('nodone-fixture 回答一句');
  assert.equal(noDone.end.data.reason.kind, 'completed', JSON.stringify(noDone.end));
  assert.equal(noDone.answer, '结束原因到了但没有 DONE 的完整回答。');
  assert.equal(attempts(noDone.turn).length, 0, 'no failed attempt recorded');
  assert.equal(mock.counters.get('nodone'), 1, 'not retried');

  // ---- m2.10 T1: withdraw a steer the model has not taken yet (session/updateQueue remove); the kernel arbitrates the race
  const rpcRaw = async (method, request) => {
    const response = await fetch(`${base}/api/${method}`, { method: 'POST', headers: { Cookie: cookie, 'Content-Type': 'application/json' }, body: JSON.stringify({ type: 'client-request', rpcId: crypto.randomUUID(), method, payload: { args: { request } } }) });
    return (await response.json()).result;
  };
  const steerIdOf = (from, text) => events(from).filter(e => e.type === 'agent/inbox/spliced' && e.data.target === 'next-step').flatMap(e => e.data.inserted).find(m => JSON.stringify(m.content).includes(text))?.id;
  const slowFrom = frames.length;
  await rpc('session/prompt', { sessionId, requestId: crypto.randomUUID(), mode: 'queue', content: [{ type: 'text', text: 'slow-fixture 慢慢做' }] });
  await wait(() => events(slowFrom).some(e => e.type === 'tool/call'));
  // (a) withdrawn while the tool runs: accepted once, recorded as a canceled splice, never reaches the model
  await rpc('session/prompt', { sessionId, requestId: crypto.randomUUID(), mode: 'steer', content: [{ type: 'text', text: 'WITHDRAW-ME 这句要撤回' }] });
  await wait(() => steerIdOf(slowFrom, 'WITHDRAW-ME'));
  const withdrawId = steerIdOf(slowFrom, 'WITHDRAW-ME');
  const removed = await rpcRaw('session/updateQueue', { sessionId, itemId: withdrawId, action: { kind: 'remove' } });
  assert.equal(removed.ok, true, JSON.stringify(removed));
  await wait(() => events(slowFrom).some(e => e.type === 'agent/inbox/spliced' && e.data.outcome === 'canceled' && e.data.removedCount === 1));
  const again = await rpcRaw('session/updateQueue', { sessionId, itemId: withdrawId, action: { kind: 'remove' } });
  assert.equal(again.ok, false); assert.equal(again.error?.code, 'session/queue-item-not-found', JSON.stringify(again));
  // (b) a steer the model already took cannot be withdrawn: the kernel answers queue-item-not-found
  await rpc('session/prompt', { sessionId, requestId: crypto.randomUUID(), mode: 'steer', content: [{ type: 'text', text: 'KEEP-ME 这句交给模型' }] });
  await wait(() => steerIdOf(slowFrom, 'KEEP-ME'));
  const keepId = steerIdOf(slowFrom, 'KEEP-ME');
  await wait(() => events(slowFrom).some(e => e.type === 'user/message' && e.data.id === keepId));
  const late = await rpcRaw('session/updateQueue', { sessionId, itemId: keepId, action: { kind: 'remove' } });
  assert.equal(late.ok, false); assert.equal(late.error?.code, 'session/queue-item-not-found', JSON.stringify(late));
  await wait(() => events(slowFrom).some(e => e.type === 'turn/end'));
  assert.equal(events(slowFrom).find(e => e.type === 'turn/end').data.reason.kind, 'completed');
  const slowRequests = mock.requests.filter(r => r.kind === 'main' && JSON.stringify(r.body.messages).includes('slow-fixture'));
  assert.ok(slowRequests.every(r => !JSON.stringify(r.body.messages).includes('WITHDRAW-ME')), 'the withdrawn steer never reached the model');
  assert.ok(slowRequests.some(r => JSON.stringify(r.body.messages).includes('KEEP-ME')), 'the kept steer did');

  // ---- upstream drops mid-reply twice → RT16 retries the same route; the half reply never becomes an assistant message
  const cut = await prompt('cut-fixture 讲一段');
  assert.equal(cut.end.data.reason.kind, 'completed', `${JSON.stringify(cut.end)} cut=${mock.counters.get('cut')}`);
  assert.equal(mock.counters.get('cut'), 3, 'two dropped streams, third attempt completes');
  assert.equal(cut.answer, '断流重试后的完整回答。');
  const cutAttempts = attempts(cut.turn);
  assert.equal(cutAttempts.length, 2, 'each dropped stream is settled as a failed assistant/attempt (the app discards its live text)');
  for (const a of cutAttempts) assert.ok(CUT_HALF.startsWith(streamText(a)) && streamText(a).length > 0, `attempt carried the streamed half: ${JSON.stringify(a).slice(0, 1500)}`);
  const messages = cut.turn.filter(e => e.type === 'assistant/message').map(e => JSON.stringify(e.data.message.content));
  assert.ok(messages.every(m => !m.includes(CUT_HALF.slice(0, 6))), 'the half reply is not part of any committed assistant message');
  // The retried request does not carry the half reply back to the model either.
  const lastCutRequest = mock.requests.filter(r => r.human.includes('cut-fixture') && r.kind === 'main').at(-1);
  assert.ok(!JSON.stringify(lastCutRequest.body.messages).includes(CUT_HALF.slice(0, 6)), 'retry request is rebuilt without the dropped half');

  // ---- finish_reason received, then the socket is reset before [DONE]
  const reset = await prompt('finishreset-fixture 回答一句');
  assert.equal(reset.end.data.reason.kind, 'completed', JSON.stringify(reset.end));
  assert.equal(reset.answer, '结束原因到了之后连接被重置的完整回答。');
  // Without the android-api stream guard pi-ai throws "terminated" here and RT16 re-asks the whole reply.
  assert.equal(mock.counters.get('finishreset'), 1, 'a finished reply is not retried');
  assert.equal(attempts(reset.turn).length, 0, 'no failed attempt recorded');

  // ---- upstream keeps dropping → error with honest wording: disconnected upstream, no "check Base URL"
  const before = notices.length;
  const always = await prompt('cutalways-fixture 讲一段');
  assert.equal(always.end.data.reason.kind, 'error', JSON.stringify(always.end));
  assert.equal(mock.counters.get('cutalways'), 4, 'one try + three retries');
  await wait(() => notices.slice(before).some(n => n.kind === 'failure'));
  const failure = notices.slice(before).find(n => n.kind === 'failure');
  assert.equal(failure.failureKind, 'network', JSON.stringify(failure));
  assert.ok(failure.text.includes('上游') && failure.text.includes('断开'), failure.text);
  assert.ok(!failure.text.includes('Base URL'), failure.text);
  assert.ok(!logs.includes(canary) && !logs.includes(info.token));
});

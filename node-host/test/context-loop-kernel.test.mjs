// WP9-B end-to-end against the real original dsh kernel (built dist) and tools/mock-llm/context-loop.mjs:
// long conversation → automatic compaction (model summary, then model-free), user words preserved, manual /compact,
// unclassified overflow recovery, repeated failing calls → reminders + safety stop, sandbox budget exhaustion,
// empty-reply and language reminders, discussion intent gate, transient retry, readable failure, end_not_done outcome.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, writeFile, readdir, readFile, rm, access } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createContextLoopMock } from '../../tools/mock-llm/context-loop.mjs';
const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };

test('WP9-B context & loop quality on the original kernel', { timeout: 600000 }, async t => {
  const root = await mkdtemp(join(tmpdir(), 'hoshino-ctx-'));
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
  const info = await Promise.race([kernelReady, once(child, 'exit').then(([code]) => { throw new Error(`Kernel exit ${code}: ${logs}`); }), new Promise((_, reject) => setTimeout(() => reject(new Error(`Kernel readiness timeout: ${logs}`)), 120000).unref())]);
  const base = `http://127.0.0.1:${info.port}`;
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
  const summaries = () => events(0).filter(e => e.type === 'compaction/summary');

  // ---- CX2: long conversation until the context is near full → one automatic compaction (model summary)
  let k = 0;
  for (; k < 40 && summaries().length === 0; k++) { const r = await prompt(`long-turn-${k} PIN-${k} 请记住第${k}个要求`); assert.equal(r.end.data.reason.kind, 'completed', JSON.stringify(r.end)); }
  assert.ok(summaries().length >= 1, 'automatic compaction happened before the context overflowed');
  const first = summaries()[0];
  assert.notEqual(first.data.model, 'hoshino-rules', 'auto strategy used the model summary first');
  await prompt(`long-turn-${k} PIN-${k} 压缩后的下一轮`); k++;
  const mainAfter = mock.requests.filter(r => r.kind === 'main').at(-1);
  const sent = JSON.stringify(mainAfter.body.messages);
  for (let i = 0; i < k; i++) assert.ok(sent.includes(`PIN-${i} `), `user words PIN-${i} survive compaction verbatim`);
  assert.ok(sent.includes('<compacted-summary>'));
  assert.ok(mock.requests.some(r => r.kind === 'summary'));
  await wait(() => notices.some(n => n.kind === 'compaction'));

  // ---- strategy rules: the next compaction is model-free
  const summaryCalls = mock.requests.filter(r => r.kind === 'summary').length;
  await context({ action: 'settings', settings: { compaction: { strategy: 'rules' } } });
  const before = summaries().length;
  for (let n = 0; n < 40 && summaries().length === before; n++, k++) await prompt(`long-turn-${k} PIN-${k} 第${k}个要求`);
  assert.equal(summaries().at(-1).data.model, 'hoshino-rules');
  assert.equal(mock.requests.filter(r => r.kind === 'summary').length, summaryCalls, 'no model call for the model-free checkpoint');
  await prompt(`long-turn-${k} PIN-${k} 再来一轮`); k++;
  const sent2 = JSON.stringify(mock.requests.filter(r => r.kind === 'main').at(-1).body.messages);
  for (let i = 0; i < k; i++) assert.ok(sent2.includes(`PIN-${i} `), `user words PIN-${i} survive two compactions`);

  // ---- manual /compact (the app's 压缩 button)
  const manual = await context({ action: 'compact', sessionId });
  assert.equal(manual.ok, true, JSON.stringify(manual)); assert.match(manual.text, /Compacted \d+ history items|No compactable history/);

  // ---- CX1: cache accounting and prefix stability
  const status = await context({ action: 'status', sessionId });
  assert.ok(status.cache.requests >= k && status.cache.compactions >= 2, JSON.stringify(status.cache));
  assert.ok(status.cache.hitRate > 0.5, `cache hit rate ${status.cache.hitRate}`);
  assert.ok(!status.cache.prefixBreaks.some(b => b.reason === 'system' && b.requestIndex > 3), JSON.stringify(status.cache.prefixBreaks));
  assert.ok(status.cache.prefixBreaks.some(b => b.reason === 'history'), 'compaction is the recorded reason the prefix moved');

  // ---- CX2: provider reports overflow in its own words → compact and retry
  const compactionsBefore = summaries().length;
  await prompt(`long-turn-${k} PIN-${k} 填充`); k++;
  const overflow = await prompt('overflow-fixture 继续');
  assert.equal(overflow.end.data.reason.kind, 'completed', JSON.stringify(overflow.end));
  assert.equal(overflow.answer, '压缩后继续完成。');
  assert.ok(summaries().length > compactionsBefore);

  // ---- RT12: the same failing call over and over → reminders, advice, safety stop, outcome
  const loop = await prompt('loop-fixture 读取那个文件');
  assert.equal(loop.end.data.reason.kind, 'completed');
  const loopRequests = mock.requests.filter(r => r.kind === 'main' && r.human.includes('loop-fixture')).map(r => JSON.stringify(r.body.messages)).join('\n');
  assert.ok(loopRequests.includes('You are repeating the exact same tool call'), 'original dsh repeat-tool-reminder is active');
  assert.ok(loopRequests.includes('tool calls in a row have failed'), 'failure ladder advice');
  assert.ok(loopRequests.includes('Safety stop (too_many_failures)'));
  assert.ok(loop.turn.filter(e => e.type === 'tool/call').length <= 10, 'the loop ends within the safety net');
  await wait(() => notices.some(n => n.kind === 'guard'));
  await wait(() => notices.some(n => n.kind === 'outcome' && n.outcome === 'not_possible'));

  // ---- SB4: sandbox time budget
  await context({ action: 'settings', settings: { guard: { sandboxBudgetMs: 3000 } } });
  const budget = await prompt('budget-fixture 跑几次命令');
  assert.ok(budget.results.some(r => r.includes('Sandbox commands in this task have used')), JSON.stringify(budget.results));
  assert.ok(budget.turn.filter(e => e.type === 'tool/call').length <= 4);
  await context({ action: 'settings', settings: { guard: { sandboxBudgetMs: 1800000 } } });

  // ---- RT11: empty reply and wrong language are sent back once
  const empty = await prompt('empty-fixture 说点什么');
  assert.equal(empty.answer, '这是补上的回答。');
  const language = await prompt('language-fixture 请用中文回答这个问题');
  assert.equal(language.answer, '这是用中文改写后的完整回答，内容和数值保持不变。');
  assert.ok(mock.requests.some(r => r.lastText.includes('Rewrite the whole answer in the language')));

  // ---- RT10: a discussion question does not run a writing tool, and never reaches the approval prompt
  const approvalsBefore = approvals.length;
  const discuss = await prompt('什么是闭包？');
  assert.ok(discuss.results.some(r => r.includes('The user is discussing')), JSON.stringify(discuss.results));
  await assert.rejects(access(join(workspace, 'should-not-exist.txt')));
  assert.ok(!approvals.slice(approvalsBefore).some(a => a.tool === 'write'));

  // ---- RT16: transient 503 retried on the same route; 401 explained
  const flaky = await prompt('flaky-fixture 再试试');
  assert.equal(flaky.end.data.reason.kind, 'completed'); assert.equal(flaky.answer, '重试后成功。'); assert.equal(mock.counters.get('flaky'), 3);
  const fatal = await prompt('fatal-fixture 再试试');
  assert.equal(fatal.end.data.reason.kind, 'error');
  await wait(() => notices.some(n => n.kind === 'failure' && n.failureKind === 'auth' && n.text.includes('API Key')));

  // ---- RT9: end_not_done ends the turn and is judged by rule
  const notDone = await prompt('notdone-fixture 打开那个开关');
  assert.ok(notDone.turn.some(e => e.type === 'tool/call' && e.data.name === 'end_not_done'));
  await wait(() => notices.some(n => n.kind === 'outcome' && n.text.includes('没办成') && n.text.includes('设置里没有这个开关')));
  const outcomes = (await context({ action: 'status', sessionId })).outcomes;
  assert.ok(outcomes.some(o => o.outcome === 'not_possible' && o.subtype === 'route_failed' && o.by === 'rule'));
  assert.ok(outcomes.some(o => o.outcome === 'done' && o.by === 'model'), 'work turns were judged by the light model');

  // ---- no credential in logs or durable files; outcome records persisted
  assert.ok(!logs.includes(canary) && !logs.includes(info.token));
  let durable = '';
  async function scan(dir) { for (const e of await readdir(dir, { withFileTypes: true })) { const p = join(dir, e.name); if (e.isDirectory()) await scan(p); else { const bytes = await readFile(p); assert.ok(!bytes.includes(Buffer.from(canary)), p); if (e.name.endsWith('.jsonl')) durable += bytes.toString('utf8'); } } }
  await new Promise(r => setTimeout(r, 1500)); await scan(root);
  assert.ok(durable.includes('compaction/summary') && durable.includes('plugin:hoshino/outcome'), 'compaction and outcome records are in the original session log');
  console.log(`WP9-B kernel: turns≈${k + 9}, compactions=${summaries().length}, hitRate=${status.cache.hitRate?.toFixed(2)}, mockRequests=${mock.requests.length}, notices=${notices.map(n => n.kind).join(',')}`);
});

import { test } from 'node:test';
import assert from 'node:assert/strict';
import { once } from 'node:events';
import { createServer } from 'node:http';
import { createMockLlm } from '../../tools/mock-llm/server.mjs';
import { decide, rewriteRequest, hostedFetch, setHostedSearchRoutes, resetLearnedSearch, transformChat, transformAnthropic, planErrorMessage, SEARCH_MARK } from '../dist/plugins/android-api/hosted-search.js';
import { buildProviderRoutes } from '../dist/plugins/android-api/routes.js';

const listen = async server => { server.listen(0, '127.0.0.1'); await once(server, 'listening'); return server.address().port; };
const route = (base, kind = 'chatgpt', models = {}) => ({ route: 'r', provider: 'p', kind, base, models });
const ownTool = { type: 'function', name: 'web_search', description: 'Hoshino search', parameters: { type: 'object' } };
const bashTool = { type: 'function', name: 'bash', parameters: { type: 'object' } };
const stream = text => new ReadableStream({ start(c) { c.enqueue(new TextEncoder().encode(text)); c.close(); } });
const collect = async gen => { let out = ''; const d = new TextDecoder(); for await (const chunk of gen) out += d.decode(chunk); return out; };
const events = text => text.split('\n\n').filter(Boolean).map(block => block.split('\n').find(l => l.startsWith('data: '))?.slice(6)).filter(d => d && d !== '[DONE]').map(d => JSON.parse(d));

test('decide: ChatGPT and Responses routes search by default; Off and learned refusals win; chat probes inline; anthropic needs detection', () => {
  assert.deepEqual(decide(route('x'), 'gpt-6.1-terra'), { method: 'responses:web_search' });
  assert.deepEqual(decide(route('x', 'chatgpt', { m: { mode: 'off' } }), 'm'), {});
  assert.deepEqual(decide(route('x', 'responses', { m: { mode: 'auto', method: 'responses:web_search_preview' } }), 'm'), { method: 'responses:web_search_preview' });
  assert.deepEqual(decide(route('x', 'chat', { m: { mode: 'auto', method: 'none' } }), 'm'), {});
  assert.deepEqual(decide(route('x', 'chat', { m: { mode: 'on', method: 'none' } }), 'm'), { method: 'chat:web_search_options' });
  assert.deepEqual(decide(route('x', 'chat', { m: { mode: 'auto', method: 'chat:plugins-web' } }), 'm'), { method: 'chat:plugins-web' });
  assert.deepEqual(decide(route('x', 'chat'), 'unknown'), { method: 'chat:web_search_options', probe: true });
  assert.deepEqual(decide(route('x', 'anthropic'), 'claude'), {});
  assert.deepEqual(decide(route('x', 'anthropic', { c: { mode: 'auto', method: 'anthropic:web_search' } }), 'c'), { method: 'anthropic:web_search' });
});

test('rewrite: plan route drops sampling/caps, replaces Hoshino search with the hosted tool, keeps other tools, drops replayed search steps', () => {
  const body = { model: 'gpt-6.1-terra', stream: true, store: false, temperature: 0.3, max_output_tokens: 128000, prompt_cache_retention: '24h', reasoning: { effort: 'medium' },
    tools: [bashTool, ownTool], input: [{ role: 'user', content: 'hi' }, { type: 'reasoning', id: 'ws_mock_1', summary: [{ type: 'summary_text', text: SEARCH_MARK + 'x' }], hoshino_web_search: true }, { type: 'reasoning', id: 'rs_1', encrypted_content: 'enc' }] };
  const { body: out, hosted } = rewriteRequest(route('x'), body, 'responses:web_search');
  assert.equal(hosted, true);
  for (const k of ['temperature', 'max_output_tokens', 'prompt_cache_retention']) assert.equal(k in out, false, k);
  assert.deepEqual(out.tools, [bashTool, { type: 'web_search' }]);
  assert.deepEqual(out.include.sort(), ['reasoning.encrypted_content', 'web_search_call.action.sources']);
  assert.deepEqual(out.input.map(i => i.id ?? i.role), ['user', 'rs_1']);
  assert.equal(body.tools.length, 2, 'the original request is untouched for fallback');
  const probe = rewriteRequest(route('x', 'chat'), { model: 'm', messages: [], tools: [{ type: 'function', function: { name: 'web_search' } }] }, 'chat:web_search_options', true).body;
  assert.equal(probe.tools.length, 1, 'inline detection keeps Hoshino search available');
  assert.deepEqual(probe.web_search_options, {});
  const anth = rewriteRequest(route('x', 'anthropic'), { model: 'c', tools: [{ name: 'web_search', input_schema: {} }, { name: 'read', input_schema: {} }], messages: [{ role: 'assistant', content: [{ type: 'text', text: SEARCH_MARK + 'step' }, { type: 'text', text: 'answer' }] }] }, 'anthropic:web_search').body;
  assert.deepEqual(anth.tools.map(t => t.type ?? t.name), ['read', 'web_search_20250305']);
  assert.deepEqual(anth.messages[0].content, [{ type: 'text', text: 'answer' }]);
  const variant = rewriteRequest(route('x', 'chat'), { model: 'llama' }, 'chat:variant:llama:online').body;
  assert.equal(variant.model, 'llama:online');
});

test('mock Responses stream: web_search_call becomes a search step, url_citation becomes a sources footer', async t => {
  const mock = createMockLlm(); const port = await listen(mock.server); t.after(() => mock.server.close());
  resetLearnedSearch(); setHostedSearchRoutes([route(`http://127.0.0.1:${port}/v1`)]);
  const response = await hostedFetch(fetch, `http://127.0.0.1:${port}/v1/responses`, { method: 'POST', headers: { Authorization: 'Bearer plan-token', 'Content-Type': 'application/json' },
    body: JSON.stringify({ model: 'gpt-6.1-terra', stream: true, store: false, input: [{ role: 'user', content: [{ type: 'input_text', text: 'news?' }] }], tools: [ownTool], max_output_tokens: 9999 }) });
  assert.equal(response.status, 200);
  const list = events(await response.text());
  const sent = mock.requests.at(-1).body;
  assert.deepEqual(sent.tools, [{ type: 'web_search' }]);
  assert.equal('max_output_tokens' in sent, false);
  assert.equal(mock.requests.at(-1).authorization, 'Bearer plan-token');
  assert.equal(list.some(e => e.item?.type === 'web_search_call' || e.type.startsWith('response.web_search_call')), false, 'no raw search items reach pi-ai');
  const step = list.find(e => e.type === 'response.output_item.done' && e.item?.type === 'reasoning');
  assert.match(step.item.summary[0].text, /^🌐 联网搜索：hoshino mock query/);
  assert.match(step.item.summary[0].text, /example\.org\/source-b/);
  const done = list.find(e => e.type === 'response.output_item.done' && e.item?.type === 'message');
  assert.match(done.item.content[0].text, /\*\*来源\*\*\n1\. \[Example Source A\]\(https:\/\/example\.com\/source-a\)/);
  assert.ok(list.some(e => e.type === 'response.output_text.delta' && e.delta.includes('来源')));
  assert.ok(list.some(e => e.type === 'response.completed'));
});

test('fallback: web_search refused → web_search_preview; every hosted tool refused → original request with Hoshino search, learned once', async t => {
  const mock = createMockLlm(); const port = await listen(mock.server); t.after(() => mock.server.close());
  const reports = [];
  resetLearnedSearch(); setHostedSearchRoutes([route(`http://127.0.0.1:${port}/v1`, 'responses')], { report: (...a) => reports.push(a) });
  const call = model => hostedFetch(fetch, `http://127.0.0.1:${port}/v1/responses`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ model, stream: true, store: false, input: [], tools: [ownTool] }) });
  const preview = await call('relay-preview-only'); await preview.text();
  assert.deepEqual(mock.requests.slice(-2).map(r => r.body.tools.at(-1).type), ['web_search', 'web_search_preview']);
  assert.deepEqual(reports.at(-1), ['p', 'relay-preview-only', 'responses:web_search_preview']);
  const plain = await call('relay-no-hosted'); const text = await plain.text();
  assert.equal(plain.status, 200);
  const last = mock.requests.at(-1).body;
  assert.deepEqual(last.tools, [ownTool], 'Hoshino search is back when hosted search is refused');
  assert.match(text, /without hosted search/);
  assert.deepEqual(reports.at(-1), ['p', 'relay-no-hosted', 'none']);
  const before = mock.requests.length; await (await call('relay-no-hosted')).text();
  assert.equal(mock.requests.length - before, 1, 'a learned refusal is not retried on every request');
});

test('plan route: 401 refreshes the Android credential once; include rejection retried without include; plan errors explained', async t => {
  let calls = 0; const seen = [];
  const server = createServer(async (req, res) => {
    let raw = ''; for await (const c of req) raw += c; const body = JSON.parse(raw); calls++; seen.push({ auth: req.headers.authorization, body });
    if (req.headers.authorization !== 'Bearer fresh') { res.writeHead(401, { 'Content-Type': 'application/json' }).end('{"error":{"code":"invalid_token"}}'); return; }
    if (body.include) { res.writeHead(400, { 'Content-Type': 'application/json' }).end('{"error":{"message":"Unsupported parameter","param":"include"}}'); return; }
    if (body.model === 'free-account') { res.writeHead(403, { 'Content-Type': 'application/json' }).end('{"error":{"code":"subscription_sharing_user_not_eligible","message":"not eligible"}}'); return; }
    res.writeHead(200, { 'Content-Type': 'text/event-stream' }).end('data: {"type":"response.completed","response":{"id":"r","status":"completed","output":[]}}\n\n');
  });
  const port = await listen(server); t.after(() => server.close());
  let refreshes = 0;
  resetLearnedSearch(); setHostedSearchRoutes([route(`http://127.0.0.1:${port}/v1`, 'chatgpt', { m: { mode: 'off' }, 'free-account': { mode: 'off' } })], { refreshCredential: async () => { refreshes++; return 'fresh'; } });
  const ok = await hostedFetch(fetch, `http://127.0.0.1:${port}/v1/responses`, { method: 'POST', headers: { Authorization: 'Bearer stale' }, body: JSON.stringify({ model: 'm', stream: true, input: [], reasoning: { effort: 'high' }, include: ['reasoning.encrypted_content'] }) });
  assert.equal(ok.status, 200); await ok.text();
  assert.equal(refreshes, 1);
  assert.deepEqual(seen.map(s => [s.auth, !!s.body.include]), [['Bearer stale', true], ['Bearer fresh', true], ['Bearer fresh', false]]);
  const denied = await hostedFetch(fetch, `http://127.0.0.1:${port}/v1/responses`, { method: 'POST', headers: { Authorization: 'Bearer fresh' }, body: JSON.stringify({ model: 'free-account', stream: true, input: [] }) });
  assert.equal(denied.status, 403);
  const error = (await denied.json()).error;
  assert.equal(error.code, 'subscription_sharing_user_not_eligible');
  assert.match(error.message, /Plus 或 Pro/); assert.match(error.message, /Go/);
  assert.match(planErrorMessage(429, '{}'), /额度/);
});

test('Chat Completions and Anthropic streams: citations become a footer, server tool blocks become search steps', async () => {
  const chat = await collect(transformChat(stream(
    'data: {"id":"c","choices":[{"index":0,"delta":{"content":"Answer"}}]}\n\n' +
    'data: {"id":"c","choices":[{"index":0,"delta":{"annotations":[{"type":"url_citation","url_citation":{"url":"https://example.com/x","title":"X"}}]}}]}\n\n' +
    'data: {"id":"c","choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}\n\ndata: [DONE]\n\n'), { searched: false, cited: false }));
  const chunks = events(chat);
  assert.equal(chunks.at(-2).choices[0].delta.content, '\n\n**来源**\n1. [X](https://example.com/x)');
  assert.equal(chunks.at(-1).choices[0].finish_reason, 'stop');
  const outcome = { searched: false, cited: false };
  const anth = events(await collect(transformAnthropic(stream([
    { type: 'message_start', message: { id: 'm', content: [] } },
    { type: 'content_block_start', index: 0, content_block: { type: 'server_tool_use', id: 'srvtoolu_1', name: 'web_search', input: {} } },
    { type: 'content_block_delta', index: 0, delta: { type: 'input_json_delta', partial_json: '{"query":"hoshino"}' } },
    { type: 'content_block_stop', index: 0 },
    { type: 'content_block_start', index: 1, content_block: { type: 'web_search_tool_result', tool_use_id: 'srvtoolu_1', content: [{ type: 'web_search_result', url: 'https://example.com/r', title: 'R' }] } },
    { type: 'content_block_stop', index: 1 },
    { type: 'content_block_start', index: 2, content_block: { type: 'text', text: '' } },
    { type: 'content_block_delta', index: 2, delta: { type: 'citations_delta', citation: { type: 'web_search_result_location', url: 'https://example.com/r', title: 'R' } } },
    { type: 'content_block_delta', index: 2, delta: { type: 'text_delta', text: 'Answer' } },
    { type: 'content_block_stop', index: 2 },
    { type: 'message_delta', delta: { stop_reason: 'end_turn' } }, { type: 'message_stop' },
  ].map(e => `event: ${e.type}\ndata: ${JSON.stringify(e)}\n\n`).join('')), outcome)));
  assert.equal(outcome.searched, true);
  assert.deepEqual(anth.filter(e => e.type === 'content_block_start').map(e => e.content_block.type), ['thinking', 'thinking', 'text', 'text']);
  assert.equal(anth.find(e => e.delta?.type === 'thinking_delta').delta.thinking, '🌐 联网搜索：hoshino');
  assert.equal(anth.some(e => e.delta?.type === 'citations_delta' || e.delta?.type === 'input_json_delta'), false);
  assert.match(anth.filter(e => e.delta?.type === 'text_delta').at(-1).delta.text, /\[R\]\(https:\/\/example\.com\/r\)/);
});

test('routes: ChatGPT uses standard openai-responses at its stored base; Responses-searching relay models get an extra route', () => {
  const { map, search } = buildProviderRoutes([
    { id: 'openai-codex', type: 'openai-codex', baseURL: 'https://api.openai.com/v1', apiKey: 'KEY_OPENAI_CODEX', models: ['gpt-6.1-terra'], metadata: { models: [{ id: 'gpt-6.1-terra', reasoning: true, vision: true, contextLength: 1050000, maxOutput: 128000, reasoningLevels: ['none', 'low', 'medium', 'high', 'xhigh', 'max'] }] } },
    { id: 'relay', type: 'openai-compatible', baseURL: 'https://relay.example/v1/chat/completions', apiKey: 'KEY_RELAY', models: ['a', 'b'], metadata: { models: [{ id: 'a', webSearchMethod: 'responses:web_search' }, { id: 'b', webSearchMethod: 'chat:web_search_options' }] } },
  ]);
  assert.equal(map['openai-codex'].api, 'openai-responses');
  assert.equal(map['openai-codex'].baseURL, 'https://api.openai.com/v1');
  const terra = map['openai-codex'].models[0];
  assert.deepEqual(terra.reasoningEfforts, { off: 'none', low: 'low', medium: 'medium', high: 'high', xhigh: 'xhigh', max: 'max' });
  assert.deepEqual(terra.input, ['text', 'image']); assert.equal(terra.contextWindow, 1050000);
  assert.deepEqual(terra.compat, { supportsMaxOutputTokens: false });
  assert.equal(map.relay.api, 'openai-completions');
  assert.deepEqual(map['relay--responses'].models.map(m => m.id), ['a']);
  assert.deepEqual(search.map(s => [s.route, s.kind]), [['openai-codex', 'chatgpt'], ['relay', 'chat'], ['relay--responses', 'responses']]);
});

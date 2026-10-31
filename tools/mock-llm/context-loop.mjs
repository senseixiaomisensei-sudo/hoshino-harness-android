// OpenAI-compatible mock for the WP9-B context/loop tests (long-conversation compaction, repeated calls, budget,
// reminders, intent gate, retries, overflow wording, outcome judge). Each scenario is chosen by a marker in the latest
// human message; auxiliary calls (compaction summary, intent gate, outcome judge) are recognised by their prompts.
// Usage is reported like a prefix-caching provider: prompt_tokens ≈ request chars / 4, cached_tokens = the common
// prefix with the previous main request, so cache-hit accounting can be asserted.
import { createServer } from 'node:http';
import { setTimeout as pause } from 'node:timers/promises';

const textOf = c => typeof c === 'string' ? c : Array.isArray(c) ? c.filter(p => p.type === 'text').map(p => p.text).join('') : '';
export function createContextLoopMock() {
  const requests = [];
  const counters = new Map();
  let previousMain = '';
  let overflowLength;
  const bump = key => { const n = (counters.get(key) ?? 0) + 1; counters.set(key, n); return n; };
  const server = createServer(async (req, res) => {
    const path = new URL(req.url, 'http://127.0.0.1').pathname;
    if (req.method !== 'POST' || path !== '/v1/chat/completions') { res.writeHead(404).end(); return; }
    let raw = ''; for await (const chunk of req) raw += chunk;
    const body = JSON.parse(raw);
    const messages = body.messages ?? [];
    const system = messages.filter(m => m.role === 'system' || m.role === 'developer').map(m => textOf(m.content)).join('\n');
    const last = messages.at(-1);
    const lastText = textOf(last?.content);
    const all = JSON.stringify(messages);
    const humans = messages.filter(m => m.role === 'user').map(m => textOf(m.content));
    const human = [...humans].reverse().find(t => /fixture|long-turn-|PIN-|什么是/.test(t) && !t.includes('<compacted-summary>')) ?? humans.at(-1) ?? '';
    const record = { body, kind: 'main', human, system, lastText, at: Date.now() };
    requests.push(record);
    const json = (status, value) => res.writeHead(status, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
    // ---- auxiliary calls
    let reply; let tool; let args = {};
    if (lastText.includes('acting as a compaction engine')) { record.kind = 'summary'; reply = '## Primary Request and Intent\n- long fixture conversation\n## Current Work\n- reading big.txt each turn'; }
    else if (system.includes("You judge what the user's CURRENT message")) { record.kind = 'intent'; reply = 'ACT'; }
    else if (system.includes("You judge whether an assistant's final reply")) { record.kind = 'judge'; reply = lastText.includes('notpossible-verdict') ? '{"outcome":"not_possible","reason":"没找到"}' : '{"outcome":"done","reason":""}'; }
    else {
      const hasResult = last?.role === 'tool';
      const steered = (s) => humans.slice(-3).some(t => t.includes(s)) || all.includes(s) && lastText.includes(s);
      if (/long-turn-\d+/.test(human)) {
        if (!hasResult) { tool = 'read'; args = { file_path: 'big.txt' }; } else reply = `已读取 big.txt（${human.match(/long-turn-\d+/)[0]}）。`;
      } else if (human.includes('loop-fixture')) {
        const n = bump('loop');
        if (lastText.includes('Safety stop') || n > 14) reply = '操作一直失败，我已停止。'; else { tool = 'read'; args = { file_path: 'missing-loop-file.txt' }; }
      } else if (human.includes('budget-fixture')) {
        if (lastText.includes('used their') || bump('budget') > 6) reply = '沙箱预算已用完，先交付现有结果。'; else { tool = 'bash'; args = { command: 'sleep 2; echo budget-step', description: 'budget fixture' }; }
      } else if (human.includes('empty-fixture')) {
        reply = lastText.includes('Your reply was empty') ? '这是补上的回答。' : '';
      } else if (human.includes('language-fixture')) {
        reply = lastText.includes('Rewrite the whole answer') ? '这是用中文改写后的完整回答，内容和数值保持不变。' : 'This is a complete answer written entirely in English even though the user wrote in Chinese, which should trigger the language reminder exactly once before it reaches the user.';
      } else if (human.includes('insist-fixture')) {
        // m2.9.3: the soft intent reminder holds the first call once; the model insists and the second call runs.
        if (!hasResult || lastText.includes('Check before acting')) { tool = 'write'; args = { file_path: 'insisted.txt', content: 'second call ran\n' }; } else reply = '已按你的要求写好。';
      } else if (human.includes('什么是')) {
        if (!hasResult) { tool = 'write'; args = { file_path: 'should-not-exist.txt', content: 'gate failed\n' }; } else reply = '闭包是函数和它捕获的变量环境的组合。';
      } else if (human.includes('flaky-fixture')) {
        if (bump('flaky') <= 2) { json(503, { error: { message: 'upstream temporarily unavailable' } }); return; }
        reply = '重试后成功。';
      } else if (human.includes('fatal-fixture')) {
        json(401, { error: { message: 'Incorrect API key provided' } }); return;
      } else if (human.includes('overflow-fixture')) {
        // Overflow until the history actually shrinks (a compaction during this turn); earlier turns may already carry a
        // <compacted-summary>, so its mere presence is not evidence that this overflow was recovered.
        overflowLength ??= messages.length;
        if (messages.length >= overflowLength && bump('overflow') <= 2) { json(400, { error: { message: "This model's maximum context length is 8192 tokens. However, your messages resulted in 9000 tokens." } }); return; }
        reply = '压缩后继续完成。';
      } else if (human.includes('notdone-fixture')) {
        if (!hasResult) { tool = 'end_not_done'; args = { message: '设置里没有这个开关 notpossible-verdict', subtype: 'route_failed' }; } else reply = '没办成。';
      } else reply = '好的。';
      // prefix-cache accounting for main requests. Providers render tool definitions before the conversation (OpenAI /
      // DeepSeek chat templates), so the cacheable prefix is tools → messages, not the JSON key order of the request
      // body (where a long tools array after `messages` would never count as cached).
      const prefix = JSON.stringify({ tools: body.tools ?? [], messages: body.messages ?? [] });
      let common = 0; const max = Math.min(prefix.length, previousMain.length); while (common < max && prefix.charCodeAt(common) === previousMain.charCodeAt(common)) common++;
      record.cachedChars = common; previousMain = prefix;
    }
    record.tool = tool; record.reply = reply;
    const promptTokens = Math.ceil(raw.length / 4); const cached = Math.floor((record.cachedChars ?? 0) / 4);
    res.writeHead(200, { 'Content-Type': 'text/event-stream', 'Cache-Control': 'no-cache', Connection: 'keep-alive' });
    const chunk = async (delta, finish = null) => { res.write(`data: ${JSON.stringify({ id: 'ctx', object: 'chat.completion.chunk', created: 1, model: body.model, choices: [{ index: 0, delta, finish_reason: finish }], ...(finish ? { usage: { prompt_tokens: promptTokens, completion_tokens: 12, total_tokens: promptTokens + 12, prompt_tokens_details: { cached_tokens: cached } } } : {}) })}\n\n`); await pause(2); };
    await chunk({ role: 'assistant', content: '' });
    if (tool) { await chunk({ tool_calls: [{ index: 0, id: `call_${requests.length}`, type: 'function', function: { name: tool, arguments: JSON.stringify(args) } }] }); await chunk({}, 'tool_calls'); }
    else { if (reply) await chunk({ content: reply }); await chunk({}, 'stop'); }
    res.write('data: [DONE]\n\n'); res.end();
  });
  return { server, requests, counters };
}

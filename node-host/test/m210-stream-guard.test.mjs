// m2.10 T4: unit tests for plugins/android-api/stream-guard.ts (a finished reply survives a reset before [DONE]).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { dialectOf, EndWatcher, guardStream } from '../dist/plugins/android-api/stream-guard.js';

const enc = new TextEncoder();
/** A body that yields the given SSE parts and then fails like undici's "terminated" (or ends cleanly). */
// Pull-based so the queued parts are read before the failure (erroring a stream in start() would drop them).
const body = (parts, fail = true) => { let i = 0; return new ReadableStream({
  pull(c) { if (i < parts.length) c.enqueue(enc.encode(parts[i++])); else if (fail) c.error(Object.assign(new TypeError('terminated'), { cause: new Error('other side closed') })); else c.close(); },
}); };
const read = async stream => { let out = ''; const d = new TextDecoder(); for await (const b of stream) out += d.decode(b, { stream: true }); return out; };
const chat = (delta, finish = null) => `data: ${JSON.stringify({ choices: [{ index: 0, delta, finish_reason: finish }] })}\n\n`;

test('dialect from the model request path', () => {
  assert.equal(dialectOf('https://api.deepseek.com/v1/chat/completions'), 'chat');
  assert.equal(dialectOf('https://api.anthropic.com/v1/messages'), 'anthropic');
  assert.equal(dialectOf('https://chatgpt.com/backend-api/codex/responses'), 'responses');
  assert.equal(dialectOf('https://api.openai.com/v1/models'), null);
  assert.equal(dialectOf('not a url'), null);
});

test('end reasons per dialect, across chunk boundaries', () => {
  const w = new EndWatcher('chat');
  const frame = chat({}, 'stop');
  w.push(frame.slice(0, 17)); assert.equal(w.finished, false);
  w.push(frame.slice(17)); assert.equal(w.finished, true);
  const a = new EndWatcher('anthropic');
  a.push('event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}\n\n');
  assert.equal(a.finished, true); assert.match(a.tail(), /message_stop/);
  a.push('event: message_stop\ndata: {"type":"message_stop"}\n\n'); assert.equal(a.tail(), '');
  const r = new EndWatcher('responses');
  r.push('data: {"type":"response.output_text.delta","delta":"x"}\n\n'); assert.equal(r.finished, false);
  r.push('data: {"type":"response.completed","response":{}}\n\n'); assert.equal(r.finished, true);
  const c = new EndWatcher('chat'); c.push(chat({ content: 'finish_reason: stop' })); assert.equal(c.finished, false, 'text that mentions finish_reason is not an end');
});

test('reset after finish_reason ends cleanly; bytes pass through unchanged', async () => {
  let recovered = 0;
  const parts = [chat({ role: 'assistant', content: '' }), chat({ content: '完整回答' }), chat({}, 'stop')];
  assert.equal(await read(guardStream(body(parts), 'chat', () => recovered++)), parts.join(''));
  assert.equal(recovered, 1);
});

test('reset before any end reason is still a failure (a real mid-reply drop)', async () => {
  let recovered = 0;
  await assert.rejects(read(guardStream(body([chat({ content: '半句' })]), 'chat', () => recovered++)), /terminated/);
  assert.equal(recovered, 0);
});

test('Anthropic: stop_reason then reset gets the closing message_stop', async () => {
  const parts = ['event: message_delta\ndata: {"type":"message_delta","delta":{"stop_reason":"end_turn"}}\n\n'];
  const out = await read(guardStream(body(parts), 'anthropic'));
  assert.ok(out.startsWith(parts[0]) && out.endsWith('data: {"type":"message_stop"}\n\n'), out);
});

test('clean streams and aborts are untouched', async () => {
  const parts = [chat({ content: 'x' }), chat({}, 'stop'), 'data: [DONE]\n\n'];
  assert.equal(await read(guardStream(body(parts, false), 'chat')), parts.join(''));
  let sent = false;
  const aborted = new ReadableStream({ pull(c) { if (!sent) { sent = true; c.enqueue(enc.encode(chat({}, 'stop'))); } else c.error(new DOMException('aborted', 'AbortError')); } });
  await assert.rejects(read(guardStream(aborted, 'chat')), e => e.name === 'AbortError');
});

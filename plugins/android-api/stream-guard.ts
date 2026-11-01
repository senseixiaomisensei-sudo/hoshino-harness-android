// Portions derived from Mika (https://github.com/wangzan101/mika), Copyright 2026 wangzan101, Apache-2.0. Modified by Hoshino Harness contributors.
// (0.1.24 · 2d5ca72: "a reply whose end reason arrived is complete, no need to wait for [DONE]"; re-implemented as a fetch
// wrapper because the wire code here is dsh's pi-ai route, which we do not fork.)
//
// m2.10 T4. pi-ai (OpenAI SDK / Anthropic SDK underneath) reads the SSE body to the end. When the upstream resets the
// connection AFTER the provider already sent its end reason (`finish_reason`, Anthropic `stop_reason`/`message_stop`,
// Responses `response.completed`) but before the closing `data: [DONE]` / clean EOF, undici rejects the read with
// "terminated" and the whole, finished reply is thrown away as a transport error (and RT16 retries it from scratch).
// This guard sits under every model request: it passes bytes through untouched, watches for the end reason, and if the
// body read fails after it, ends the stream cleanly instead (synthesising Anthropic's `message_stop` when only the
// `stop_reason` arrived). A failure before the end reason is still a failure — that is a real mid-reply drop.
// Nothing about the content is logged.

export type StreamDialect = 'chat' | 'anthropic' | 'responses';

/** Which streaming wire a model request URL speaks, or null for anything that is not a model request. */
export function dialectOf(url: string): StreamDialect | null {
  let path: string;
  try { path = new URL(url).pathname; } catch { return null; }
  if (/\/chat\/completions$/.test(path)) return 'chat';
  if (/\/messages$/.test(path)) return 'anthropic';
  if (/\/responses$/.test(path)) return 'responses';
  return null;
}

/** Incremental SSE scanner: has the provider already said the reply is over? */
export class EndWatcher {
  private buffer = '';
  /** The provider sent its end reason. */
  finished = false;
  /** Anthropic only: the closing `message_stop` arrived too (nothing to synthesise). */
  stopped = false;
  constructor(private readonly dialect: StreamDialect) {}

  push(text: string): void {
    this.buffer += text;
    for (let i = this.buffer.indexOf('\n'); i >= 0; i = this.buffer.indexOf('\n')) {
      this.line(this.buffer.slice(0, i).trim());
      this.buffer = this.buffer.slice(i + 1);
    }
  }

  /** What to append so the SDK sees a well-formed end (only Anthropic needs a frame of its own). */
  tail(): string {
    return this.dialect === 'anthropic' && this.finished && !this.stopped ? 'event: message_stop\ndata: {"type":"message_stop"}\n\n' : '';
  }

  private line(line: string): void {
    if (!line.startsWith('data:')) return;
    const data = line.slice(5).trim();
    if (!data || data === '[DONE]') return;
    let event: any;
    try { event = JSON.parse(data); } catch { return; }
    switch (this.dialect) {
      case 'chat':
        if (Array.isArray(event?.choices) && event.choices.some((c: any) => c?.finish_reason)) this.finished = true;
        break;
      case 'anthropic':
        if (event?.type === 'message_delta' && event?.delta?.stop_reason) this.finished = true;
        if (event?.type === 'message_stop') { this.finished = true; this.stopped = true; }
        break;
      case 'responses':
        if (event?.type === 'response.completed' || event?.type === 'response.incomplete') this.finished = true;
        break;
    }
  }
}

const isAbort = (error: unknown) => (error as Error)?.name === 'AbortError';

/**
 * Pass an SSE body through; when reading it fails after the end reason was seen, close it cleanly instead.
 * @param onRecovered - called once when a post-end failure was turned into a clean end (for counters/tests).
 */
export function guardStream(body: ReadableStream<Uint8Array>, dialect: StreamDialect, onRecovered?: () => void): ReadableStream<Uint8Array> {
  const reader = body.getReader();
  const watcher = new EndWatcher(dialect);
  const decoder = new TextDecoder();
  const encoder = new TextEncoder();
  return new ReadableStream<Uint8Array>({
    async pull(controller) {
      let chunk: ReadableStreamReadResult<Uint8Array>;
      try { chunk = await reader.read(); }
      catch (error) {
        if (watcher.finished && !isAbort(error)) {
          const tail = watcher.tail();
          if (tail) controller.enqueue(encoder.encode(tail));
          controller.close();
          onRecovered?.();
          return;
        }
        controller.error(error);
        return;
      }
      if (chunk.done) { controller.close(); return; }
      watcher.push(decoder.decode(chunk.value, { stream: true }));
      controller.enqueue(chunk.value);
    },
    cancel(reason) { return reader.cancel(reason); },
  });
}

let installed = false;
/** How many finished replies were saved from a post-end connection reset (diagnostics only). */
export const streamGuardStats = { recovered: 0 };

/** Wrap global fetch once. Install it before the other fetch wrappers so it sits closest to the network. */
export function installStreamGuardFetch(): void {
  if (installed) return; installed = true;
  const original = globalThis.fetch.bind(globalThis);
  globalThis.fetch = (async (input: any, init?: any) => {
    const response = await original(input, init);
    try {
      const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input?.url;
      const method = String(init?.method ?? (typeof input === 'object' && input && 'method' in input ? input.method : 'GET')).toUpperCase();
      const dialect = raw && method === 'POST' ? dialectOf(raw) : null;
      if (!dialect || !response.ok || !response.body || !/text\/event-stream/i.test(response.headers.get('content-type') ?? '')) return response;
      // The body is already decoded by fetch; drop the wire framing headers so nothing downstream decodes it twice.
      const headers = new Headers(response.headers); headers.delete('content-encoding'); headers.delete('content-length');
      return new Response(guardStream(response.body, dialect, () => { streamGuardStats.recovered++; }), { status: response.status, statusText: response.statusText, headers });
    } catch { return response; }
  }) as typeof fetch;
}

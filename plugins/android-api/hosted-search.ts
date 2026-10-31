/**
 * Model-hosted web search and the Sign-in-with-ChatGPT request rules, applied at the fetch boundary under pi-ai.
 *
 * pi-ai (pinned 1.4.0) owns the wire protocols; it neither adds hosted tools nor understands their output items
 * (web_search_call, url_citation, server_tool_use …), and it cannot tell a ChatGPT plan token from an API key on a
 * route that is not literally `openai`. Rather than fork it, requests to configured model routes are adjusted here and
 * the streamed replies are rewritten into shapes pi-ai already renders:
 *   - a search step becomes one reasoning/thinking block whose text starts with SEARCH_MARK (the app shows it as a
 *     "联网搜索" step; replaying it to the model is suppressed below);
 *   - citations become a Markdown "来源" footer appended to the answer text.
 * Hosted search replaces Hoshino's own `web_search` function tool for that request (no double search). When the provider
 * refuses the hosted tool, the original request — with Hoshino's tool — is sent instead (automatic fallback).
 * These are the same model requests the user already approved; nothing here opens a new destination.
 */
export const SEARCH_MARK = '🌐 ';
export type SearchMode = 'auto' | 'on' | 'off';
export interface RouteModel { mode: SearchMode; method?: string }
export interface SearchRoute {
  /** dsh route key (provider id, or `<id>--responses` for a relay routed through Responses). */
  route: string;
  /** Provider id as stored by the app (capability reports go to it). */
  provider: string;
  kind: 'chatgpt' | 'responses' | 'chat' | 'anthropic';
  /** Normalized base: Responses/Chat = `<…>/v1`, Anthropic = root before `/v1`. */
  base: string;
  models: Record<string, RouteModel>;
}
export interface HostedSearchHooks {
  /** A fresh credential after the API answered 401 (ChatGPT plan tokens are refreshed by Android). */
  refreshCredential?: (route: SearchRoute) => Promise<string | undefined>;
  /** Persist what a real request proved about a model's hosted search. */
  report?: (provider: string, model: string, method: string) => void;
  /** Diagnostics without request content or credentials. */
  log?: (message: string) => void;
}

const HOSHINO_TOOL = 'web_search';
const routes: SearchRoute[] = [];
const learned = new Map<string, string>();
let hooks: HostedSearchHooks = {};
let installed = false;

export function setHostedSearchRoutes(list: SearchRoute[], next: HostedSearchHooks = hooks) {
  routes.splice(0, routes.length, ...list.map(r => ({ ...r, base: r.base.replace(/\/+$/, '') })));
  routes.sort((a, b) => b.base.length - a.base.length);
  hooks = next;
}
export function resetLearnedSearch() { learned.clear(); }

/** The method a request should use, or undefined for none. `probe` marks an inline first-use detection. */
export function decide(route: SearchRoute, model: string): { method?: string; probe?: boolean } {
  const cfg = route.models[model] ?? { mode: 'auto' as SearchMode };
  if (cfg.mode === 'off') return {};
  const known = learned.get(route.provider + '\u0000' + model) ?? cfg.method ?? '';
  if (route.kind === 'chatgpt' || route.kind === 'responses') {
    if (known === 'none' && cfg.mode !== 'on') return {};
    return { method: known.startsWith('responses:') ? known : 'responses:web_search' };
  }
  if (route.kind === 'anthropic') {
    if (known === 'anthropic:web_search' || cfg.mode === 'on') return { method: 'anthropic:web_search' };
    return {};
  }
  if (known.startsWith('chat:')) return { method: known };
  if (known === 'none' || known.startsWith('responses:')) return cfg.mode === 'on' ? { method: 'chat:web_search_options' } : {};
  // Unknown on a Chat Completions route: try web_search_options inside this already-approved request, keeping Hoshino's
  // own search tool available, and learn from the reply.
  return { method: 'chat:web_search_options', probe: cfg.mode !== 'on' };
}

function isHoshinoSearchTool(tool: any): boolean {
  return tool?.name === HOSHINO_TOOL && (tool.type === 'function' || tool.type === undefined || tool.type === 'custom')
    || tool?.type === 'function' && tool.function?.name === HOSHINO_TOOL;
}

function withoutSearchReplay(input: any[]): any[] {
  return input.filter(item => !(item?.type === 'reasoning' && (item.hoshino_web_search || typeof item.id === 'string' && /^ws_/.test(item.id) && !item.encrypted_content)));
}

/** Request rewrite. Returns the body to send and whether hosted search was added. */
export function rewriteRequest(route: SearchRoute, body: any, method: string | undefined, probe = false): { body: any; hosted: boolean } {
  const next = structuredClone(body);
  if (route.kind === 'chatgpt') {
    // Sign in with ChatGPT accepts only the core Responses fields (Mika's ResponsesModel): no sampling or output caps.
    for (const k of ['temperature', 'top_p', 'max_output_tokens', 'prompt_cache_retention', 'prompt_cache_options', 'service_tier', 'truncation', 'user', 'metadata']) delete next[k];
    next.store = false;
  }
  if (route.kind === 'chatgpt' || route.kind === 'responses') {
    if (Array.isArray(next.input)) next.input = withoutSearchReplay(next.input);
    if (next.reasoning && next.reasoning.effort && next.reasoning.effort !== 'none') {
      next.include = [...new Set([...(Array.isArray(next.include) ? next.include : []), 'reasoning.encrypted_content'])];
    }
  }
  if (route.kind === 'anthropic' && Array.isArray(next.messages)) {
    for (const m of next.messages) if (m?.role === 'assistant' && Array.isArray(m.content))
      m.content = m.content.filter((b: any) => !((b?.type === 'text' || b?.type === 'thinking') && String(b.text ?? b.thinking ?? '').startsWith(SEARCH_MARK)));
  }
  if (!method) return { body: next, hosted: false };
  const tools: any[] = Array.isArray(next.tools) ? next.tools : [];
  const keepOwn = probe;
  const own = keepOwn ? tools : tools.filter(t => !isHoshinoSearchTool(t));
  if (method.startsWith('responses:')) {
    next.tools = [...own, { type: method.slice('responses:'.length) }];
    next.include = [...new Set([...(Array.isArray(next.include) ? next.include : []), 'web_search_call.action.sources'])];
  } else if (method === 'anthropic:web_search') {
    next.tools = [...own, { type: 'web_search_20250305', name: HOSHINO_TOOL, max_uses: 5 }];
  } else if (method === 'chat:web_search_options') {
    next.web_search_options = {}; next.tools = own;
  } else if (method === 'chat:plugins-web') {
    next.plugins = [...(Array.isArray(next.plugins) ? next.plugins : []), { id: 'web' }]; next.tools = own;
  } else if (method.startsWith('chat:variant:')) {
    next.model = method.slice('chat:variant:'.length); next.tools = own;
  }
  if (Array.isArray(next.tools) && next.tools.length === 0) delete next.tools;
  if (next.tools === undefined && next.tool_choice !== undefined && next.tool_choice !== 'none' && next.tool_choice !== 'auto') delete next.tool_choice;
  return { body: next, hosted: true };
}

// ---------- people-facing errors for the ChatGPT plan route ----------

const PLAN_ERRORS: Record<string, string> = {
  subscription_sharing_user_not_eligible: '这个 ChatGPT 账号不能在 Hoshino 里使用套餐额度：需要 ChatGPT Plus 或 Pro（免费版和 Go 不支持）。可以改用其他服务商的 API Key。',
  subscription_sharing_usage_limit_exceeded: 'ChatGPT 套餐额度已用完（与其他使用套餐的 App 共用），请稍后再试。用量：https://chatgpt.com/settings/usage',
  subscription_sharing_workplace_individual_usage_limit_exceeded: '已达到 ChatGPT 工作区为你设置的用量上限，请联系工作区管理员提高限额。',
  subscription_sharing_usage_unavailable: 'ChatGPT 套餐额度暂时不可用，请稍后再试。',
  subscription_sharing_user_unavailable: 'ChatGPT 账号暂时不可用，请稍后再试。',
  subscription_sharing_route_not_supported: 'ChatGPT 套餐不支持这个接口。',
  subscription_sharing_invalid_user: 'ChatGPT 登录已失效，请到设置 → 模型 → ChatGPT 重新登录。',
  chatpass_v2_scope_not_authorized: '没有允许 Hoshino 使用 ChatGPT 套餐，请重新登录并允许使用套餐额度（需要 Plus 或 Pro）。',
  chatpass_v2_invalid_authorization_context: 'ChatGPT 登录已失效，请到设置 → 模型 → ChatGPT 重新登录。',
  rate_limit_exceeded: 'ChatGPT 套餐额度已用完或请求太频繁（与其他使用套餐的 App 共用），请稍后再试。用量：https://chatgpt.com/settings/usage',
};

export function planErrorMessage(status: number, text: string): string | undefined {
  let code = '', message = '', param = '';
  try { const root = JSON.parse(text); const e = root?.error ?? root; code = String(e?.code ?? e?.type ?? ''); message = String(e?.message ?? root?.detail ?? ''); param = String(e?.param ?? ''); } catch { /* not JSON */ }
  if (PLAN_ERRORS[code]) return PLAN_ERRORS[code];
  if (code === 'subscription_sharing_unsupported_capability') return `ChatGPT 套餐不支持请求里的 ${param || '某项内容'}。`;
  if (status === 403) return PLAN_ERRORS.subscription_sharing_user_not_eligible + (message ? `（${message.slice(0, 120)}）` : '');
  if (status === 429) return PLAN_ERRORS.subscription_sharing_usage_limit_exceeded;
  if (status === 401) return PLAN_ERRORS.subscription_sharing_invalid_user;
  return undefined;
}

function errorMentions(text: string, ...words: string[]) { const t = text.toLowerCase(); return words.some(w => t.includes(w)); }

// ---------- SSE plumbing ----------

interface SseEvent { event?: string; data: string }
async function* sse(body: ReadableStream<Uint8Array>): AsyncGenerator<SseEvent> {
  const reader = body.getReader(); const decoder = new TextDecoder(); let buffer = '';
  try {
    while (true) {
      const { value, done } = await reader.read();
      buffer += decoder.decode(value ?? new Uint8Array(), { stream: !done });
      let at: number;
      while ((at = buffer.search(/\r?\n\r?\n/)) >= 0) {
        const raw = buffer.slice(0, at); buffer = buffer.slice(at).replace(/^\r?\n\r?\n/, '');
        let event: string | undefined; const data: string[] = [];
        for (const line of raw.split(/\r?\n/)) { if (line.startsWith('event:')) event = line.slice(6).trim(); else if (line.startsWith('data:')) data.push(line.slice(5).replace(/^ /, '')); }
        if (data.length || event) yield { event, data: data.join('\n') };
      }
      if (done) { if (buffer.trim()) { const data = buffer.split(/\r?\n/).filter(l => l.startsWith('data:')).map(l => l.slice(5).replace(/^ /, '')).join('\n'); if (data) yield { data }; } return; }
    }
  } finally { reader.releaseLock(); }
}
const encoder = new TextEncoder();
function frame(data: unknown, event?: string) { return encoder.encode(`${event ? `event: ${event}\n` : ''}data: ${typeof data === 'string' ? data : JSON.stringify(data)}\n\n`); }

interface Source { url: string; title?: string }
export function sourcesFooter(sources: Source[]): string {
  const unique = [...new Map(sources.filter(s => /^https?:\/\//i.test(s.url)).map(s => [s.url, s])).values()].slice(0, 20);
  if (!unique.length) return '';
  const clean = (t: string) => t.replace(/[\[\]\n\r]/g, ' ').trim();
  return '\n\n**来源**\n' + unique.map((s, i) => `${i + 1}. [${clean(s.title || new URL(s.url).hostname)}](${s.url})`).join('\n');
}

/** Text of a search step shown as a process item. */
export function searchStepText(action: any, sources: Source[] = []): string {
  const queries: string[] = Array.isArray(action?.queries) ? action.queries.map(String) : action?.query ? [String(action.query)] : [];
  let head: string;
  if (action?.type === 'open_page') head = `${SEARCH_MARK}打开网页：${action.url ?? ''}`;
  else if (action?.type === 'find_in_page' || action?.type === 'find') head = `${SEARCH_MARK}页内查找：${action.pattern ?? ''}${action.url ? ` @ ${action.url}` : ''}`;
  else head = `${SEARCH_MARK}联网搜索：${queries.join('；') || '（模型自带搜索）'}`;
  const list = [...new Map(sources.filter(s => s?.url).map(s => [s.url, s])).values()].slice(0, 8).map(s => `- ${s.title ? `[${String(s.title).replace(/[\[\]\n]/g, ' ')}](${s.url})` : s.url}`);
  return list.length ? `${head}\n${list.join('\n')}` : head;
}

export interface StreamOutcome { failedBeforeOutput?: string; searched: boolean; cited: boolean }

/**
 * Rewrite an OpenAI Responses SSE stream. Search calls become reasoning items; url_citation annotations become a footer.
 * Until the first real output arrives, a failure event is held back so the caller can retry without hosted search.
 */
export async function* transformResponses(body: ReadableStream<Uint8Array>, outcome: StreamOutcome, holdFailures: boolean, plan = false): AsyncGenerator<Uint8Array> {
  const held: Uint8Array[] = []; let released = !holdFailures;
  const release = function* () { if (!released) { released = true; yield* held.splice(0); } };
  const citations = new Map<string, Source[]>();
  for await (const ev of sse(body)) {
    if (ev.data === '[DONE]') { yield* release(); yield frame('[DONE]'); continue; }
    let e: any; try { e = JSON.parse(ev.data); } catch { yield* release(); yield frame(ev.data, ev.event); continue; }
    const type = String(e?.type ?? '');
    if ((type === 'response.failed' || type === 'error') && !released) {
      const err = e.response?.error ?? e.error ?? e;
      outcome.failedBeforeOutput = `${err?.code ?? ''} ${err?.message ?? ''}`.trim() || type;
      return;
    }
    if (plan && (type === 'response.failed' || type === 'error')) {
      const err = e.response?.error ?? e.error ?? e; const friendly = err?.code ? planErrorMessage(0, JSON.stringify({ error: err })) : undefined;
      if (friendly) err.message = friendly;
    }
    if (type.startsWith('response.web_search_call.')) continue;
    if (type === 'response.output_text.annotation.added') {
      const a = e.annotation; if (a?.type === 'url_citation' && a.url) { const list = citations.get(e.item_id) ?? []; list.push({ url: a.url, title: a.title }); citations.set(e.item_id, list); }
      continue;
    }
    if (type === 'response.output_item.added' && e.item?.type === 'web_search_call') continue;
    if (type === 'response.output_item.done' && e.item?.type === 'web_search_call') {
      outcome.searched = true;
      const item = e.item; const text = searchStepText(item.action, Array.isArray(item.action?.sources) ? item.action.sources : []);
      const reasoning = { type: 'reasoning', id: item.id, summary: [{ type: 'summary_text', text }], hoshino_web_search: true };
      const out = [
        frame({ type: 'response.output_item.added', output_index: e.output_index, item: { ...reasoning, summary: [] } }),
        frame({ type: 'response.reasoning_summary_text.delta', item_id: item.id, output_index: e.output_index, summary_index: 0, delta: text }),
        frame({ type: 'response.output_item.done', output_index: e.output_index, item: reasoning }),
      ];
      // A completed search proves the hosted tool works here: stop holding events back.
      held.push(...out); yield* release();
      continue;
    }
    if (type === 'response.output_item.done' && e.item?.type === 'message' && Array.isArray(e.item.content)) {
      const found = [...(citations.get(e.item.id) ?? [])];
      for (const part of e.item.content) for (const a of part?.annotations ?? []) if (a?.type === 'url_citation' && a.url) found.push({ url: a.url, title: a.title });
      const footer = sourcesFooter(found);
      if (footer) {
        outcome.cited = true;
        const index = e.item.content.map((p: any) => p?.type).lastIndexOf('output_text');
        if (index >= 0) {
          e.item.content[index] = { ...e.item.content[index], text: String(e.item.content[index].text ?? '') + footer };
          yield* release();
          yield frame({ type: 'response.output_text.delta', item_id: e.item.id, output_index: e.output_index, content_index: index, delta: footer });
        }
      }
      yield* release(); yield frame(e, ev.event); continue;
    }
    const isOutput = type === 'response.output_item.added' && e.item?.type !== 'reasoning' || type === 'response.output_text.delta' || type === 'response.completed' || type === 'response.incomplete';
    if (isOutput) yield* release();
    if (released) yield frame(e, ev.event); else held.push(frame(e, ev.event));
  }
  yield* release();
}

/** Rewrite a Chat Completions SSE stream: collect annotations/citations and append a footer before the finish chunk. */
export async function* transformChat(body: ReadableStream<Uint8Array>, outcome: StreamOutcome): AsyncGenerator<Uint8Array> {
  const sources: Source[] = []; let footerSent = false; let last: any;
  const footerChunk = () => { footerSent = true; const footer = sourcesFooter(sources); if (!footer) return undefined; outcome.cited = true; return frame({ id: last?.id ?? 'chatcmpl-hoshino', object: 'chat.completion.chunk', created: last?.created ?? 0, model: last?.model ?? '', choices: [{ index: 0, delta: { content: footer }, finish_reason: null }] }); };
  for await (const ev of sse(body)) {
    if (ev.data === '[DONE]') { if (!footerSent) { const f = footerChunk(); if (f) yield f; } yield frame('[DONE]'); continue; }
    let e: any; try { e = JSON.parse(ev.data); } catch { yield frame(ev.data, ev.event); continue; }
    last = e;
    for (const url of Array.isArray(e?.citations) ? e.citations : []) if (typeof url === 'string') sources.push({ url });
    for (const choice of Array.isArray(e?.choices) ? e.choices : []) {
      for (const a of choice?.delta?.annotations ?? choice?.message?.annotations ?? []) {
        const c = a?.url_citation ?? a; if (c?.url) sources.push({ url: c.url, title: c.title });
      }
      if (choice?.delta?.annotations) delete choice.delta.annotations;
      if (choice?.finish_reason && !footerSent) { const f = footerChunk(); if (f) yield f; }
    }
    yield frame(e, ev.event);
  }
  if (!footerSent) { const f = footerChunk(); if (f) yield f; }
}

/** Rewrite an Anthropic Messages SSE stream: server_tool_use / web_search_tool_result become thinking blocks; citations a footer. */
export async function* transformAnthropic(body: ReadableStream<Uint8Array>, outcome: StreamOutcome): AsyncGenerator<Uint8Array> {
  const pending = new Map<number, { kind: 'use' | 'result'; json: string; block: any }>();
  const sources: Source[] = []; let maxIndex = -1;
  const thinking = function* (index: number, text: string) {
    yield frame({ type: 'content_block_start', index, content_block: { type: 'thinking', thinking: '' } }, 'content_block_start');
    yield frame({ type: 'content_block_delta', index, delta: { type: 'thinking_delta', thinking: text } }, 'content_block_delta');
    yield frame({ type: 'content_block_stop', index }, 'content_block_stop');
  };
  for await (const ev of sse(body)) {
    let e: any; try { e = JSON.parse(ev.data); } catch { yield frame(ev.data, ev.event); continue; }
    const type = String(e?.type ?? '');
    if (typeof e?.index === 'number') maxIndex = Math.max(maxIndex, e.index);
    if (type === 'content_block_start' && (e.content_block?.type === 'server_tool_use' || e.content_block?.type === 'web_search_tool_result')) {
      pending.set(e.index, { kind: e.content_block.type === 'server_tool_use' ? 'use' : 'result', json: '', block: e.content_block });
      continue;
    }
    if (type === 'content_block_delta' && pending.has(e.index)) { if (e.delta?.type === 'input_json_delta') pending.get(e.index)!.json += e.delta.partial_json ?? ''; continue; }
    if (type === 'content_block_delta' && e.delta?.type === 'citations_delta') { const c = e.delta.citation; if (c?.url) sources.push({ url: c.url, title: c.title }); continue; }
    if (type === 'content_block_stop' && pending.has(e.index)) {
      const p = pending.get(e.index)!; pending.delete(e.index);
      outcome.searched = true;
      if (p.kind === 'use') {
        let input = p.block.input ?? {}; try { if (p.json) input = JSON.parse(p.json); } catch { /* partial */ }
        yield* thinking(e.index, searchStepText({ type: 'search', query: input?.query }));
      } else {
        const results = Array.isArray(p.block.content) ? p.block.content.filter((r: any) => r?.url).map((r: any) => ({ url: r.url, title: r.title })) : [];
        const failed = p.block.content && !Array.isArray(p.block.content) ? `（搜索失败：${p.block.content.error_code ?? 'unknown'}）` : '';
        yield* thinking(e.index, `${SEARCH_MARK}搜索结果${failed}${results.length ? '\n' + results.slice(0, 8).map((r: Source) => `- [${String(r.title ?? r.url).replace(/[\[\]\n]/g, ' ')}](${r.url})`).join('\n') : ''}`);
      }
      continue;
    }
    if (type === 'message_delta') {
      const footer = sourcesFooter(sources);
      if (footer) {
        outcome.cited = true; const index = ++maxIndex;
        yield frame({ type: 'content_block_start', index, content_block: { type: 'text', text: '' } }, 'content_block_start');
        yield frame({ type: 'content_block_delta', index, delta: { type: 'text_delta', text: footer } }, 'content_block_delta');
        yield frame({ type: 'content_block_stop', index }, 'content_block_stop');
        sources.length = 0;
      }
    }
    if (type === 'content_block_start' && Array.isArray(e.content_block?.citations)) delete e.content_block.citations;
    yield frame(e, ev.event);
  }
}

function streamOf(gen: AsyncGenerator<Uint8Array>): ReadableStream<Uint8Array> {
  return new ReadableStream({
    async pull(controller) { try { const { value, done } = await gen.next(); if (done) controller.close(); else controller.enqueue(value); } catch (error) { controller.error(error); } },
    async cancel() { await gen.return(undefined); },
  });
}

function findRoute(url: URL): { route: SearchRoute; path: string } | undefined {
  const raw = url.href.replace(/[?#].*$/, '');
  for (const route of routes) {
    if (!raw.startsWith(route.base + '/')) continue;
    const path = raw.slice(route.base.length);
    const ok = route.kind === 'anthropic' ? path === '/v1/messages' : route.kind === 'chat' ? path === '/chat/completions' : path === '/responses';
    if (ok) return { route, path };
  }
  return undefined;
}

function jsonResponse(status: number, payload: unknown, headers: Headers) {
  const h = new Headers(headers); h.set('content-type', 'application/json'); h.delete('content-length'); h.delete('content-encoding');
  return new Response(JSON.stringify(payload), { status, headers: h });
}

/** Wrap one model request. Exported for tests; installHostedSearchFetch() wires it into globalThis.fetch. */
export async function hostedFetch(original: typeof fetch, input: any, init: any): Promise<Response> {
  const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input?.url;
  if (!raw || (init?.method ?? 'GET').toUpperCase() !== 'POST' || typeof init?.body !== 'string') return original(input, init);
  let url: URL; try { url = new URL(raw); } catch { return original(input, init); }
  const match = findRoute(url); if (!match) return original(input, init);
  let parsed: any; try { parsed = JSON.parse(init.body); } catch { return original(input, init); }
  const { route } = match; const model = String(parsed?.model ?? '');
  const decision = decide(route, model);
  let headers = new Headers(init.headers);
  const send = (body: any) => { headers.delete('content-length'); return original(raw, { ...init, headers, body: JSON.stringify(body) }); };

  let attemptMethod = decision.method;
  let request = rewriteRequest(route, parsed, attemptMethod, decision.probe);
  let response = await send(request.body);
  let refreshed = false; let refusedHosted = false;
  for (let guard = 0; guard < 4 && !response.ok; guard++) {
    const status = response.status;
    if (status === 401 && route.kind === 'chatgpt' && !refreshed && hooks.refreshCredential) {
      refreshed = true; const token = await hooks.refreshCredential(route).catch(() => undefined);
      if (!token) break;
      headers = new Headers(headers); headers.set('authorization', `Bearer ${token}`);
      await response.body?.cancel().catch(() => undefined); response = await send(request.body); continue;
    }
    if (status < 400 || status >= 500 && !request.hosted) break;
    const text = await response.clone().text().catch(() => '');
    if (status === 400 && Array.isArray(request.body.include) && (errorMentions(text, '"include"', 'reasoning.encrypted_content', 'web_search_call.action.sources') || /"param"\s*:\s*"include/.test(text))) {
      const body = { ...request.body }; delete body.include; request = { ...request, body };
      await response.body?.cancel().catch(() => undefined); response = await send(request.body); continue;
    }
    if (request.hosted && status !== 401 && status !== 429) {
      if (attemptMethod === 'responses:web_search' && errorMentions(text, 'web_search', 'tool')) {
        attemptMethod = 'responses:web_search_preview';
        request = rewriteRequest(route, parsed, attemptMethod);
      } else {
        // The provider refused hosted search: send the original request with Hoshino's own search tool.
        refusedHosted = decision.probe || errorMentions(text, 'web_search', 'web search', 'plugins', 'hosted tool', 'tool type');
        hooks.log?.(`hosted search refused by ${route.provider} (${status}); using Hoshino search`);
        attemptMethod = undefined; request = rewriteRequest(route, parsed, undefined);
      }
      await response.body?.cancel().catch(() => undefined); response = await send(request.body); continue;
    }
    break;
  }
  // Only a plain request that then succeeds proves the provider refused hosted search (not some other request error).
  if (refusedHosted && response.ok) { learned.set(route.provider + '\u0000' + model, 'none'); hooks.report?.(route.provider, model, 'none'); }
  if (!response.ok && route.kind === 'chatgpt') {
    const text = await response.text().catch(() => '');
    const friendly = planErrorMessage(response.status, text);
    if (!friendly) return new Response(text, { status: response.status, statusText: response.statusText, headers: response.headers });
    let code = ''; try { const root = JSON.parse(text); code = String(root?.error?.code ?? root?.error?.type ?? ''); } catch { /* keep empty */ }
    return jsonResponse(response.status, { error: { code: code || `http_${response.status}`, type: code || 'chatgpt_plan_error', message: friendly } }, response.headers);
  }
  if (!response.ok || !response.body || !(response.headers.get('content-type') ?? '').includes('text/event-stream')) return response;

  const outcome: StreamOutcome = { searched: false, cited: false };
  const remember = () => { if (attemptMethod && (outcome.searched || outcome.cited)) { const key = route.provider + '\u0000' + model; if (learned.get(key) !== attemptMethod && route.models[model]?.method !== attemptMethod) hooks.report?.(route.provider, model, attemptMethod); learned.set(key, attemptMethod); } };
  const out = new Headers(response.headers); out.delete('content-length'); out.delete('content-encoding');
  if (route.kind === 'chatgpt' || route.kind === 'responses') {
    const first = response; const hostedRequest = request.hosted;
    const gen = (async function* () {
      yield* transformResponses(first.body!, outcome, hostedRequest, route.kind === 'chatgpt');
      if (outcome.failedBeforeOutput && hostedRequest) {
        hooks.log?.(`hosted search failed mid-stream at ${route.provider}; retrying with Hoshino search`);
        attemptMethod = undefined;
        const retry = await send(rewriteRequest(route, parsed, undefined).body);
        if (!retry.ok || !retry.body) { yield frame({ type: 'error', code: `http_${retry.status}`, message: (planErrorMessage(retry.status, await retry.text().catch(() => '')) ?? `HTTP ${retry.status}`) }); return; }
        const second: StreamOutcome = { searched: false, cited: false };
        yield* transformResponses(retry.body, second, false, route.kind === 'chatgpt');
      } else if (outcome.failedBeforeOutput) {
        yield frame({ type: 'error', message: outcome.failedBeforeOutput });
      }
      remember();
    })();
    return new Response(streamOf(gen), { status: response.status, statusText: response.statusText, headers: out });
  }
  const gen = (async function* () {
    yield* (route.kind === 'anthropic' ? transformAnthropic(response.body!, outcome) : transformChat(response.body!, outcome));
    remember();
  })();
  return new Response(streamOf(gen), { status: response.status, statusText: response.statusText, headers: out });
}

export function installHostedSearchFetch() {
  if (installed) return; installed = true;
  const original = globalThis.fetch.bind(globalThis);
  globalThis.fetch = (async (input: any, init?: any) => {
    try { return await hostedFetch(original, input, init); }
    catch (error) { if ((error as Error)?.name === 'AbortError') throw error; throw error; }
  }) as typeof fetch;
}

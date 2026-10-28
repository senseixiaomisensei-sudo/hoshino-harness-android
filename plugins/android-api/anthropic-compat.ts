/**
 * Third-party Anthropic-compatible endpoints (relays, DeepSeek, etc.) often break on pi-ai's newest
 * Claude-only request fields (adaptive thinking, eager tool streaming, beta headers) and answer 4xx/502.
 * Rewrite requests to those hosts into the plain, widely supported Messages shape. api.anthropic.com is untouched.
 */
const BUDGET: Record<string, number> = { low: 1024, medium: 2048, high: 3072, xhigh: 8192, max: 16000 };
const bases = new Set<string>();
let installed = false;
export function setThirdPartyAnthropicBases(list: string[]) {
  bases.clear();
  for (const base of list) { try { const u = new URL(base); if (u.hostname !== 'api.anthropic.com') bases.add(base.replace(/\/+$/, '')); } catch { /* invalid URL is reported elsewhere */ } }
}
export function sanitizeAnthropicBody(body: any): any {
  if (!body || typeof body !== 'object') return body;
  delete body.betas; delete body.fallbacks; delete body.context_management;
  if (Array.isArray(body.tools)) body.tools = body.tools.map((t: any) => { const { eager_input_streaming, cache_control, strict, defer_loading, ...rest } = t ?? {}; return rest; });
  const effort = body.output_config?.effort; delete body.output_config;
  const thinking = body.thinking;
  if (thinking && typeof thinking === 'object') {
    if (thinking.type === 'adaptive' || thinking.type === 'enabled') {
      const max = Number(body.max_tokens) || 4096;
      const wanted = thinking.type === 'enabled' ? Number(thinking.budget_tokens) || 1024 : BUDGET[String(effort ?? 'medium')] ?? 2048;
      const budget = Math.max(1024, Math.min(wanted, max - 1));
      body.thinking = budget < max ? { type: 'enabled', budget_tokens: budget } : { type: 'disabled' };
    } else body.thinking = { type: thinking.type === 'disabled' ? 'disabled' : thinking.type };
  }
  if (Array.isArray(body.messages)) body.messages = body.messages.filter((m: any) => m?.role === 'user' || m?.role === 'assistant');
  return body;
}
export function installAnthropicCompatFetch() {
  if (installed) return; installed = true;
  const original = globalThis.fetch.bind(globalThis);
  globalThis.fetch = (async (input: any, init?: any) => {
    try {
      const raw = typeof input === 'string' ? input : input instanceof URL ? input.href : input?.url;
      if (raw && bases.size && (init?.method ?? 'GET').toUpperCase() === 'POST' && typeof init?.body === 'string') {
        const url = new URL(raw);
        const match = [...bases].some(b => raw.startsWith(b + '/')) && /\/messages$/.test(url.pathname);
        if (match) {
          url.searchParams.delete('beta');
          const headers = new Headers(init.headers);
          headers.delete('anthropic-beta'); headers.delete('anthropic-dangerous-direct-browser-access');
          for (const k of [...headers.keys()]) if (k.startsWith('x-stainless-')) headers.delete(k);
          const body = JSON.stringify(sanitizeAnthropicBody(JSON.parse(init.body)));
          headers.delete('content-length');
          return original(url.toString(), { ...init, headers, body });
        }
      }
    } catch { /* fall through with the original request */ }
    return original(input, init);
  }) as typeof fetch;
}

import type { Provider } from '../../node-host/src/platform.js';
import { providerBaseURL } from './provider-config.js';
import type { SearchMode, SearchRoute } from './hosted-search.js';

/** Suffix of the extra Responses route for an OpenAI-compatible provider whose models search through Responses. */
export const RESPONSES_ROUTE_SUFFIX = '--responses';
type ModelMeta = NonNullable<NonNullable<Provider['metadata']>['models']>[number];
const LEVELS = ['low', 'medium', 'high', 'xhigh', 'max'] as const;

/** Reasoning levels a model offers, as pi-ai efforts (key = selectable level, value = wire spelling). */
export function reasoningEfforts(p: Provider, model: ModelMeta | undefined, responses: boolean): Record<string, string | null> | undefined {
  const reasoning = model?.reasoning ?? p.type === 'anthropic';
  if (!reasoning) return undefined;
  const levels = model?.reasoningLevels?.map(l => l.toLowerCase());
  if (levels?.length) {
    const efforts: Record<string, string | null> = { off: levels.includes('none') ? 'none' : levels.includes('minimal') ? 'minimal' : null };
    for (const level of LEVELS) if (levels.includes(level)) efforts[level] = level;
    return efforts;
  }
  // No declared levels: the long-standing set. xhigh is offered as its own level only on Responses routes; elsewhere it
  // is the strongest level that protocol already accepts.
  return { off: null, low: 'low', medium: 'medium', high: 'high', xhigh: responses ? 'xhigh' : 'high', max: 'max' };
}

function modelEntry(p: Provider, id: string, responses: boolean) {
  const model = p.metadata?.models?.find(m => m.id === id);
  const efforts = reasoningEfforts(p, model, responses);
  return {
    id, input: model?.vision ? ['text', 'image'] : ['text'],
    ...(efforts ? { reasoningEfforts: efforts } : {}),
    ...(p.type === 'anthropic' && (model?.defaultEffort?.toLowerCase() === 'max' || /^deepseek(?:[-_.]|$)/i.test(id)) ? { compat: { forceAdaptiveThinking: true } } :
      p.type === 'openai-compatible' && !responses && /^deepseek(?:[-_.]|$)/i.test(id) ? { compat: { thinkingFormat: 'deepseek', supportsReasoningEffort: true, maxTokensField: 'max_tokens' } } :
      responses && p.type === 'openai-codex' ? { compat: { supportsMaxOutputTokens: false } } : {}),
    contextWindow: model?.contextLength ?? 128000, maxTokens: model?.maxOutput ?? 4096,
  };
}

function searchMode(model: ModelMeta | undefined): SearchMode {
  const m = model?.webSearchMode; return m === 'on' || m === 'off' ? m : 'auto';
}

/**
 * pi-ai routes for the app's providers, plus the hosted-search view of them.
 * - `anthropic` → anthropic-messages; `openai-compatible` → openai-completions (unchanged).
 * - `openai-codex` (Sign in with ChatGPT) → pi-ai's standard openai-responses at the stored base (api.openai.com/v1);
 *   the plan token is resolved per request through the Android credential seam.
 * - an OpenAI-compatible model whose detected hosted search is Responses-based (and not switched off) is additionally
 *   served by `<id>--responses` speaking openai-responses; the app selects that route for it.
 */
export function buildProviderRoutes(providers: Provider[]): { map: Record<string, object>; search: SearchRoute[] } {
  const map: Record<string, object> = {};
  const search: SearchRoute[] = [];
  for (const p of providers) {
    if (!/^[a-z][a-z0-9-]*$/.test(p.id) || !p.models.length) throw new Error('Invalid provider configuration');
    const chatgpt = p.type === 'openai-codex';
    const base = chatgpt ? p.baseURL.replace(/\/+$/, '') : providerBaseURL(p.type, p.baseURL);
    const common = { apiKeyEnv: p.apiKey, headers: p.headers ?? {}, displayName: p.metadata?.name ?? p.id, retryPolicy: { mode: 'normal', maxRetries: 0 } };
    map[p.id] = {
      ...common,
      api: chatgpt ? 'openai-responses' : p.type === 'anthropic' ? 'anthropic-messages' : 'openai-completions',
      baseURL: base,
      models: p.models.map(id => modelEntry(p, id, chatgpt)),
      // Only session/selectModel's explicit effort may enable thinking.
      ...(p.type === 'anthropic' ? { thinkingBudgets: { low: 1024, medium: 2048, high: 3072 } } : {}),
    };
    const models = Object.fromEntries(p.models.map(id => { const m = p.metadata?.models?.find(x => x.id === id); return [id, { mode: searchMode(m), method: m?.webSearchMethod || undefined }]; }));
    search.push({ route: p.id, provider: p.id, kind: chatgpt ? 'chatgpt' : p.type === 'anthropic' ? 'anthropic' : 'chat', base, models });
    if (p.type === 'openai-compatible') {
      const viaResponses = p.models.filter(id => { const m = p.metadata?.models?.find(x => x.id === id); return searchMode(m) !== 'off' && (m?.webSearchMethod ?? '').startsWith('responses:'); });
      if (viaResponses.length) {
        const route = p.id + RESPONSES_ROUTE_SUFFIX;
        map[route] = { ...common, displayName: `${common.displayName} (Responses)`, api: 'openai-responses', baseURL: base, models: viaResponses.map(id => modelEntry(p, id, true)) };
        search.push({ route, provider: p.id, kind: 'responses', base, models: Object.fromEntries(viaResponses.map(id => [id, models[id]!])) });
      }
    }
  }
  return { map, search };
}

// WP9-B context plugin (CX1 cache ledger, CX2 manual compact + non-canonical overflow recovery + notices,
// CX5 untrusted wrapping, and the /mobile/context control endpoint shared by the WP9-B plugins).
import type { Context } from '../../node-host/src/platform.js';
import { join } from 'node:path';
import { isOverflowFailure, isUntrustedTool, wrapUntrusted, UNTRUSTED_RULE, recordRequest, hitRate, sha } from './core.js';
import { settings, loadSettings, saveSettings, cacheOf, budgetOf, outcomesOf, notify, DEFAULT_SETTINGS, type ContextSettings } from './state.js';
import type {} from '@deepseek-ai/dsh-tools';
import type {} from '@deepseek-ai/dsh-agent';
import type {} from '@deepseek-ai/dsh-system-prompt';

/**
 * Shared WP9-B state for hoshino-turn-policy and hoshino-outcome, handed over as a Cordis service so those plugins
 * never import this plugin's files (EV4 architecture guard, node-host/test/architecture.test.mjs).
 */
export interface HoshinoContextService {
  readonly settings: ContextSettings;
  budgetOf: typeof budgetOf;
  outcomesOf: typeof outcomesOf;
  notify: typeof notify;
}
declare module '@deepseek-ai/cordis' { interface Context { hoshinoContext: HoshinoContextService } }

export const name = 'hoshino-context';
export const inject = ['androidBridge', 'agents', 'webServer', 'connection'];

const textBlocks = (content: readonly any[]) => content.filter(b => b?.type === 'text').map(b => String(b.text)).join('\n');

export async function apply(ctx: Context) {
  const bootstrap = ctx.androidBridge.bootstrap;
  await loadSettings(bootstrap.selfHome ?? join(bootstrap.home, 'hoshino'));
  ctx.provide('hoshinoContext', { settings, budgetOf, outcomesOf, notify } satisfies HoshinoContextService);

  // ---------------------------------------------------------------- CX5: one stable rule + wrap external content once.
  ctx.on('system-prompt/assemble', async (assembly: any, _context: any, next: () => Promise<any>) => {
    if (settings.untrusted.enabled) assembly.sections.push({ name: 'hoshino-untrusted', text: UNTRUSTED_RULE, interpolate: false });
    return next();
  });
  ctx.on('tools/post-execute', async (exec: any, result: any, next: () => Promise<any>) => {
    const decision = await next();
    if (!settings.untrusted.enabled || result.isError || decision?.kind !== 'accept' || decision.value !== undefined) return decision;
    if (!isUntrustedTool(exec.name, settings.untrusted.extraTools)) return decision;
    const content = (decision.content ?? result.content) as any[];
    if (!Array.isArray(content) || !content.some(b => b?.type === 'text' && b.text)) return decision;
    return { ...decision, content: content.map(b => b?.type === 'text' && b.text ? { ...b, text: wrapUntrusted(String(b.text), exec.name) } : b) };
  });

  // ---------------------------------------------------------------- CX1: measure prefix stability and cache hits.
  ctx.on('session/event', (session: any, event: any) => {
    try {
      if (event.type === 'assistant/message') {
        const header = session.requestHeader?.();
        const nodes = session.surface?.nodes ?? [];
        const head = nodes.length ? session.eventAt(nodes[0]) : undefined;
        const system = head?.type === 'system/message' ? sha(textBlocks(head.data?.message?.content ?? head.data?.content ?? [])) : '';
        const tools = sha(JSON.stringify((header?.tools ?? []).map((t: any) => [t.name, t.description, t.parameters])));
        const model = `${header?.config?.provider ?? ''}/${header?.config?.model ?? ''}`;
        recordRequest(cacheOf(session.id), { system, tools, model, generation: session.surface?.replaceGeneration ?? 0 }, event.data?.usage);
      } else if (event.type === 'compaction/summary') {
        const cache = cacheOf(session.id); cache.compactions++;
        const manual = event.data?.sourceCommandId !== undefined;
        const n = event.data?.shadowedSeqs?.length ?? 0; const tokens = event.data?.shadowedTokenCount ?? 0;
        const how = event.data?.model === 'hoshino-rules' ? '无模型摘要' : '模型摘要';
        if (!manual) notify(ctx.androidBridge, session.id, 'compaction', `上下文快满，已自动压缩：折叠 ${n} 条较早记录（约 ${tokens} tokens，${how}），你说过的原话已逐字保留。`, { shadowed: n, tokens, manual });
      }
    } catch { /* observation only */ }
  });

  // ---------------------------------------------------------------- CX2: providers that report overflow in their own words.
  const overflowTried = new Map<string, number>();
  ctx.on('agent/request-error', async (payload: any, next: () => Promise<any>) => {
    const { agent, failure, signal } = payload;
    // Canonical CONTEXT_WINDOW_EXCEEDED is owned by the original engine; we only cover unclassified wording.
    if (signal.aborted || failure.code === 'CONTEXT_WINDOW_EXCEEDED' || !isOverflowFailure(failure)) return next();
    const engine = ctx.get('compaction') as any;
    const key = `${agent.id}:${payload.turn}`;
    if (!engine || (overflowTried.get(key) ?? 0) >= 2) return next();
    overflowTried.set(key, (overflowTried.get(key) ?? 0) + 1);
    if (overflowTried.size > 256) overflowTried.delete(overflowTried.keys().next().value as string);
    const generation = agent.session.surface.replaceGeneration;
    try { await engine.compactIfNeeded(agent, 'context-overflow', signal); } catch (error) { ctx.logger.warn('overflow compaction failed: %s', error instanceof Error ? error.message.slice(0, 200) : 'error'); }
    if (!signal.aborted && agent.session.surface.replaceGeneration > generation) return { kind: 'retry' };
    return next();
  }, { prepend: true } as any);

  // ---------------------------------------------------------------- /mobile/context (same cookie admission as /mobile/control).
  const compact = async (sessionId: string) => {
    const agent = ctx.agents.get(sessionId as any) as any;
    if (!agent) throw Error('会话尚未在内核中加载：先打开这个会话或发一条消息后再压缩');
    const commands = ctx.get('commands') as any;
    const signal = AbortSignal.timeout(180_000);
    if (commands) {
      const execution = await commands.execute(agent, '/compact', [], signal);
      if (execution) return { ok: execution.result.kind === 'success', text: execution.result.text ?? '', source: 'command' };
    }
    const engine = ctx.get('compaction') as any;
    if (!engine) throw Error('压缩服务未装载');
    const result = await engine.compactNow(agent, signal);
    return { ok: true, text: result ? `Compacted ${result.shadowedSeqs.length} history items (~${result.shadowedTokenCount} tokens).` : 'No compactable history yet.', source: 'engine' };
  };
  const status = (sessionId: string) => {
    const cache = cacheOf(sessionId);
    const engine = ctx.get('compaction') as any;
    return {
      cache: { requests: cache.requests, inputTokens: cache.inputTokens, cachedTokens: cache.cachedTokens, cacheWriteTokens: cache.cacheWriteTokens, hitRate: hitRate(cache), prefixBreaks: cache.prefixBreaks.slice(-10), compactions: cache.compactions },
      compaction: { loaded: !!engine, strategy: settings.compaction.strategy, modelSummaries: engine?.modelSummaries ?? 0, ruleSummaries: engine?.ruleSummaries ?? 0 },
      budget: budgetOf(sessionId),
      outcomes: outcomesOf(sessionId).slice(-10),
    };
  };
  ctx.effect(() => ctx.webServer.register({ kind: 'exact', path: '/mobile/context', handler: async (req: any, res: any) => {
    if (req.method !== 'POST' || 'rejection' in ctx.connection.admit(req)) { res.writeHead(403).end(); return; }
    try {
      let raw = ''; for await (const chunk of req) { raw += chunk; if (raw.length > 262144) throw Error('Request too large'); }
      const input = JSON.parse(raw || '{}') as { action?: string; sessionId?: string; settings?: unknown };
      const sessionId = String(input.sessionId ?? '');
      let value: unknown;
      switch (input.action) {
        case 'compact': value = await compact(sessionId); break;
        case 'status': value = status(sessionId); break;
        case 'settings': value = input.settings === undefined ? settings : await saveSettings(input.settings); break;
        case 'defaults': value = DEFAULT_SETTINGS; break;
        default: throw Error('Unknown context action');
      }
      res.writeHead(200, { 'Content-Type': 'application/json' }).end(JSON.stringify(value));
    } catch (failure) {
      res.writeHead(400, { 'Content-Type': 'application/json' }).end(JSON.stringify({ error: failure instanceof Error ? failure.message.slice(0, 600) : 'Context operation failed' }));
    }
  } } as any));
}

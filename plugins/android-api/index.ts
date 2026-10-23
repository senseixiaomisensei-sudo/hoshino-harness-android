import { randomBytes, timingSafeEqual } from 'node:crypto';
import type { Context, Provider } from '../../node-host/src/platform.js';
import * as piAi from '@deepseek-ai/dsh-llm-pi-ai';
import { HostConnectionService } from '@deepseek-ai/dsh-client-connection';
import '@deepseek-ai/dsh-host-webserver';
import '@deepseek-ai/dsh-api-session-controller';
export const name = 'android-api';
export const inject = ['llm', 'connection', 'webServer', 'sessionController', 'androidBridge'];
export async function apply(ctx: Context): Promise<void> {
  let providerFiber: ReturnType<Context['plugin']> | undefined;
  const reload = async () => {
    const providers = await ctx.androidBridge.call<Provider[]>('providers');
    const map: Record<string, object> = {};
    for (const p of providers) {
      if (!/^[a-z][a-z0-9-]*$/.test(p.id) || !p.models.length) throw new Error('Invalid provider configuration');
      map[p.id] = {
        api: p.type === 'anthropic' ? 'anthropic-messages' : 'openai-completions',
        baseURL: p.baseURL, apiKeyEnv: p.apiKey,
        models: p.models.map(id => ({ id, ...(p.type === 'anthropic' ? { reasoningEfforts: { medium: 'medium' } } : {}), contextWindow: 128000, maxTokens: 4096 })),
        ...(p.type === 'anthropic' ? { reasoning: 'medium', thinkingBudgets: { medium: 1024 } } : {}),
        retryPolicy: { mode: 'normal', maxRetries: 0 },
      };
    }
    await providerFiber?.dispose();
    providerFiber = ctx.plugin(piAi, { providers: map });
    await providerFiber;
  };
  await reload();
  const token = randomBytes(32).toString('hex');
  let exchanged = false;
  ctx.effect(() => ctx.webServer.register({ kind: 'exact', path: '/mobile/connect', handler: (req, res) => {
    const url = new URL(req.url!, 'http://127.0.0.1');
    const candidate = Buffer.from(url.searchParams.get('token') ?? '');
    if (req.method !== 'GET' || exchanged || candidate.length !== token.length || !timingSafeEqual(candidate, Buffer.from(token))) {
      res.writeHead(401).end(); return;
    }
    exchanged = true;
    // Exchange via dsh's own API authentication; native clients retain its cookie and Mux.
    const internal = new URL(ctx.connection.authenticatedUrl(`http://127.0.0.1:${ctx.webServer.port}/`));
    req.url = `/connect?token=${encodeURIComponent(internal.searchParams.get('token')!)}&interactions=none`;
    if (!(ctx.connection instanceof HostConnectionService)) throw new Error('HTTP Connection carrier required');
    ctx.connection.authorizeConnect(req, res);
  } }));
  ctx.effect(() => ctx.webServer.register({ kind: 'exact', path: '/mobile/providers', handler: async (req, res) => {
    if (req.method !== 'POST' || 'rejection' in ctx.connection.admit(req)) { res.writeHead(403).end(); return; }
    try { await reload(); res.writeHead(200, { 'Content-Type': 'application/json' }).end('{}'); }
    catch { res.writeHead(400).end(); }
  } }));
  ctx.provide('mobileApi', { port: ctx.webServer.port, token });
}

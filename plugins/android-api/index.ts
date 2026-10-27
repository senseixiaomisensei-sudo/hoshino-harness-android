import { randomBytes, timingSafeEqual } from 'node:crypto';
import type { Context, Provider } from '../../node-host/src/platform.js';
import * as piAi from '@deepseek-ai/dsh-llm-pi-ai';
import { HostConnectionService } from '@deepseek-ai/dsh-client-connection';
import '@deepseek-ai/dsh-host-webserver';
import '@deepseek-ai/dsh-api-session-controller';
import type {} from '../hoshino-self/index.js';
import { providerBaseURL } from './provider-config.js';
export const name = 'android-api';
export const inject = ['llm', 'connection', 'webServer', 'sessionController', 'androidBridge', 'hoshinoControl'];
export async function apply(ctx: Context): Promise<void> {
  let providerFiber: ReturnType<Context['plugin']> | undefined;
  const reload = async () => {
    const providers = await ctx.androidBridge.call<Provider[]>('providers');
    const map: Record<string, object> = {};
    for (const p of providers) {
      if (!/^[a-z][a-z0-9-]*$/.test(p.id) || !p.models.length) throw new Error('Invalid provider configuration');
      map[p.id] = {
        api: p.type === 'anthropic' ? 'anthropic-messages' : 'openai-completions',
        baseURL: providerBaseURL(p.type, p.baseURL), apiKeyEnv: p.apiKey, headers: p.headers ?? {}, displayName: p.metadata?.name ?? p.id,
        models: p.models.map(id => {
          const model=p.metadata?.models?.find(model=>model.id===id);
          const reasoning=model?.reasoning ?? p.type==='anthropic';
          return { id, input: model?.vision ? ['text','image'] : ['text'],
            ...(reasoning ? { reasoningEfforts: { off: null, low: 'low', medium: 'medium', high: 'high', max: 'max' } } : {}),
            ...(p.type==='anthropic' && (model?.defaultEffort?.toLowerCase()==='max' || /^deepseek(?:[-_.]|$)/i.test(id)) ? {compat:{forceAdaptiveThinking:true}} :
              p.type!=='anthropic' && /^deepseek(?:[-_.]|$)/i.test(id) ? {compat:{thinkingFormat:'deepseek',supportsReasoningEffort:true,maxTokensField:'max_tokens'}} : {}),
            contextWindow: model?.contextLength ?? 128000, maxTokens: model?.maxOutput ?? 4096 };
        }),
        // Only session/selectModel's explicit effort may enable thinking.
        ...(p.type === 'anthropic' ? { thinkingBudgets: { low: 1024, medium: 2048, high: 3072 } } : {}),
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
  ctx.effect(()=>ctx.webServer.register({kind:'exact',path:'/mobile/control',handler:async(req,res)=> {
    if(req.method!=='POST' || 'rejection' in ctx.connection.admit(req)){res.writeHead(403).end();return;}
    try {
      const chunks:Buffer[]=[];let size=0;
      for await(const chunk of req){const bytes=Buffer.from(chunk);size+=bytes.length;if(size>8388608)throw new Error('Request too large');chunks.push(bytes);}
      const input=JSON.parse(Buffer.concat(chunks).toString('utf8')) as {action:string;request:Record<string,unknown>};
      const value=await ctx.hoshinoControl.invoke(input.action,input.request ?? {});
      res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(value));
    } catch(failure) {
      const message=failure instanceof SyntaxError?'Invalid JSON configuration; check syntax':failure instanceof Error?failure.message.slice(0,1200):'Control operation failed; check arguments or rollback';
      res.writeHead(400,{'Content-Type':'application/json'}).end(JSON.stringify({error:message.replace(/Bearer\s+[^\s"']+/gi,'Bearer [redacted]').replace(/\bsk-[a-zA-Z0-9_-]{8,}/g,'[redacted]')}));
    }
  }}));
}

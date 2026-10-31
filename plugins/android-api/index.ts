import { randomBytes, timingSafeEqual } from 'node:crypto';
import {readFile} from 'node:fs/promises';
import {createRequire} from 'node:module';
import type { Context, Provider } from '../../node-host/src/platform.js';
import * as piAi from '@deepseek-ai/dsh-llm-pi-ai';
import { HostConnectionService } from '@deepseek-ai/dsh-client-connection';
import '@deepseek-ai/dsh-host-webserver';
import '@deepseek-ai/dsh-api-session-controller';
import type {} from '../hoshino-self/index.js';
import { providerBaseURL } from './provider-config.js';
import { installAnthropicCompatFetch, setThirdPartyAnthropicBases } from './anthropic-compat.js';
import { installHostedSearchFetch, setHostedSearchRoutes } from './hosted-search.js';
import { buildProviderRoutes } from './routes.js';
export const name = 'android-api';
export const inject = ['llm', 'connection', 'webServer', 'sessionController', 'androidBridge', 'hoshinoControl'];
export async function apply(ctx: Context): Promise<void> {
  let providerFiber: ReturnType<Context['plugin']> | undefined;
  const reload = async () => {
    const providers = await ctx.androidBridge.call<Provider[]>('providers');
    // Load only the enabled providers' local wire implementation before reporting chat-ready. No network probe.
    // pi-ai exports ./api/<file>; the Anthropic wire file is anthropic-messages. Warm-up only: a failure here must never stop the kernel.
    const {map, search} = buildProviderRoutes(providers);
    await Promise.all([...new Set(Object.values(map).map(r=>String((r as {api?:string}).api)))].map(api=>import('@earendil-works/pi-ai/api/'+api).catch(()=>undefined)));
    installAnthropicCompatFetch();
    installHostedSearchFetch();
    setThirdPartyAnthropicBases(providers.filter(p=>p.type==='anthropic').map(p=>{try{return providerBaseURL(p.type,p.baseURL);}catch{return '';}}));
    setHostedSearchRoutes(search, {
      // ChatGPT plan tokens are refreshed by Android; ask for a fresh one after a 401.
      refreshCredential: async route => { const p=providers.find(x=>x.id===route.provider); if(!p) return undefined; const hit=await ctx.androidBridge.call<{value?:string}>('credential',{ref:p.apiKey,fresh:true}); return hit?.value || undefined; },
      report: (provider, model, method) => { void ctx.androidBridge.call('web-search-capability',{provider,model,method}).catch(()=>undefined); },
      log: message => console.error('hoshino: '+message),
    });
    await providerFiber?.dispose();
    providerFiber = ctx.plugin(piAi, { providers: map });
    await providerFiber;
  };
  // One rejected provider must never keep the whole kernel offline; start with none and let the app report the provider error.
  await reload().catch(async()=>{console.error('hoshino: provider configuration rejected; kernel started without providers');await providerFiber?.dispose();providerFiber=ctx.plugin(piAi,{providers:{}});await providerFiber;});
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
  const dshVersion=JSON.parse(await readFile(createRequire(import.meta.url).resolve('@deepseek-ai/dsh/package.json'),'utf8')).version;
  ctx.provide('mobileApi', { port: ctx.webServer.port, token, dshVersion });
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

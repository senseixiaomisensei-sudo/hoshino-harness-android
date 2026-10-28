import type {Context} from '../../node-host/src/platform.js';
import {defineTool} from '@deepseek-ai/dsh-tools';
import {readFile,writeFile,mkdir,readdir,lstat,appendFile} from 'node:fs/promises';
import {join,dirname,relative} from 'node:path';
import {Configuration,modes,defaults,interpolate,treeStamp,atomic,type Data} from './config.js';
import {approval} from './approval.js';
import {fetchPage,search,webUrl} from './web.js';
import {safePublicPath,checkpoint,restoreCheckpoint,installPlugin,gitOperation,ensureTools,exportBackup,restoreBackup,selfSnapshot,command} from './operations.js';
import {UI_SCHEMA,validateOverrides} from '../hoshino-self/overrides.js';
import {workspacePath} from '../android-policy/paths.js';
import type {} from '@deepseek-ai/dsh-goal';
import type {} from '@deepseek-ai/dsh-schedule';
import type {} from '@deepseek-ai/dsh-subagent';
import {createRequire} from 'node:module';
import {createScope,type Scope} from '@deepseek-ai/dsh-scope';
export interface MobileM2 {config:Configuration;syncTools(sessionId:string):void;invoke(action:string,args:Data):Promise<any>;approve(action:Data,ask:()=>Promise<{approved:boolean;remember?:boolean}>):Promise<{approved:boolean;remember?:boolean}>}
declare module '@deepseek-ai/cordis'{interface Context{mobileM2:MobileM2}}
export const name='hoshino-m2';
export const inject=['androidBridge','mobilePolicy','systemPrompt','tools','agents'];
export async function apply(ctx:Context){
 const root=ctx.androidBridge.bootstrap.selfHome??join(ctx.androidBridge.bootstrap.home,'hoshino');const workspace=ctx.androidBridge.bootstrap.workspace;
 const config=new Configuration(root);await config.reload();
 const safe=ctx.androidBridge.bootstrap.safeMode===true;
 void ctx.androidBridge.whenReady.then(async()=>{
 const sdk=join(root,'plugin-packages','node_modules','@hh','bridge');await mkdir(sdk,{recursive:true});
 await atomic(join(sdk,'package.json'),{name:'@hh/bridge',version:'1.0.0',type:'module',main:'index.js',types:'index.d.ts',license:'MIT'});
 await writeFile(join(sdk,'index.js'),await readFile(new URL('../hh-bridge/index.js',import.meta.url)));
 await writeFile(join(sdk,'index.d.ts'),await readFile(new URL('../hh-bridge/index.d.ts',import.meta.url)).catch(()=>Buffer.from('export declare function createBridge(ctx:any):any;')));
 await mkdir(join(root,'ui'),{recursive:true});await atomic(join(root,'self','ui.schema.json'),UI_SCHEMA);
 }).catch(()=>{});
 const originalCall=ctx.androidBridge.call.bind(ctx.androidBridge);
 const bridge=async<T=Data>(action:string,request:Data={}):Promise<T>=>{const result=await originalCall<T>('bridge-v1',{version:1,action,request});if(result&&(result as Data).ok===false&&(result as Data).error)throw Error(String((result as Data).error));return result;};
 // All existing plugins retain their approval seam, now backed by the selected policy.
 ctx.androidBridge.call=async function<T>(method:string,body:unknown={},signal?:AbortSignal):Promise<T>{
  if(method==='approve')return approval(ctx,config,body as Data,()=>originalCall('approve',body,signal)) as Promise<T>;
  return originalCall<T>(method,body,signal);
 };
 ctx.effect(()=>()=>{ctx.androidBridge.call=originalCall;});
 const modeFor=(sid:string)=>{const o=ctx.mobilePolicy.options.get(sid) as Data|undefined;return (config.get('modes.json').modes??modes).find((m:Data)=>m.id===o?.mode)??modes[1]!;};
 const toolMasks=new Map<string,{scope:Scope;release?:()=>void}>();let syncingTools=false;
 const syncTools=(id:string)=>{if(syncingTools)return;const item=toolMasks.get(id);if(!item)return;syncingTools=true;try{item.release?.();item.release=undefined;const mode=modeFor(id);if(Array.isArray(mode.tools)){const deny=ctx.tools.definitions().map(t=>t.name).filter(name=>!mode.tools.includes(name));if(deny.length)item.release=item.scope.ctx.tools.restrict({deny});}}finally{syncingTools=false;}};
 ctx.on('agent/created',({agent})=>{toolMasks.set(agent.id,{scope:createScope(ctx,agent)});syncTools(agent.id);return undefined;});
 ctx.on('agent/disposed',({agent})=>{const item=toolMasks.get(agent.id);toolMasks.delete(agent.id);if(item)void item.scope.dispose();});
 ctx.on('tools/change',()=>{if(!syncingTools)for(const id of toolMasks.keys())syncTools(id);});
 ctx.effect(()=>()=>Promise.all([...toolMasks.values()].map(v=>v.scope.dispose())).then(()=>{}));
 const fallbackRoutes=new Map<string,Data>();const failedRoutes=new Map<string,Set<string>>();
 ctx.on('agent/request',async(payload,next)=>{
  const id=payload.agent.id;const mode=modeFor(id);if(typeof mode.maxSteps==='number'&&payload.step>=mode.maxSteps)throw Error('Configured maximum Agent steps reached');
  const call={...await next()};const failed=fallbackRoutes.get(id);if(failed&&failed.turn!==payload.turn)fallbackRoutes.delete(id);const override=(failed?.turn===payload.turn?failed:undefined)??mode.model;
  if(override?.providerId&&override?.modelId){call.provider=String(override.providerId);call.model=String(override.modelId);delete call.reasoningEffort;}
  if(typeof mode.temperature==='number')call.temperature=Math.min(2,Math.max(0,mode.temperature));
  if(typeof mode.maxOutputTokens==='number')call.maxTokens=Math.max(1,Math.floor(mode.maxOutputTokens));
  return call;
 });
 ctx.on('agent/request-error',async(payload,next)=>{
  if(payload.signal.aborted)return next();const key=payload.agent.id+':'+payload.turn;const tried=failedRoutes.get(key)??new Set<string>();tried.add(payload.provider);failedRoutes.set(key,tried);
  const route=(config.get('routing.json').fallback??[]).find((r:Data)=>r.providerId&&r.modelId&&!tried.has(r.providerId));
  if(!route)return next();fallbackRoutes.set(payload.agent.id,{...route,turn:payload.turn});return {kind:'retry'};
 });
 const promptLayers=async(sid:string)=>{
  const p=config.get('prompts.json');const mode=modeFor(sid);const layers:Data[]=[{source:'global',...(p.global??{})}];
  for(const name of ['AGENTS.md','HOSHINO.md'])try{layers.push({source:name,operation:'append',text:await readFile(await workspacePath(workspace,name),'utf8')});}catch{}
  layers.push({source:'mode:'+mode.id,operation:mode.promptOperation??'append',text:mode.systemPrompt??''},{source:'session',...(p.sessions?.[sid]??{})});return layers;
 };
 const workspacePrompts=new Map<string,string>();
 async function refresh(){if(safe){for(const [k,v]of Object.entries(defaults))config.values.set(k,structuredClone(v));workspacePrompts.clear();}else{await config.reload();for(const file of ['AGENTS.md','HOSHINO.md'])workspacePrompts.set(file,await readFile(join(workspace,file),'utf8').catch(()=>''));}}
 await refresh();
 ctx.on('system-prompt/assemble',async(assembly,context,next)=>{
  const sid=ctx.agents.currentInitiator()?.id??(context as Data).hoshinoSessionId??'';const p=config.get('prompts.json');const mode=modeFor(sid);
  const variables={date:new Date().toISOString().slice(0,10),time:new Date().toISOString(),workspace,mode:mode.id,model:String((ctx.mobilePolicy.options.get(sid) as Data)?.model??''),device:'Android',language:'zh-CN',memory:'',...p.variables};
  const layers=[{source:'base-custom',...(p.base??{})},{source:'global',...(p.global??{})},...Array.from(workspacePrompts,([source,text])=>({source,operation:'append',text})),{source:'mode:'+mode.id,operation:mode.promptOperation??'append',text:mode.systemPrompt??''},{source:'session',...(p.sessions?.[sid]??{})}];
  for(const layer of layers){if(layer.operation==='replace')assembly.sections=[];if(layer.text)assembly.sections.push({name:'hoshino-'+layer.source,text:interpolate(String(layer.text),variables),interpolate:false});}
  if(p.injectTools!==false)assembly.sections.push({name:'hoshino-tool-protocol',text:'Call available tools using the model provider native tool-call protocol and declared JSON schemas. Never invent tool results.',interpolate:false});
  assembly.variables={...assembly.variables,...Object.fromEntries(Object.entries(variables).map(([k,v])=>[k,String(v)]))};return next();
 });
 ctx.on('tools/pre-execute',async(exec,next)=>{const mode=modeFor(exec.agent?.id??'');if(Array.isArray(mode.tools)&&!mode.tools.includes(exec.name))return {kind:'deny',reason:'Tool excluded by Agent mode'};if(mode.readOnly&&!['read','read_image','get_device_info','self_read','self_inspect','self_doctor','self_diff','skill','self_config_read'].includes(exec.name))return {kind:'deny',reason:'Read-only mode'};return next();});
 let stamp='';let checking=false;let backgroundReady=false;void ctx.androidBridge.whenReady.then(async()=>{stamp=await treeStamp(root);backgroundReady=true;}).catch(()=>{});
 const timer=setInterval(()=>{if(!backgroundReady||checking)return;checking=true;void(async()=>{const current=await treeStamp(root);if(current!==stamp){const previous=stamp;stamp=current;await refresh();if(!safe){const control=ctx.get('hoshinoControl');if(control){await control.invoke('reload',{plugin:'skills'});const disabled=JSON.parse(await readFile(join(root,'config/disabled-plugins.json'),'utf8').catch(()=>'[]'));for(const entry of await readdir(join(root,'plugins'),{withFileTypes:true}).catch(()=>[])){if(!entry.isDirectory()||disabled.includes(entry.name))continue;const prefix=join(root,'plugins',entry.name);if(current.split('\n').filter(s=>s.startsWith(prefix)).join('\n')!==previous.split('\n').filter(s=>s.startsWith(prefix)).join('\n'))await control.invoke('reload',{plugin:entry.name}).catch(()=>{});}}}await originalCall('self-changed',{});}})().catch(()=>{}).finally(()=>{checking=false;});},1500).unref();
 ctx.effect(()=>()=>clearInterval(timer));
 ctx.on('schedule/changed',()=>{if(!ctx.get('schedule'))return;void ctx.schedule.catalog().then(entries=>bridge('scheduler.sync',{entries})).catch(()=>{});});
 ctx.on('tools/execute',async(exec,next)=>{const start=Date.now();try{return await next();}finally{await mkdir(join(root,'audit'),{recursive:true});await appendFile(join(root,'audit','actions.jsonl'),JSON.stringify({at:new Date().toISOString(),sessionId:exec.agent?.id,tool:exec.name,durationMs:Date.now()-start})+'\n');}});
 async function sourceFiles(dir:string,prefix=''):Promise<Data[]>{
  const out:Data[]=[];
  for(const e of await readdir(dir,{withFileTypes:true})){if(['.git','node_modules','build','.gradle','.research','local.properties','.hoshino-source-manifest.json'].includes(e.name)||/\.(keystore|jks)$/.test(e.name))continue;const path=join(dir,e.name);const s=await lstat(path);if(s.isSymbolicLink())continue;const rel=prefix+e.name;if(e.isDirectory())out.push(...await sourceFiles(path,rel+'/'));else if(s.isFile()){if(s.size>4*1024*1024)throw Error('Source file exceeds 4 MiB; split it before remote build: '+rel);const bytes=await readFile(path);out.push({path:rel,...(bytes.includes(0)?{binary:bytes.toString('base64')}:{content:bytes.toString('utf8')}),mode:e.name==='gradlew'?'100755':'100644',type:'blob'});}}
  if(out.length>3000)throw Error('Source tree too large');return out;
 }
 const github=(path:string,method='GET',body?:Data)=>bridge<Data>('github.request',{path,method,...(body?{body}:{})});
 async function remoteBuild(args:Data){
  const cfg=config.get('build.json');if(!/^[\w.-]+\/[\w.-]+$/.test(cfg.repository))throw Error('Set your GitHub repository in credentials/build settings');
  const repo='/repos/'+cfg.repository;const info=await github(repo);const base=String(args.base??info.default_branch);const ref=await github(repo+'/git/ref/heads/'+encodeURIComponent(base));const commit=await github(repo+'/git/commits/'+ref.object.sha);
  const selfSnap=await selfSnapshot(root);
  const gradle=join(root,'source/app/build.gradle.kts');const previous=await readFile(gradle,'utf8').catch(()=>'');
  if(previous){const code=Number(previous.match(/versionCode\s*=\s*(\d+)/)?.[1]??200);const caps=await bridge('capabilities');await writeFile(gradle,previous.replace(/versionCode\s*=\s*\d+/,`versionCode = ${Math.max(code,Number(caps.versionCode??0))+1}`));}
  const files=await sourceFiles(join(root,'source'));if(!files.length)throw Error('Harness source working copy is not installed');
  const original=JSON.parse(await readFile(join(root,'source/.hoshino-source-manifest.json'),'utf8').catch(()=>'[]')) as string[];const present=new Set(files.map(f=>f.path));for(const path of original)if(!present.has(path))files.push({path,mode:'100644',type:'blob',sha:null});
  for(const file of files)if(file.binary){const blob=await github(repo+'/git/blobs','POST',{content:file.binary,encoding:'base64'});file.sha=blob.sha;delete file.binary;}
  const snap=await checkpoint(root,workspace);const tree=await github(repo+'/git/trees','POST',{base_tree:commit.tree.sha,tree:files});const next=await github(repo+'/git/commits','POST',{message:String(args.message??'feat: self update Hoshino Harness'),tree:tree.sha,parents:[ref.object.sha]});
  const branch=String(cfg.branch??'hoshino/self-update')+'-'+Date.now();await github(repo+'/git/refs','POST',{ref:'refs/heads/'+branch,sha:next.sha});await github(repo+'/actions/workflows/'+encodeURIComponent(cfg.workflow??'m2.yml')+'/dispatches','POST',{ref:branch});
  await atomic(join(root,'config','last-build.json'),{repository:cfg.repository,branch,sha:next.sha,snapshot:snap,selfSnapshot:selfSnap});return {repository:cfg.repository,branch,sha:next.sha,snapshot:snap,selfSnapshot:selfSnap,url:`https://github.com/${cfg.repository}/actions`,status:'queued'};
 }
 const invoke=async(action:string,args:Data):Promise<any>=>{
  switch(action){
   case 'state':return {version:'0.2.0-m2',modes:config.get('modes.json').modes,configuration:Object.fromEntries(config.values),errors:Object.fromEntries(config.errors),schema:UI_SCHEMA,safeMode:ctx.androidBridge.bootstrap.safeMode??false};
   case 'read':{const path=await safePublicPath(root,String(args.path));const s=await lstat(path);if(s.size>2*1024*1024)throw Error('Read in smaller pages');return {path:args.path,content:await readFile(path,'utf8')};}
   case 'write':case 'apply':{
    const path=await safePublicPath(root,String(args.path));const content=String(args.content??'');if(Buffer.byteLength(content)>2*1024*1024)throw Error('Public text edit exceeds 2 MiB');
    if(/(?:api[_-]?key|authorization|access[_-]?token|password)\s*["']?\s*:/i.test(content))throw Error('Store credentials in Android Keystore');
    if(String(args.path).endsWith('.json')){const parsed=JSON.parse(content);if(args.path==='ui/overrides.json')validateOverrides(parsed);}
    const id=await selfSnapshot(root);const before=await readFile(path,'utf8').catch(()=>null);await mkdir(dirname(path),{recursive:true});await writeFile(path,content);await refresh();await originalCall('self-changed',{});return {written:args.path,snapshot:id,before,after:content};
   }
   case 'diff':{const current=await readFile(await safePublicPath(root,String(args.path)),'utf8').catch(()=>'');return {path:args.path,before:current,after:String(args.content??''),changed:current!==String(args.content??'')};}
   case 'reload':await refresh();await originalCall('self-changed',{});return {reloaded:true,errors:Object.fromEntries(config.errors)};
   case 'snapshot':return {id:await selfSnapshot(root),workspaceCheckpoint:await checkpoint(root,workspace)};
   case 'rollback-workspace':{try{await lstat(join(root,'checkpoints',String(args.id)));return restoreCheckpoint(root,workspace,String(args.id));}catch(e){if((e as NodeJS.ErrnoException).code!=='ENOENT')throw e;return ctx.hoshinoControl.invoke('rollback',{id:args.id});}}
   case 'doctor':return {kernel:'ready',dsh:'0.2.2-alpha.2',node:process.version,configErrors:Object.fromEntries(config.errors),bridge:await bridge('capabilities'),safeMode:ctx.androidBridge.bootstrap.safeMode??false};
   case 'config':{const name=String(args.name);if(args.value!==undefined){await config.write(name,args.value);await originalCall('self-changed',{});}return config.get(name);}
   case 'prompt-preview':{const sid=String(args.sessionId??'');const assembly=await ctx.systemPrompt.assemble({hoshinoSessionId:sid} as any);return {layers:await promptLayers(sid),sections:assembly.sections,contexts:assembly.contexts,tools:assembly.tools.map(t=>t.name),variables:assembly.variables,tokenEstimate:Math.ceil(JSON.stringify(assembly.sections).length/4),tokenCountKind:'estimate'};}
   case 'search':return search(String(args.query),config.get('search.json'));
   case 'fetch':return fetchPage(String(args.url),{...config.get('search.json'),workspace});
   case 'render':webUrl(String(args.url));return bridge('web.render',args);
   case 'question':return originalCall('question',args);
   case 'deliver':{const path=await workspacePath(workspace,String(args.path));const s=await lstat(path);if(!s.isFile())throw Error('Deliver a regular workspace file');return bridge('file.deliver',{sessionId:args.sessionId,path,name:args.name??path.split('/').at(-1),bytes:s.size});}
   case 'ensure-tools':return ensureTools(String(args.group),workspace);
   case 'install-plugin':return installPlugin(ctx,config,String(args.spec));
   case 'plugin-search':return fetchPage('https://registry.npmjs.org/-/v1/search?text='+encodeURIComponent(String(args.query??'dsh-plugin'))+'&size=20',config.get('search.json'));
   case 'git':return gitOperation(workspace,String(args.operation),args);
   case 'build':return remoteBuild(args);
   case 'build-status':{const cfg=config.get('build.json');return github('/repos/'+cfg.repository+'/actions/runs?branch='+encodeURIComponent(String(args.branch??cfg.branch))+'&per_page=5');}
   case 'update':{
    if(args.url)return bridge('update.install',args);
    const last=JSON.parse(await readFile(join(root,'config','last-build.json'),'utf8'));const repo='/repos/'+last.repository;
    const runs=await github(repo+'/actions/runs?branch='+encodeURIComponent(last.branch)+'&per_page=10');const run=runs.workflow_runs?.find((r:Data)=>r.head_sha===last.sha);
    if(!run)return {status:'waiting',reason:'Workflow has not appeared yet',branch:last.branch};
    if(run.status!=='completed')return {status:'waiting',runId:run.id,runStatus:run.status,url:run.html_url};
    if(run.conclusion!=='success')throw Error('Build did not pass; inspect '+run.html_url);
    const artifacts=await github(repo+'/actions/runs/'+run.id+'/artifacts');const artifact=artifacts.artifacts?.find((a:Data)=>a.name==='hoshino-m2-development'&&!a.expired);if(!artifact)throw Error('Signed APK artifact missing or expired');
    return bridge('update.artifact',{artifactId:artifact.id,runId:run.id});
   }
   case 'backup':{await atomic(join(root,'config/android-metadata.json'),await bridge('backup.metadata'));return exportBackup(root,workspace,ctx.androidBridge.bootstrap.home);}
   case 'restore-backup':throw Error('Use native Settings → M2 → Diagnostics backup restoration; it stops the kernel before replacing session logs.');
   case 'schedules':return {items:await ctx.schedule.catalog()};
   case 'schedule-create':return ctx.schedule.create(String(args.sessionId) as any,args.request as any);
   case 'schedule-delete':return ctx.schedule.delete({sessionId:String(args.sessionId),id:String(args.id)} as any);
   case 'undo-turn':return restoreCheckpoint(root,workspace,String(args.id));
   case 'import-config':{
    const text=String(args.content);let data:unknown;try{data=JSON.parse(text);}catch{const require=createRequire(import.meta.url);data=(require('yaml') as {parse(s:string):unknown}).parse(text);}
    if(Array.isArray(data)){
     const loaded:Data[]=[];
     for(const row of data as Data[]){if(typeof row.name!=='string')throw Error('Cordis rows require a package name');try{const require=createRequire(import.meta.url);const entry=require.resolve(row.name);const module=await import('file://'+entry);const fiber=ctx.plugin(module,row.config??{});await fiber;loaded.push({id:row.id??row.name,status:'loaded'});}catch(e){loaded.push({id:row.id??row.name,status:'incompatible',reason:e instanceof Error?e.message:'Plugin activation failed'});}}
     await atomic(join(root,'config','desktop.cordis.json'),data);return {items:loaded};
    }
    if(data&&typeof data==='object'){for(const [key,value]of Object.entries(data))if(key in defaults)await config.write(key,value as Data);return {imported:true};}
    throw Error('Import an original Cordis row array or exported Hoshino configuration object');
   }
   case 'export-config':return Object.fromEntries(config.values);
   case 'sync-push':{const cfg=config.get('build.json');const path='/repos/'+cfg.repository+'/contents/hoshino-config.json';let sha:string|undefined;try{sha=(await github(path)).sha;}catch{}return github(path,'PUT',{message:'chore: sync public Hoshino configuration',content:Buffer.from(JSON.stringify(Object.fromEntries(config.values),null,2)).toString('base64'),...(sha?{sha}:{})});}
   case 'sync-pull':{const cfg=config.get('build.json');const result=await github('/repos/'+cfg.repository+'/contents/hoshino-config.json');return invoke('import-config',{content:Buffer.from(result.content,'base64').toString('utf8')});}
   case 'bridge':return bridge(String(args.action),args.request??{});
   default:throw Error('Unknown M2 operation');
  }
 };
 ctx.provide('mobileM2',{config,invoke,syncTools,approve:(action,ask)=>approval(ctx,config,action,ask)});
 const tools:Array<[string,string,Data,string]>=[
  ['self_read','Read a public self file, including source working copy.',{path:{type:'string',required:true}},'read'],
  ['self_write','Write public self configuration, DSL, plugin or source, with checkpoint.',{path:{type:'string',required:true},content:{type:'string',required:true}},'write'],
  ['self_diff','Preview a proposed text change without writing.',{path:{type:'string',required:true},content:{type:'string',required:true}},'diff'],
  ['self_apply','Apply a proposed self file edit after policy approval.',{path:{type:'string',required:true},content:{type:'string',required:true}},'apply'],
  ['self_snapshot','Take a workspace checkpoint before changing files.',{},'snapshot'],
  ['self_rollback','Restore a workspace checkpoint, preserving a safety snapshot.',{id:{type:'string',required:true}},'rollback-workspace'],
  ['self_doctor','Inspect kernel, schema errors and native bridge capabilities.',{},'doctor'],
  ['self_build','Push the Harness source working copy to your configured GitHub fork and dispatch a signed build.',{message:{type:'string'}},'build'],
  ['self_update','Poll the last self-build and install its matching signed artifact. Or supply a direct HTTPS APK URL and SHA256.',{url:{type:'string'},sha256:{type:'string'}},'update'],
  ['web_search','Search free sources with automatic fallback; returns source URLs.',{query:{type:'string',required:true}},'search'],
  ['web_fetch','Fetch a page as text/Markdown without a service key.',{url:{type:'string',required:true}},'fetch'],
  ['web_render','Render a remote page using Android WebView; optionally click/input/scroll under approval.',{url:{type:'string',required:true},script:{type:'string'},screenshot:{type:'boolean'}},'render'],
  ['ask_user','Ask the user a question with optional choices; the card and notification remain outside process folding.',{question:{type:'string',required:true},options:{type:'array',items:{type:'string'}}},'question'],
  ['deliver_file','Deliver a generated workspace file as a native file card.',{path:{type:'string',required:true},name:{type:'string'}},'deliver'],
  ['ensure_tools','Install optional free local media/document/speech/git/native-build tools on demand.',{group:{type:'string',required:true}},'ensure-tools'],
  ['plugin_install','Install an original Cordis/dsh plugin from npm, git, local directory or tgz.',{spec:{type:'string',required:true}},'install-plugin'],
 ];
 for(const [tool,description,parameters,action]of tools)ctx.tools.register(defineTool({name:tool,description,parameters:parameters as any,output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},async execute(args,exec){return JSON.stringify(await invoke(action,{...args,sessionId:exec.agent?.id}));}}));
}




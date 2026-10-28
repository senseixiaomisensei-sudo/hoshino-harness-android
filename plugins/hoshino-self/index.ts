import type { Context } from '../../node-host/src/platform.js';
import { defineTool } from '@deepseek-ai/dsh-tools';
import { mkdir, readFile, writeFile, readdir, cp, rm, rename, lstat, realpath } from 'node:fs/promises';
import { dirname, join, resolve } from 'node:path';
import { pathToFileURL } from 'node:url';
import { randomUUID } from 'node:crypto';
import { createRequire } from 'node:module';
import type {} from '@deepseek-ai/dsh-skill';
import type {} from '../android-skills/index.js';
import { validateOverrides,validateSchema,UI_SCHEMA } from './overrides.js';
import { workspacePath } from '../android-policy/paths.js';
import type {} from '../hoshino-m2/index.js';
type Json=string|number|boolean|null|Json[]|{[key:string]:Json};
export interface HoshinoControl { invoke(action: string,request: Record<string,unknown>): Promise<Json> }
declare module '@deepseek-ai/cordis' { interface Context { hoshinoControl: HoshinoControl } }
export const name='hoshino-self';
export const inject=['tools','androidBridge','systemPrompt','skills','mobileSkills','mobilePolicy','mobileM2'];
export async function apply(ctx: Context): Promise<void> {
  const root=ctx.androidBridge.bootstrap.selfHome ?? resolve(ctx.androidBridge.bootstrap.home,'hoshino');
  const userRoots=['config','plugins','skills','ui','source','prompts','modes'];
  for(const part of [...userRoots,'snapshots','self'])await mkdir(join(root,part),{recursive:true});
  const fibers=new Map<string,ReturnType<Context['plugin']>>();
  const invalid=new Set<string>();
  const disabled=new Set<string>();
  let queue=Promise.resolve();
  const serial=<T>(operation:()=>Promise<T>):Promise<T>=> {const next=queue.then(operation);queue=next.then(()=>{},()=>{});return next;};
  const identifier=(v:unknown)=> {if(typeof v!=='string' || !/^[a-z][a-z0-9-]{0,63}$/.test(v))throw new Error('Use a lowercase plugin or skill identifier');return v;};
  const readJson=async(path:string,fallback:Json):Promise<Json>=> {try{return JSON.parse(await readFile(path,'utf8')) as Json;}catch(e){if((e as NodeJS.ErrnoException).code==='ENOENT')return fallback;throw new Error('Invalid configuration JSON');}};
  const save=async(path:string,value:unknown)=> {await mkdir(dirname(path),{recursive:true});const temp=path+'.'+randomUUID()+'.tmp';await writeFile(temp,JSON.stringify(value,null,2)+'\n');await rename(temp,path);};
  const snapshot=async()=> {
    const id=Date.now()+'-'+randomUUID().slice(0,8);const dest=join(root,'snapshots',id);await mkdir(dest);
    for(const part of userRoots)await cp(join(root,part),join(dest,part),{recursive:true,dereference:false});
    const items=(await readdir(join(root,'snapshots'))).sort().reverse();
    for(const old of items.slice(20))await rm(join(root,'snapshots',old),{recursive:true,force:true});
    return id;
  };
  const configPath=(value:unknown)=> {
    if(typeof value!=='string' || !/^(config\/[a-z0-9-]+\.json|plugins\/[a-z][a-z0-9-]*\/config\.json|ui\/overrides\.json)$/.test(value))throw new Error('Only public Hoshino configuration paths are readable');
    return workspacePath(root,value);
  };
  const assertNoSecrets=(text:string)=> {
    if(/(?:api[_-]?key|authorization|password|access[_-]?token)\s*["']?\s*:/i.test(text))throw new Error('Credentials belong in Android Keystore');
  };
  async function loadPlugin(id:string) {
    await fibers.get(id)?.dispose();fibers.delete(id);invalid.delete(id);
    const path=await workspacePath(join(root,'plugins'),id+'/index.js');
    if(!(await lstat(path)).isFile())throw new Error('Plugin entry must be a regular file');
    try {
      const module=await import(pathToFileURL(path).href+'?generation='+randomUUID());
      const fiber=ctx.plugin(module,await readJson(join(root,'plugins',id,'config.json'),{}));
      try{await Promise.race([fiber,new Promise((_,reject)=>setTimeout(()=>reject(Error('Plugin activation timed out; check declared inject services')),10000).unref())]);}catch(e){await fiber.dispose();throw e;}
      fibers.set(id,fiber);
    } catch {invalid.add(id);throw new Error('Plugin could not load; use rollback or safe mode');}
  }
  async function reloadSkills() {
    ctx.mobileSkills.refresh();
  }
  const invoke=async(action:string,request:Record<string,unknown>):Promise<Json> => {
    switch(action) {
      case 'inspect': {
        const manifest=await readJson(join(root,'self','manifest.json'),{});
        const plugins=(await readdir(join(root,'plugins'),{withFileTypes:true})).filter(e=>e.isDirectory()).map(e=>({id:e.name,enabled:fibers.has(e.name),invalid:invalid.has(e.name)}));
        const summaries=await ctx.skills.list();
        return {version:'0.2.0-m2',dshVersion:'0.2.1-rc.2',nodeVersion:process.versions.node,kernel:'ready',manifest,
          plugins,tools:ctx.tools.schemas() as unknown as Json,skills:summaries as unknown as Json,mcp:[],hooks:[],
          paths:{self:join(root,'self'),plugins:join(root,'plugins'),skills:join(root,'skills'),workspace:ctx.androidBridge.bootstrap.workspace},
          ...(await ctx.androidBridge.call<Record<string,Json>>('self-status'))};
      }
      case 'session-options': {
        const id=String(request.sessionId ?? '');if(!id)throw new Error('Missing session');
        if(!['read-only','workspace-write','full-access'].includes(String(request.tier)))throw new Error('Unknown session permission tier');
        if(request.tier==='full-access') {
          const access=await ctx.androidBridge.call<{authorized:boolean}>('system-access');
          if(!access.authorized)throw new Error('Shizuku authorization is required for full-access');
        }
        const prior=ctx.mobilePolicy.options.get(id);
        const options={tier:String(request.tier),planMode:typeof request.planMode==='boolean' ? request.planMode : prior?.planMode ?? false,prompt:typeof request.prompt==='string' ? request.prompt.slice(0,32000) : prior?.prompt ?? '',mode:String(request.mode??prior?.mode??'standard'),model:String(request.model??prior?.model??'')};
        ctx.mobilePolicy.options.set(id,options);return {};
      }
      case 'resolve-diff': await ctx.mobilePolicy.resolveDiff(String(request.sessionId),String(request.id),request.accept===true);return {};
      case 'config-read': {
        const path=String(request.path ?? '');
        if(/^self\/(SELF|PATHS|PLUGINS|TOOLS|UI|DIY)\.md$/.test(path) || path==='self/manifest.json') {
          const content=await readFile(await workspacePath(root,path),'utf8');if(Buffer.byteLength(content)>512*1024)throw new Error('Read this manual in pages with the read tool');return {content};
        }
        return {content:JSON.stringify(await readJson(await configPath(request.path),{}),null,2)};
      }
      case 'config-write': return serial(async()=> {
        const path=await configPath(request.path);const content=String(request.content ?? '');if(Buffer.byteLength(content)>2*1024*1024)throw new Error('Config too large');assertNoSecrets(content);
        const value=JSON.parse(content) as unknown;if(request.path==='ui/overrides.json')validateOverrides(value);
        const id=await snapshot();await save(path,value);await ctx.androidBridge.call('self-changed',{});return {snapshot:id};
      });
      case 'ui-override': return invoke('config-write',{path:'ui/overrides.json',content:typeof request.content==='string' ? request.content : JSON.stringify(request.overrides)});
      case 'ui-read': {
        if(ctx.androidBridge.bootstrap.safeMode)return {value:{},valid:true,safeMode:true};
        try{
          const value=await readJson(join(root,'ui','overrides.json'),{}) as Record<string,any>;const slotErrors:Record<string,string>={};
          if(value.slots && typeof value.slots==='object')for(const [id,node]of Object.entries(value.slots))try{validateSchema(node,UI_SCHEMA.$defs.node,'$.slots.'+id);}catch(error){slotErrors[id]=error instanceof Error?error.message:'Invalid slot';delete value.slots[id];}
          return {value:validateOverrides(value) as unknown as Json,valid:true,slotErrors};
        }
        catch{return {value:{},valid:false,error:'UI override validation failed; original UI is active'};}
      }
      case 'scaffold-plugin': return serial(async()=> {
        const id=identifier(request.name);const dir=join(root,'plugins',id);try{await lstat(dir);throw new Error('Plugin already exists');}catch(e){if((e as NodeJS.ErrnoException).code!=='ENOENT')throw e;}
        const snapshotId=await snapshot();await mkdir(dir);
        const toolModule=pathToFileURL(createRequire(import.meta.url).resolve('@deepseek-ai/dsh-tools')).href;
        const entry=`import {defineTool} from ${JSON.stringify(toolModule)};\nexport const name=${JSON.stringify(id)};\nexport const inject=['tools'];\nexport function apply(ctx){ctx.tools.register(defineTool({name:${JSON.stringify(id)},description:${JSON.stringify(String(request.description ?? 'User-created '+id))},parameters:{},output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},async execute(){return ${JSON.stringify('Hello from '+id)};}}));}\n`;
        await writeFile(join(dir,'index.js'),entry);await save(join(dir,'package.json'),{name:'hoshino-user-'+id,type:'module',version:'0.1.0',license:'MIT'});
        await ctx.androidBridge.call('self-changed',{});return {id,path:join(dir,'index.js'),snapshot:snapshotId,next:'Call self_reload with this plugin id'};
      });
      case 'create-skill': return serial(async()=> {
        const id=identifier(request.name);const content=String(request.content ?? '');assertNoSecrets(content);const snap=await snapshot();
        const dir=join(root,'skills',id);await mkdir(dir,{recursive:true});await writeFile(join(dir,'SKILL.md'),`---\nname: ${id}\ndescription: ${JSON.stringify(String(request.description ?? id))}\n---\n${content}\n`);
        await reloadSkills();await ctx.androidBridge.call('self-changed',{});return {id,snapshot:snap};
      });
      case 'reload': return serial<Json>(async()=> {
        if(!request.plugin || request.plugin==='all' || request.plugin==='config'){await ctx.mobileM2.invoke('reload',{});await reloadSkills();return {reloaded:true};}
        if(request.plugin==='kernel') {await ctx.androidBridge.call('restart-after-turn',{sessionId:request.sessionId});return {scheduled:true} as Json;}
        if(request.plugin==='skills')await reloadSkills();else await loadPlugin(identifier(request.plugin));
        await ctx.androidBridge.call('self-changed',{});return {loaded:String(request.plugin)} as Json;
      });
      case 'snapshots': return {items:(await readdir(join(root,'snapshots'))).sort().reverse()};
      case 'rollback': return serial(async()=> {
        const id=String(request.id ?? '');if(!/^\d+-[a-f0-9]{8}$/.test(id))throw new Error('Invalid snapshot');
        const source=await workspacePath(join(root,'snapshots'),id);await lstat(source);
        for(const fiber of fibers.values())await fiber.dispose();fibers.clear();
        for(const part of userRoots) { await rm(join(root,part),{recursive:true,force:true});await cp(join(source,part),join(root,part),{recursive:true,dereference:false}); }
        await loadUsers();await reloadSkills();await ctx.androidBridge.call('self-changed',{});return {restored:id};
      });
      case 'enable-plugin': return serial(async()=> {
        const id=identifier(request.id);await snapshot();
        if(request.enabled===true){disabled.delete(id);await loadPlugin(id);}else{disabled.add(id);await fibers.get(id)?.dispose();fibers.delete(id);}
        await save(join(root,'config','disabled-plugins.json'),[...disabled]);return {};
      });
      case 'remove-plugin': return serial(async()=> {
        const id=identifier(request.id);await snapshot();await fibers.get(id)?.dispose();fibers.delete(id);await rm(await workspacePath(join(root,'plugins'),id),{recursive:true,force:true});return {};
      });
      case 'm2': return await ctx.mobileM2.invoke(String(request.operation),request) as Json;
      default: throw new Error('Unknown Hoshino control operation');
    }
  };
  async function loadUsers() {
    const saved=await readJson(join(root,'config','disabled-plugins.json'),[]);if(Array.isArray(saved))for(const id of saved)if(typeof id==='string')disabled.add(id);
    if(ctx.androidBridge.bootstrap.safeMode)return;
    for(const entry of await readdir(join(root,'plugins'),{withFileTypes:true}))if(entry.isDirectory() && !disabled.has(entry.name))await loadPlugin(identifier(entry.name)).catch(()=>{});
  }
  ctx.provide('hoshinoControl',{invoke});
  ctx.skills.register({name:'hoshino-self',description:'Understand and modify the current Hoshino Android shell; read its generated manuals before DIY.',source:'bundled',content:`You are running in Hoshino Harness. Before changing your shell read ${join(root,'self','SELF.md')}. PATHS.md lists paths; PLUGINS.md and TOOLS.md describe the actual composition; UI.md lists the ZIP routes and allowed overrides; DIY.md gives reload, verify and rollback steps. Write tools require approval; never access credentials.`});
  ctx.effect(()=>async()=>{for(const fiber of fibers.values())await fiber.dispose();});
  ctx.systemPrompt.section({name:'hoshino-self-index',order:10,text:`You run inside Hoshino Harness, a native Android shell around the original dsh. For questions about yourself or modifications, first use self_config_read or read the generated manuals at ${join(root,'self')}. Index: SELF.md, PATHS.md, PLUGINS.md, TOOLS.md, UI.md, DIY.md. Use the hoshino-self skill. Public config never includes credentials. Changes require approval and can be rolled back.`});
  const specs=[
    ['self_inspect','Inspect the actual running Hoshino shell, plugins, tool schemas, skills and model, without credentials.',{},'inspect'],
    ['self_reload','Reload a user plugin or skills. plugin=kernel schedules a restart after this turn.',{plugin:{type:'string',required:true}},'reload'],
    ['self_scaffold_plugin','Create a minimal working Cordis plugin and tool. Load it with self_reload.',{name:{type:'string',required:true},description:{type:'string'}},'scaffold-plugin'],
    ['self_create_skill','Create a skill using the original dsh skill filesystem provider.',{name:{type:'string',required:true},description:{type:'string',required:true},content:{type:'string',required:true}},'create-skill'],
    ['self_config_read','Read public configuration only, e.g. config/presets.json. Never credentials.',{path:{type:'string',required:true}},'config-read'],
    ['self_config_write','Validate and write public JSON configuration, taking a rollback snapshot first.',{path:{type:'string',required:true},content:{type:'string',required:true}},'config-write'],
    ['self_ui_override','Validate and apply declarative ZIP UI token, capsule, command or sidebar overrides. content is JSON.',{content:{type:'string',required:true}},'ui-override'],
  ] as const;
  for(const [tool,description,parameters,action] of specs)ctx.tools.register(defineTool({name:tool,description,parameters,
    output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},
    async execute(args,exec){return JSON.stringify(await invoke(action,{...args,sessionId:exec.agent?.id}));},
  }));
  void ctx.androidBridge.whenReady.then(()=>loadUsers()).catch(()=>{});
}

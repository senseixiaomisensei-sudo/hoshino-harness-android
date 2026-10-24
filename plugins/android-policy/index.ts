import type { Context } from '../../node-host/src/platform.js';
import { readFile, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { workspacePath } from './paths.js';
import type { FsInfo, FsTarget } from '@deepseek-ai/dsh-fs';
import type { ToolExecutionToken } from '@deepseek-ai/dsh-tools';
export interface SessionOptions { tier?: string; planMode?: boolean; prompt?: string }
interface Change { sessionId: string; target: FsTarget; before: string|null; after: string; version: NonNullable<FsInfo>['version'] }
export interface MobilePolicy {
  options: Map<string,SessionOptions>;
  resolveDiff(sessionId: string,id: string,accept: boolean): Promise<void>;
}
declare module '@deepseek-ai/cordis' { interface Context { mobilePolicy: MobilePolicy } }
export const name = 'android-policy';
export const inject = ['androidBridge', 'tools', 'fs', 'agents', 'systemPrompt'];
export function apply(ctx: Context): void {
  const options=new Map<string,SessionOptions>();
  const changes=new Map<string,Change>();
  const guards=new Map<ToolExecutionToken,{path: string;target: FsTarget;before: string|null;info: FsInfo|undefined}>();
  const remembered=new Set<string>();
  const reads=new Set(['get_device_info','read','read_image','skill','self_inspect','self_config_read']);
  ctx.provide('mobilePolicy',{ options,async resolveDiff(sessionId,id,accept) {
    const change=changes.get(id);if(!change || change.sessionId!==sessionId)throw new Error('Diff is no longer available');
    if(!accept) {
      const path=await workspacePath(ctx.androidBridge.bootstrap.workspace,change.target.displayPath);
      const current=await ctx.fs.stat(change.target);
      if(!current || current.version!==change.version)throw new Error('File changed outside this diff; refusing to overwrite');
      if(change.before===null) {
        if(await readFile(path,'utf8')!==change.after)throw new Error('File changed; refusing to remove');
        await unlink(path);
      } else await ctx.fs.writeText(change.target,change.before,{kind:'replaceIfVersion',version:change.version});
    }
    changes.delete(id);
  }});
  ctx.systemPrompt.context({name:'hoshino-session-policy',order:501,required:true,text:()=> {
    const id=ctx.agents.currentInitiator()?.id;const current=id ? options.get(id) : undefined;
    return [current?.prompt,current?.planMode ? 'Plan mode: do not write files, execute shell, or change configuration. Read and explain only.' : ''].filter(Boolean).join('\n');
  }});
  ctx.on('tools/pre-execute', async (exec, next) => {
    const sessionId=exec.agent?.id ?? '';
    const policy=options.get(sessionId);
    if(!reads.has(exec.name) && (policy?.tier==='read-only' || policy?.planMode))return {kind:'deny',reason:'Read-only session'};
    const args=exec.arguments as Record<string,unknown>;
    let path: string|undefined;
    if(['read','read_image','write','edit'].includes(exec.name)) {
      try { path=await workspacePath(ctx.androidBridge.bootstrap.workspace,String(args.file_path ?? '')); }
      catch { return {kind:'deny',reason:'Path is outside the private workspace or follows an escaping link'}; }
      if(exec.name==='write' || exec.name==='edit') {
        const target=await ctx.fs.resolve(path);const info=await ctx.fs.stat(target);
        if(info && (info.type!=='file' || (info.size ?? 0)>2*1024*1024))return {kind:'deny',reason:'Only UTF-8 files up to 2 MiB can be changed with a reviewable diff'};
        const before=info ? await readFile(path,'utf8') : null;
        guards.set(exec.token,{path,target,before,info});
      }
    }
    if(!reads.has(exec.name)) {
      const onceOnly=['clipboard_read','clipboard_write','app_screenshot','share_text','share_file'].includes(exec.name);
      const key=`${sessionId}:${exec.name}`;
      if(onceOnly || !remembered.has(key)) {
        const guard=guards.get(exec.token);
        const approved=await ctx.androidBridge.call<{approved:boolean;remember?:boolean}>('approve',{
          kind:'tool',sessionId,tool:exec.name,path,command:args.command ?? '',arguments:args,
          ...(guard ? {before:guard.before,after:exec.name==='write' ? args.content : args.new_string} : {}),
        },exec.signal);
        if(!approved.approved) {guards.delete(exec.token);return {kind:'deny',reason:'User declined'};}
        if(approved.remember && !onceOnly)remembered.add(key);
      }
    }
    return next();
  });
  ctx.on('fs/write-intent',async(target,actor,next)=> {
    const guard=actor && 'token' in actor ? guards.get(actor.token as ToolExecutionToken) : undefined;
    return guard ? guard.info ? {kind:'replaceIfVersion',version:guard.info.version} : {kind:'createIfAbsent'} : next();
  });
  ctx.on('fs/edit-intent',async(target,actor,next)=> {
    const guard=actor && 'token' in actor ? guards.get(actor.token as ToolExecutionToken) : undefined;
    return guard?.info ? {version:guard.info.version} : next();
  });
  ctx.on('tools/execute',async(exec,next)=> {
    const guard=guards.get(exec.token);
    try {
      const result=await next();
      if(guard && !result.isError) {
        const info=await ctx.fs.stat(guard.target);
        if(info) {
          const after=await readFile(guard.path,'utf8');const id=randomUUID();const sessionId=exec.agent?.id ?? '';
          changes.set(id,{sessionId,target:guard.target,before:guard.before,after,version:info.version});
          await ctx.androidBridge.call('file-diff',{sessionId,id,path:guard.path,before:guard.before,after},exec.signal);
        }
      }
      return result;
    } finally { guards.delete(exec.token); }
  });
  ctx.on('agent/request', async (payload, next) => {
    const sessionId=ctx.agents.requireInitiator().id;
    const key=`${sessionId}:network`;
    if(remembered.has(key))return next();
    const approved = await ctx.androidBridge.call<{ approved: boolean;remember?:boolean }>('approve', { kind: 'network',sessionId }, payload.signal);
    if (!approved.approved) throw new Error('Network request declined');
    if(approved.remember)remembered.add(key);
    return next();
  });
}


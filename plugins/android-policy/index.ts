import type { Context } from '../../node-host/src/platform.js';
import { readFile, unlink } from 'node:fs/promises';
import { randomUUID } from 'node:crypto';
import { workspacePath } from './paths.js';
import { tierGate, READ_TOOLS, FULL_ACCESS_PROMPT } from './tier.js';
import { guardSystemShell, touchesScreen } from './shell-guard.js';
import {resolve} from 'node:path';
import type {} from '@deepseek-ai/dsh-working-directory';
import type { FsInfo, FsTarget } from '@deepseek-ai/dsh-fs';
import type { ToolExecutionToken } from '@deepseek-ai/dsh-tools';
export interface SessionOptions { tier?: string; planMode?: boolean; prompt?: string;mode?:string;model?:string }
interface Change { sessionId: string; target: FsTarget; before: string|null; after: string; version: NonNullable<FsInfo>['version'] }
export interface MobilePolicy {
  options: Map<string,SessionOptions>;
  resolveDiff(sessionId: string,id: string,accept: boolean): Promise<void>;
}
declare module '@deepseek-ai/cordis' { interface Context { mobilePolicy: MobilePolicy } }
export const name = 'android-policy';
export const inject = ['androidBridge', 'tools', 'fs', 'agents', 'systemPrompt','workingDirectory'];
export function apply(ctx: Context): void {
  const options=new Map<string,SessionOptions>();
  const changes=new Map<string,Change>();
  const guards=new Map<ToolExecutionToken,{path: string;target: FsTarget;before: string|null;info: FsInfo|undefined}>();
  const remembered=new Set<string>();
  const reads=READ_TOOLS;
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
    return [current?.prompt,current?.tier==='full-access' ? FULL_ACCESS_PROMPT : '',current?.planMode ? 'Plan mode: do not write files, execute shell, or change configuration. Read and explain only.' : ''].filter(Boolean).join('\n');
  }});
  ctx.on('tools/pre-execute', async (exec, next) => {
    const sessionId=exec.agent?.id ?? '';
    const policy=options.get(sessionId);
    // Tier first: approval modes (even no-approval) never widen read-only / workspace / full-access boundaries.
    const gate=tierGate(policy?.tier,policy?.planMode,exec.name);
    if(gate.kind==='deny')return gate;
    const args=exec.arguments as Record<string,unknown>;
    // m2.9: app operations only on the background agent display. Refused before the approval prompt.
    if(exec.name==='system_shell') {
      const shell=await systemShellScreenGuard(ctx,String(args.command ?? ''),sessionId,exec.signal);
      if(shell.kind==='deny')return {kind:'deny',reason:shell.reason};
    }
    let path: string|undefined;
    if(['read','read_image','write','edit'].includes(exec.name)) {
      const cwd=exec.agent ? await ctx.workingDirectory.ensure(exec.agent,exec.signal) : ctx.androidBridge.bootstrap.workspace;
      const target=await ctx.fs.resolve(String(args.file_path ?? ''),{cwd,signal:exec.signal});
      const executionPath=ctx.fs.processPath(target);
      const root=ctx.androidBridge.bootstrap.workspace;
      // Old M0 logs record /workspace. Preserve that execution-world alias without changing JSONL.
      const legacy=root==='/root/workspace' && (executionPath==='/workspace' || executionPath.startsWith('/workspace/'));
      try { path=await workspacePath(legacy ? '/workspace' : root,executionPath); }
      catch {
        if(exec.name==='read') {
          const home=ctx.androidBridge.bootstrap.selfHome ?? resolve(ctx.androidBridge.bootstrap.home,'hoshino');
          for(const publicRoot of [home,'/opt/hoshino/dsh-source']) {
            try{path=await workspacePath(publicRoot,executionPath);break;}catch{}
          }
        }
        if(!path)return {kind:'deny',reason:'Path is outside the private workspace and public self documentation'};
      }
      if(exec.name==='write' || exec.name==='edit') {
        if(exec.name==='write' && Buffer.byteLength(String(args.content ?? ''))>2*1024*1024)return {kind:'deny',reason:'Writes over 2 MiB cannot be reviewed in this deployment'};
        const info=await ctx.fs.stat(target);
        if(info && (info.type!=='file' || (info.size ?? 0)>2*1024*1024))return {kind:'deny',reason:'Only UTF-8 files up to 2 MiB can be changed with a reviewable diff'};
        const before=info ? await readFile(path,'utf8') : null;
        guards.set(exec.token,{path,target,before,info});
      }
    }
    if(!reads.has(exec.name)) {
      const onceOnly=['clipboard_read','clipboard_write','app_screenshot','share_text','share_file'].includes(exec.name);
      // phone_* tools share one "allow for this session" decision; irreversible phone steps still ask once per step (android-phone).
      const key=`${sessionId}:${exec.name.startsWith('phone_') ? 'phone_*' : exec.name}`;
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
    try {
      const result=await next();
      const guard=guards.get(exec.token);
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
  // Sending a user prompt to an explicitly enabled provider is already authorized.
  // Tool/network side effects still use the tool approval policy above.

}



/** Current agent display id from Android (null when no phone task holds one or the bridge cannot tell). */
export async function agentDisplayId(ctx:Context,sessionId:string,signal?:AbortSignal):Promise<number|null> {
  try {
    const r=await ctx.androidBridge.call<{display?:unknown}>('phone',{action:'display',sessionId},signal);
    return typeof r?.display==='number' && Number.isInteger(r.display) ? r.display : null;
  } catch { return null; }
}

/** guardSystemShell with the display id fetched only for commands that touch a screen. */
export async function systemShellScreenGuard(ctx:Context,command:string,sessionId:string,signal?:AbortSignal) {
  if(!touchesScreen(command))return {kind:'continue'} as const;
  return guardSystemShell(command,await agentDisplayId(ctx,sessionId,signal));
}

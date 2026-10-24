import { randomUUID } from 'node:crypto';
import { defineTool } from '@deepseek-ai/dsh-tools';
import type { Context } from '../../node-host/src/platform.js';
import type {} from '../android-policy/index.js';
export const name='android-shizuku';
export const inject=['tools','androidBridge','mobilePolicy'];
export function apply(ctx:Context):void {
  ctx.tools.register(defineTool({
    name:'system_shell',description:'Execute an Android system shell command with the actual Shizuku adb/root uid. Requires an authorized full-access session and approval. Android cwd is /data/local/tmp; ordinary dsh bash and file tools still use the private workspace.',
    parameters:{command:{type:'string',required:true},timeout_ms:{type:'number'}},
    output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},
    async execute(args,exec) {
      const sessionId=exec.agent?.id ?? '';
      if(ctx.mobilePolicy.options.get(sessionId)?.tier!=='full-access')throw new Error('Enable full-access with Shizuku authorization before system_shell');
      const id=randomUUID();
      const cancel=()=>{void ctx.androidBridge.call('system-shell-cancel',{id,sessionId},AbortSignal.timeout(5000)).catch(()=>{});};
      exec.signal.addEventListener('abort',cancel,{once:true});
      try {return JSON.stringify(await ctx.androidBridge.call('system-shell',{id,sessionId,command:args.command,timeout:args.timeout_ms ?? 120000},exec.signal));}
      finally {exec.signal.removeEventListener('abort',cancel);}
    },
  }));
}

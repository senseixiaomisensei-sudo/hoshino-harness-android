import { randomUUID } from 'node:crypto';
import { defineTool } from '@deepseek-ai/dsh-tools';
import type { Context } from '../../node-host/src/platform.js';
import type {} from '../android-policy/index.js';
import { SYSTEM_SHELL_DENY_REASON } from '../android-policy/tier.js';
export const name='android-shizuku';
export const inject=['tools','androidBridge','mobilePolicy'];
export function apply(ctx:Context):void {
  ctx.tools.register(defineTool({
    name:'system_shell',description:'Execute an Android system shell command (pm, settings, dumpsys...) with a real adb/root uid, through Shizuku or the built-in wireless-debugging helper. Only in a full-access session the user enabled; every call goes through the user\'s approval policy. To open or operate apps prefer phone_launch_app / phone_do_steps (they need no full-access); use system_shell for system commands. With the built-in helper one command runs at most 14 s and stdout/stderr are merged. Android cwd is /data/local/tmp; ordinary dsh bash and file tools still use the private workspace.',
    parameters:{command:{type:'string',required:true},timeout_ms:{type:'number'}},
    output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},
    async execute(args,exec) {
      const sessionId=exec.agent?.id ?? '';
      const tier=ctx.mobilePolicy.options.get(sessionId)?.tier;
      if(tier!=='full-access')throw new Error(SYSTEM_SHELL_DENY_REASON);
      const id=randomUUID();
      const cancel=()=>{void ctx.androidBridge.call('system-shell-cancel',{id,sessionId},AbortSignal.timeout(5000)).catch(()=>{});};
      exec.signal.addEventListener('abort',cancel,{once:true});
      try {return JSON.stringify(await ctx.androidBridge.call('system-shell',{id,sessionId,tier,command:args.command,timeout:args.timeout_ms ?? 120000},exec.signal));}
      finally {exec.signal.removeEventListener('abort',cancel);}
    },
  }));
}

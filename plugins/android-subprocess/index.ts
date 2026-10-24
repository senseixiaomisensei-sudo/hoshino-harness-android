import { mkdirSync } from 'node:fs';
import { resolve } from 'node:path';
import { LocalSubprocessRuntime } from '@deepseek-ai/dsh-subprocess-local';
import type { Context } from '../../node-host/src/platform.js';
import type {SubprocessHandle,SubprocessSpawnSpec} from '@deepseek-ai/dsh-subprocess';

/** Reuses the upstream process/PTY owner; changes only the deployment-owned spill directory. */
export default class AndroidSubprocessRuntime extends LocalSubprocessRuntime {
  static inject = ['androidBridge','agents'];
  private readonly mobile:Context;
  constructor(ctx: Context) {
    super(ctx);
    this.mobile=ctx;
    const spillDir = resolve(ctx.androidBridge.bootstrap.workspace, '.hoshino-output');
    mkdirSync(spillDir, { recursive: true });
    this.internals = { ...this.internals, spillDir };
  }
  override spawn(spec:SubprocessSpawnSpec):SubprocessHandle {
    const handle=super.spawn(spec);const sessionId=this.mobile.agents.currentInitiator()?.id;
    if(!sessionId)return handle;
    const offsets={stdout:0,stderr:0};let sending=Promise.resolve();
    const flush=()=> {
      for(const stream of ['stdout','stderr'] as const) {
        const output=handle.collected[stream]?.readFrom(offsets[stream]);if(!output)continue;offsets[stream]=output.nextOffset;
        if(output.text)sending=sending.then(()=>this.mobile.androidBridge.call('terminal-output',{sessionId,stream,text:output.text})).then(()=>{},()=>{});
      }
    };
    const timer=setInterval(flush,200).unref();
    void handle.done.then(()=>{clearInterval(timer);flush();},()=>{clearInterval(timer);flush();});
    return handle;
  }
}

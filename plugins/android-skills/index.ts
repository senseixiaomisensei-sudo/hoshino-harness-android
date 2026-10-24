import type { Context } from '../../node-host/src/platform.js';
import { FileSystemSkillProvider } from '@deepseek-ai/dsh-skill-filesystem';
import type { Config } from '@deepseek-ai/dsh-skill-filesystem';
declare module '@deepseek-ai/cordis' { interface Context { mobileSkills: { refresh(): void } } }
export const name='android-skills';
export const inject=['skills'];
export function apply(ctx: Context,config:Config):void {
  let refresh=()=>{};
  let provider:FileSystemSkillProvider;
  ctx.skills.registerProvider(control=> {
    refresh=control.invalidate;provider=new FileSystemSkillProvider(ctx,control,config);return provider;
  });
  ctx.provide('mobileSkills',{refresh:()=>refresh()});
  ctx.effect(()=>()=>provider.dispose());
}

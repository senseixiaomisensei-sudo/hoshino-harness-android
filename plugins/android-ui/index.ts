import type {Context} from '../../node-host/src/platform.js';
import {defineTool} from '@deepseek-ai/dsh-tools';
import type {ImageAttachmentRef} from '@deepseek-ai/dsh-attachment';
export const name='android-ui';
export const inject=['tools','androidBridge','attachments','llm'];
export function apply(ctx:Context):void {
  ctx.tools.register(defineTool({name:'app_screenshot',description:'Capture this app window, after explicit approval. Requires image-capable model; secure credential pages cannot be captured.',parameters:{},
    output:{schema:{type:'string'},render:(_,value)=>[{type:'image',attachment:JSON.parse(String(value)) as ImageAttachmentRef}]},
    async execute(_,exec){
      const route=exec.agent?.session.requestHeader()?.config;const provider=route?.provider ?? exec.agent?.options.provider;const model=route?.model ?? exec.agent?.options.model;
      if(!provider || !model || !(await ctx.llm.resolveModelInfo(provider,model,exec.signal)).inputModalities?.includes('image'))throw new Error('Current model does not declare image input');
      const image=await ctx.androidBridge.call<{mediaType:'image/jpeg';data:string}>('ui-action',{action:'app_screenshot'},exec.signal);
      const attachment=await ctx.attachments.saveImage({data:Buffer.from(image.data,'base64'),mediaType:image.mediaType,name:'Hoshino-app-screenshot.jpg'});
      return JSON.stringify(attachment);
    },
  }));
  for(const [tool,description,parameters] of [
    ['clipboard_read','Read the clipboard once, while Hoshino is in the foreground. Explicit approval is required every time.',{}],
    ['clipboard_write','Write text to clipboard once, while Hoshino is foreground.',{text:{type:'string',required:true}}],
    ['share_text','Open the Android system share sheet for this text.',{text:{type:'string',required:true}}],
    ['share_file','Share a file inside the private workspace through Android FileProvider.',{path:{type:'string',required:true}}],
  ] as const)ctx.tools.register(defineTool({name:tool,description,parameters,output:{schema:{type:'string'},render:(_,value)=>[{type:'text',text:value}]},async execute(args,exec){return JSON.stringify(await ctx.androidBridge.call('ui-action',{action:tool,...args},exec.signal));}}));
}

import { mkdir,readFile,writeFile,rename,readdir,lstat } from 'node:fs/promises';
import { dirname,join,resolve } from 'node:path';
export type Data=Record<string,any>;
export const modes=[
  {id:'chat',name:'聊天',description:'无工具对话',tools:[],systemPrompt:'Answer conversationally. Do not use tools.'},
  {id:'standard',name:'Agent',description:'通用任务',systemPrompt:'Complete the requested task using the available tools.'},
  {id:'plan',name:'Plan',description:'只读规划',readOnly:true,systemPrompt:'Inspect and plan. Do not modify files or run commands until the user switches mode.'},
  {id:'ask',name:'Ask',description:'只读问答',readOnly:true,systemPrompt:'Read and explain. Do not change the workspace.'},
  {id:'research',name:'Research',description:'联网检索与引用',systemPrompt:'Search multiple sources, read primary sources and cite clickable URLs. Distinguish evidence from inference.'},
  {id:'review',name:'Review',description:'审查代码与 diff',readOnly:true,systemPrompt:'Review the actual diff and files. Report actionable findings with paths and evidence.'},
  {id:'goal',name:'Goal',description:'长期目标与自检',systemPrompt:'Work toward the user goal, verify meaningful milestones, and report remaining work candidly.'},
  {id:'auto',name:'Auto',description:'按审批策略自主执行',systemPrompt:'Proceed autonomously within the configured permissions and approval policy.'},
  {id:'orchestrator',name:'Orchestrator',description:'委派与汇总',systemPrompt:'Use the kernel delegation tools when available; do not claim parallel agents ran unless they actually did.'},
];
export const defaults:Record<string,Data>={
 'modes.json':{modes},
 'approval.json':{mode:'manual',highRiskAsk:true,rules:[],timeoutMs:20000,failure:'ask',scopes:{workspaces:{},sessions:{},modes:{}}},
 'prompts.json':{base:{operation:'append',text:''},global:{operation:'append',text:''},sessions:{},variables:{},injectTools:true,templates:[]},
 'search.json':{sources:['duckduckgo','bing','baidu','wikipedia','github','arxiv','npm','stackexchange','hackernews'],searxng:[],timeoutMs:18000,cacheSeconds:300,proxy:'system'},
 'build.json':{repository:'',workflow:'m2.yml',branch:'hoshino/self-update',allowLocal:false},
 'routing.json':{main:null,compact:null,approval:null,vision:null,fallback:[]},
 'preferences.json':{process:'collapsed',typeDefaults:{},concurrency:2},
};
export class Configuration {
 readonly values=new Map<string,Data>();readonly errors=new Map<string,string>();
 constructor(readonly root:string){}
 async reload(){
  for(const [name,fallback] of Object.entries(defaults)){
   const path=join(this.root,'config',name);await mkdir(dirname(path),{recursive:true});
   try{const v=JSON.parse(await readFile(path,'utf8'));if(!v || typeof v!=='object' || Array.isArray(v))throw Error('Expected object');this.values.set(name,v);this.errors.delete(name);}
   catch(e){if((e as NodeJS.ErrnoException).code==='ENOENT'){await this.write(name,fallback);}else{this.errors.set(name,'Invalid JSON; last valid configuration remains active');if(!this.values.has(name))this.values.set(name,structuredClone(fallback));}}
  }
 }
 get(name:string):Data{return this.values.get(name) ?? structuredClone(defaults[name] ?? {});}
 async write(name:string,value:Data){if(!(name in defaults))throw Error('Unknown configuration');assertPublic(value);await atomic(join(this.root,'config',name),value);this.values.set(name,value);}
}
export function assertPublic(value:unknown){if(Array.isArray(value)){value.forEach(assertPublic);return;}if(value&&typeof value==='object')for(const [k,v]of Object.entries(value)){if(/^(api[_-]?key|authorization|access[_-]?token|password)$/i.test(k)&&typeof v==='string'&&!/^(KEY_|HEADER_|\$\{)/.test(v))throw Error('Import credentials through Android Keystore, not public configuration');assertPublic(v);}}
export async function atomic(path:string,value:unknown){await mkdir(dirname(path),{recursive:true});const tmp=path+'.tmp';await writeFile(tmp,JSON.stringify(value,null,2));await rename(tmp,path);}
export function interpolate(text:string,variables:Record<string,unknown>){return text.replace(/\{\{([\w.-]+)\}\}/g,(_,name:string)=>String(variables[name] ?? ''));}
export function scopePolicy(base:Data,session:string,workspace:string,mode:string):Data{return {...base,...base.scopes?.modes?.[mode],...base.scopes?.workspaces?.[workspace],...base.scopes?.sessions?.[session]};}
export function matchRule(rule:Data,action:Data):boolean{
 if(rule.tool && rule.tool!==action.tool)return false;
 if(rule.prefix && !String(action.command ?? '').startsWith(rule.prefix))return false;
 if(rule.regex){if(String(rule.regex).length>500)throw Error('Rule regex too long');if(!new RegExp(rule.regex).test(String(action.command ?? '')))return false;}
 if(rule.path){const re='^'+String(rule.path).split('**').map(s=>s.split('*').map(p=>p.replace(/[.+?^${}()|[\]\\]/g,'\\$&')).join('[^/]*')).join('.*')+'$';if(!new RegExp(re).test(String(action.path ?? '')))return false;}
 if(rule.domain){let hostname='';try{hostname=new URL(String(action.url ?? '')).hostname;}catch{}if(hostname!==rule.domain && !hostname.endsWith('.'+rule.domain))return false;}
 return ['tool','prefix','regex','path','domain'].some(k=>rule[k]);
}
export function resolveApproval(policy:Data,action:Data):{decision:'allow'|'deny'|'ask'|'model';reason:string}{
 const risky=/\b(rm|reboot|shutdown|mkfs|dd|su|pm\s+uninstall|git\s+reset|chmod)\b|self_update|self_build|plugin_install/.test(String(action.command ?? '')+' '+String(action.tool ?? ''));
 if(policy.highRiskAsk!==false && risky)return {decision:'ask',reason:'High-risk confirmation is enabled'};
 for(const rule of policy.rules ?? [])if(matchRule(rule,action))return {decision:['allow','deny','ask'].includes(rule.decision)?rule.decision:'ask',reason:String(rule.reason ?? 'Matched rule')};
 if(policy.mode==='auto')return {decision:'allow',reason:'User selected automatic approval'};
 if(policy.mode==='model' || policy.mode==='rules' && policy.model)return {decision:'model',reason:'Approval model'};
 return {decision:'ask',reason:'Manual approval'};
}
export async function treeStamp(root:string):Promise<string>{
 const items:string[]=[];
 for(const part of ['config','ui','skills','plugins']){
  async function walk(dir:string,depth:number){if(depth>4)return;for(const e of await readdir(dir,{withFileTypes:true}).catch(()=>[])){if(e.name==='node_modules' || e.name==='.git')continue;const path=join(dir,e.name);const s=await lstat(path);if(s.isSymbolicLink())continue;if(e.isDirectory())await walk(path,depth+1);else items.push(path+':'+s.mtimeMs+':'+s.size);}}
  await walk(resolve(root,part),0);
 }
 return items.sort().join('\n');
}

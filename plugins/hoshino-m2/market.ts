// Plugin market: reads DSH "format 1" plugin index files (the same files the desktop
// Plugins → Discover tab reads) and installs a listed version through installPlugin.
import {lstat,mkdir,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {randomUUID} from 'node:crypto';
import type {Context} from '../../node-host/src/platform.js';
import {installPlugin,command} from './operations.js';
import type {Configuration,Data} from './config.js';

export const DSH_VERSION='0.2.2-alpha.2';
export const DEFAULT_SOURCES=[
 {url:'https://cdn.jsdelivr.net/npm/dsh-plugin-catalog/index.json'},
 {url:'https://dshopen.org/index.json'},
];
const MAX_BYTES=128*1024*1024;const TTL=10*60*1000;const TIMEOUT=30000;

type Version={version:string;publishTimestamp:number;source:Data;engines?:Data;prerelease:boolean;compatible:boolean|null};
type Entry={name:string;title:string;description:string;icon:string;author:string;homepage:string;repository:string;category:string;labels:string[];keywords:string[];sortKeys:Record<string,number>;versions:Version[];recommended?:Version;haystack:string;order:number};
type Index={url:string;title:string;description:string;categories:{id:string;title:string}[];labels:{id:string;title:string}[];sortKeys:{id:string;title:string;order:string}[];plugins:Entry[];fetchedAt:number};

const cache=new Map<string,Index>();const inflight=new Map<string,Promise<Index>>();const errors=new Map<string,string>();

function text(v:unknown,lang='zh'):string{if(typeof v==='string')return v;if(v&&typeof v==='object'){const o=v as Record<string,unknown>;const hit=o[lang]??o['zh-cn']??o['zh-hans']??o.en??Object.values(o)[0];return typeof hit==='string'?hit:'';}return '';}
export function checkUrl(raw:string){let u:URL;try{u=new URL(raw);}catch{throw Error('索引地址无效');}if(!/^https?:$/.test(u.protocol))throw Error('只支持 http(s) 索引地址');if(u.username||u.password||u.hash)throw Error('索引地址不能包含用户名、密码或 # 片段');return u.toString();}
function resolveIcon(icon:unknown,base:string):string{if(typeof icon!=='string'||!icon)return '';if(icon.startsWith('data:'))return icon.length<=48*1024?icon:'';try{const u=new URL(icon,base);if(u.protocol!=='https:'&&u.origin!==new URL(base).origin)return '';return u.toString();}catch{return '';}}

let semver:any;let semverTried=false;
async function satisfies(range:unknown):Promise<boolean|null>{if(range===undefined||range===null||range==='')return true;if(typeof range!=='string')return null;if(!semverTried){semverTried=true;try{semver=(await import('semver' as string)).default;}catch{semver=null;}}if(!semver)return null;try{return semver.satisfies(DSH_VERSION,range,{includePrerelease:true});}catch{return null;}}
function cmp(a:string,b:string):number{if(semver){try{return semver.compare(a,b);}catch{}}const pa=a.split(/[.+-]/),pb=b.split(/[.+-]/);for(let i=0;i<Math.max(pa.length,pb.length);i++){const x=pa[i]??'',y=pb[i]??'';const nx=Number(x),ny=Number(y);if(!isNaN(nx)&&!isNaN(ny)&&x!==''&&y!==''){if(nx!==ny)return nx-ny;}else if(x!==y)return x<y?-1:1;}return 0;}

// jsDelivr is often slow or blocked on mobile networks in mainland China; its sibling CDNs serve identical files.
function mirrors(url:string):string[]{const u=new URL(url);if(u.host==='cdn.jsdelivr.net')return ['fastly.jsdelivr.net','gcore.jsdelivr.net','testingcf.jsdelivr.net'].map(h=>{const m=new URL(url);m.host=h;return m.toString();});return [];}
function reason(e:unknown):string{const err=e as Error&{cause?:{code?:string;message?:string}};if(err?.name==='AbortError'||err?.name==='TimeoutError')return '超时';const c=err?.cause;return [err?.message,c?.code??c?.message].filter(Boolean).join(': ');}
async function fetchOne(url:string):Promise<any>{
 let current=url;
 for(let hop=0;hop<5;hop++){
  const res=await fetch(current,{redirect:'manual',signal:AbortSignal.timeout(TIMEOUT),headers:{accept:'application/json','user-agent':'Hoshino-Harness/0.2 (DSH plugin market)'}});
  if(res.status>=300&&res.status<400&&res.headers.get('location')){const next=new URL(res.headers.get('location')!,current);if(next.protocol!=='https:'&&new URL(current).protocol==='https:')throw Error('拒绝从 https 跳转到 http');current=next.toString();continue;}
  if(res.status<200||res.status>=300)throw Error('HTTP '+res.status);
  const reader=res.body?.getReader();if(!reader)throw Error('空响应');const parts:Uint8Array[]=[];let total=0;
  for(;;){const {done,value}=await reader.read();if(done)break;total+=value.byteLength;if(total>MAX_BYTES)throw Error('索引超过 128 MiB');parts.push(value);}
  return JSON.parse(Buffer.concat(parts).toString('utf8'));
 }
 throw Error('跳转次数过多');
}
async function download(url:string):Promise<any>{
 const tried:string[]=[];
 for(const candidate of [url,...mirrors(url)]){try{return await fetchOne(candidate);}catch(e){tried.push(new URL(candidate).host+' '+reason(e));}}
 throw Error('索引读取失败（'+tried.join('；')+'）');
}

async function parse(url:string,raw:any):Promise<Index>{
 if(!raw||typeof raw!=='object'||raw.format!==1)throw Error('不是 format 1 插件索引');
 if(!Array.isArray(raw.plugins))throw Error('索引缺少 plugins');
 const meta=raw.metadata??{};const list=(o:unknown)=>o&&typeof o==='object'?Object.entries(o as Data):[];
 const plugins:Entry[]=[];const seen=new Set<string>();
 for(const [i,p] of (raw.plugins as Data[]).entries()){
  if(!p||typeof p.name!=='string'||!p.name||seen.has(p.name)||!Array.isArray(p.versions)||!p.versions.length)continue;seen.add(p.name);
  const versions:Version[]=[];
  for(const v of p.versions as Data[]){if(!v||typeof v.version!=='string'||!v.source||!['npm','git'].includes(v.source.type))continue;if(v.source.type==='git'&&(typeof v.source.url!=='string'||!/^https:\/\//.test(v.source.url)||!/^[0-9a-f]{40}$/.test(String(v.source.commit))))continue;
   versions.push({version:v.version,publishTimestamp:Number(v.publishTimestamp)||0,source:v.source,engines:v.engines,prerelease:/-/.test(v.version.split('+')[0]),compatible:await satisfies(v.engines?.dsh)});}
  if(!versions.length)continue;versions.sort((a,b)=>cmp(b.version,a.version));
  const ok=versions.filter(v=>v.compatible!==false);const recommended=ok.find(v=>!v.prerelease)??ok[0];
  const title=text(p.title)||p.name;const description=text(p.description);const md=p.metadata??{};
  const allText=[p.name,p.author,...(md.keywords??[]),p.title,p.description].map(x=>typeof x==='object'&&x?Object.values(x).join(' '):String(x??'')).join(' ').toLowerCase();
  plugins.push({name:p.name,title,description,icon:resolveIcon(p.icon,url),author:typeof p.author==='string'?p.author:text(p.author?.name??''),homepage:typeof p.homepage==='string'?p.homepage:'',repository:typeof p.repository?.url==='string'?p.repository.url:'',category:typeof md.category==='string'?md.category:'',labels:Array.isArray(md.labels)?md.labels.filter((x:unknown)=>typeof x==='string'):[],keywords:Array.isArray(md.keywords)?md.keywords.filter((x:unknown)=>typeof x==='string').slice(0,12):[],sortKeys:md.sortKeys&&typeof md.sortKeys==='object'?md.sortKeys:{},versions,recommended,haystack:allText,order:i});
 }
 return {url,title:text(raw.title)||new URL(url).host,description:text(raw.description),
  categories:list(meta.categories).map(([id,v])=>({id,title:text(v?.title)||id})),labels:list(meta.labels).map(([id,v])=>({id,title:text(v?.title)||id})),
  sortKeys:list(meta.sortKeys).map(([id,v])=>({id,title:text(v?.title)||id,order:v?.order==='asc'?'asc':'desc'})),plugins,fetchedAt:Date.now()};
}

export async function loadIndexFile(url:string,path:string):Promise<Index>{url=checkUrl(url);const {readFile}=await import('node:fs/promises');const idx=await parse(url,JSON.parse(await readFile(path,'utf8')));cache.set(url,idx);errors.delete(url);return idx;}
export async function loadIndex(url:string,force=false):Promise<Index>{
 url=checkUrl(url);const hit=cache.get(url);if(hit&&!force&&Date.now()-hit.fetchedAt<TTL)return hit;
 const running=inflight.get(url);if(running)return running;
 const job=(async()=>{try{const idx=await parse(url,await download(url));cache.set(url,idx);errors.delete(url);return idx;}catch(e){errors.set(url,(e as Error).message);if(hit)return hit;throw e;}finally{inflight.delete(url);}})();
 inflight.set(url,job);return job;
}

// Typo tolerance: bounded Damerau-Levenshtein (1 edit for short words, 2 for 7+ chars), adjacent swaps count once.
function dl(a:string,b:string,max:number):number{if(Math.abs(a.length-b.length)>max)return max+1;const d:number[][]=[];for(let i=0;i<=a.length;i++){d[i]=[i];}for(let j=0;j<=b.length;j++)d[0][j]=j;
 for(let i=1;i<=a.length;i++){let best=Infinity;for(let j=1;j<=b.length;j++){const cost=a[i-1]===b[j-1]?0:1;let v=Math.min(d[i-1][j]+1,d[i][j-1]+1,d[i-1][j-1]+cost);if(i>1&&j>1&&a[i-1]===b[j-2]&&a[i-2]===b[j-1])v=Math.min(v,d[i-2][j-2]+1);d[i][j]=v;if(v<best)best=v;}if(best>max)return max+1;}return d[a.length][b.length];}
function near(word:string,hay:string):boolean{if(hay.includes(word))return true;if(word.length<4)return false;const max=word.length>=7?2:1;for(const token of hay.split(/[^a-z0-9\u4e00-\u9fff]+/)){if(token&&dl(word,token,max)<=max)return true;}return false;}
function score(e:Entry,q:string[]):number{if(!q.length)return 1;const whole=q.join(' '),dash=q.join('-');let s=(e.name.toLowerCase().includes(dash)||e.title.toLowerCase().includes(whole))?60:0;for(const w of q){if(e.name.toLowerCase()===w||e.title.toLowerCase()===w)s+=100;else if(e.title.toLowerCase().includes(w)||e.name.toLowerCase().includes(w))s+=20;else if(e.haystack.includes(w))s+=5;else if(near(w,e.haystack))s+=1;else return 0;}return s;}

export function pluginId(name:string){return name.replace(/^@/,'').replace(/[^a-zA-Z0-9-]/g,'-').toLowerCase();}
async function installedState(config:Configuration,name:string){const rec=config.get('market.json').installs?.[name];const present=await lstat(join(config.root,'plugins',pluginId(name))).then(()=>true,()=>false);const p=await provided(name);return present?{installed:true,version:rec?.version??null,source:rec?.source??null,provided:p}:{installed:false,version:null,source:null,provided:p};}
// A package the DSH kernel itself ships (resolvable from the kernel's own node_modules) is "provided by DSH".
const providedCache=new Map<string,boolean>();
async function provided(name:string){let hit=providedCache.get(name);if(hit===undefined){hit=await lstat(new URL('../../../node_modules/'+name+'/package.json',import.meta.url)).then(()=>true,()=>false);providedCache.set(name,hit);}return hit;}

function card(e:Entry,state:{installed:boolean;version:string|null;provided:boolean}){const rec=e.recommended;return {name:e.name,title:e.title,description:e.description,icon:e.icon,author:e.author,category:e.category,labels:e.labels,recommended:rec?.version??null,compatible:!!rec,published:rec?.publishTimestamp??e.versions[0].publishTimestamp,
 status:state.provided&&!state.installed?'provided':state.installed?(state.version&&rec&&cmp(rec.version,state.version)>0?'update':'installed'):'available',installedVersion:state.version};}

export async function sources(config:Configuration){const m=config.get('market.json');const list:Data[]=Array.isArray(m.sources)?m.sources:DEFAULT_SOURCES;return list.map(s=>({url:s.url,title:cache.get(s.url)?.title??s.title??new URL(s.url).host,count:cache.get(s.url)?.plugins.length??null,error:errors.get(s.url)??null}));}
async function saveSources(config:Configuration,list:Data[]){const m=config.get('market.json');await config.write('market.json',{...m,sources:list.map(s=>({url:s.url,...(s.title?{title:s.title}:{})}))});}

export async function marketInvoke(ctx:Context,config:Configuration,op:string,args:Data):Promise<any>{
 switch(op){
  case 'sources':return {sources:await sources(config)};
  case 'load-file':{const rel=String(args.file);if(!/^market-cache\/[a-f0-9]{64}\.json$/.test(rel))throw Error('Invalid index cache file');const idx=await loadIndexFile(String(args.url),join(config.root,rel));return {title:idx.title,count:idx.plugins.length};}
  case 'source-add':{const url=checkUrl(String(args.url));const idx=await loadIndex(url);const list:Data[]=(await sources(config)).filter(s=>s.url!==url);list.push({url,title:idx.title});await saveSources(config,list);return {sources:await sources(config)};}
  case 'source-remove':{const url=String(args.url);await saveSources(config,(await sources(config)).filter(s=>s.url!==url));cache.delete(url);errors.delete(url);return {sources:await sources(config)};}
  case 'query':{
   const idx=await loadIndex(String(args.url),!!args.refresh);const q=String(args.q??'').toLowerCase().trim().split(/\s+/).filter(Boolean);
   let rows=idx.plugins.map(e=>({e,s:score(e,q)})).filter(r=>r.s>0);
   if(args.category)rows=rows.filter(r=>r.e.category===args.category);
   if(args.label)rows=rows.filter(r=>r.e.labels.includes(String(args.label)));
   const sort=String(args.sort??'');
   if(sort==='publish')rows.sort((a,b)=>(b.e.recommended?.publishTimestamp??0)-(a.e.recommended?.publishTimestamp??0));
   else if(sort&&idx.sortKeys.some(k=>k.id===sort)){const dir=(args.order??idx.sortKeys.find(k=>k.id===sort)!.order)==='asc'?1:-1;rows.sort((a,b)=>dir*((a.e.sortKeys[sort]??-Infinity)-(b.e.sortKeys[sort]??-Infinity))||a.e.order-b.e.order);}
   else if(q.length)rows.sort((a,b)=>b.s-a.s||a.e.order-b.e.order);
   const offset=Math.max(0,Number(args.offset)||0);const limit=Math.min(60,Math.max(1,Number(args.limit)||30));const page=rows.slice(offset,offset+limit);
   return {title:idx.title,total:rows.length,offset,categories:idx.categories,labels:idx.labels,sortKeys:idx.sortKeys,error:errors.get(idx.url)??null,
    plugins:await Promise.all(page.map(async r=>card(r.e,await installedState(config,r.e.name))))};
  }
  case 'plugin':{
   const idx=await loadIndex(String(args.url));const e=idx.plugins.find(p=>p.name===args.name);if(!e)throw Error('索引中没有这个插件');const state=await installedState(config,e.name);
   return {...card(e,state),homepage:e.homepage,repository:e.repository,keywords:e.keywords,versions:e.versions.map(v=>({version:v.version,published:v.publishTimestamp,prerelease:v.prerelease,compatible:v.compatible,engines:v.engines?.dsh??null,source:describe(e.name,v)}))};
  }
  case 'install':{
   const idx=await loadIndex(String(args.url));const e=idx.plugins.find(p=>p.name===args.name);if(!e)throw Error('索引中没有这个插件');
   const v=args.version?e.versions.find(x=>x.version===args.version):e.recommended;if(!v)throw Error('没有与当前 DSH '+DSH_VERSION+' 兼容的版本');if(v.compatible===false)throw Error('该版本要求 DSH '+v.engines?.dsh+'，当前为 '+DSH_VERSION);
   if(await provided(e.name))throw Error('该插件已由 DSH 内核提供');
   let result:Data;const proxy=typeof args.proxy==='string'&&/^https?:\/\/[\w.-]+:\d+$/.test(args.proxy)?args.proxy:null;const saved={http:process.env.http_proxy,https:process.env.https_proxy,H:process.env.HTTP_PROXY,S:process.env.HTTPS_PROXY};
   if(proxy){process.env.http_proxy=process.env.https_proxy=process.env.HTTP_PROXY=process.env.HTTPS_PROXY=proxy;}
   try{
   if(v.source.type==='npm'){const flags=typeof v.source.registry==='string'&&/^https?:\/\//.test(v.source.registry)?['--registry',v.source.registry]:[];result=await installPlugin(ctx,config,e.name+'@'+v.version,flags);}
   else{
    const dir=join(config.root,'plugin-sources','git-'+randomUUID());await mkdir(dir,{recursive:true});
    try{
     if((await command('git',['--version'],dir,10000).catch(()=>({code:1}))).code){const r=await command('apk',['add','--no-cache','git'],dir,600000);if(r.code)throw Error('无法下载 git，请检查网络后重试');}
     const url=String(v.source.url).replace(/\/$/,'');const steps:[string[],number][]=[[['init','-q'],30000],[['remote','add','origin',url],30000],[['fetch','-q','--depth','1','origin',v.source.commit],600000],[['checkout','-q','FETCH_HEAD'],120000]];
     for(const [argv,ms] of steps){const r=await command('git',argv,dir,ms);if(r.code)throw Error('git '+argv[0]+' 失败：'+r.output.slice(-400));}
     const sub=String(v.source.path??'').replace(/^\/+|\/+$/g,'');if(sub.split('/').includes('..'))throw Error('索引中的 path 无效');
     result=await installPlugin(ctx,config,sub?join(dir,sub):dir,['--install-links']);
    }catch(err){await rm(dir,{recursive:true,force:true});throw err;}
    if(!(result as Data).ok)await rm(dir,{recursive:true,force:true});
   }
   }finally{if(proxy){const set=(k:string,v:string|undefined)=>{if(v===undefined)delete process.env[k];else process.env[k]=v;};set('http_proxy',saved.http);set('https_proxy',saved.https);set('HTTP_PROXY',saved.H);set('HTTPS_PROXY',saved.S);}}
   if((result as Data).ok){const m=config.get('market.json');await config.write('market.json',{...m,installs:{...(m.installs??{}),[e.name]:{version:v.version,source:idx.url,id:(result as Data).id,at:Date.now()}}});}
   return result;
  }
 }
 throw Error('Unknown market operation '+op);
}
function describe(name:string,v:Version){return v.source.type==='npm'?`npm ${name}@${v.version}`+(v.source.registry?` · ${v.source.registry}`:''):`git ${v.source.url} @ ${String(v.source.commit).slice(0,12)}`+(v.source.path?` · ${v.source.path}`:'');}

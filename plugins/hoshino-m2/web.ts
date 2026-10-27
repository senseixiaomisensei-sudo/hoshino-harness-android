import type {Data} from './config.js';
const cache=new Map<string,{at:number;value:Data}>();
const agents=['Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/128.0.0.0 Mobile Safari/537.36','HoshinoHarness/0.2 (+https://github.com/senseixiaomisensei-sudo/hoshino-harness-android)'];
let lastRequest=0;
export function webUrl(value:string):URL{const u=new URL(value);if(!['http:','https:'].includes(u.protocol)||u.username||u.password)throw Error('Only HTTP/HTTPS URLs without embedded credentials are supported');return u;}
export function markdown(html:string):string{return html.replace(/<(script|style|nav|noscript|svg)\b[^>]*>[\s\S]*?<\/\1>/gi,'').replace(/<a\b[^>]*href=["']([^"']+)["'][^>]*>([\s\S]*?)<\/a>/gi,(_,url:string,label:string)=>`[${label.replace(/<[^>]*>/g,'')}](${url})`).replace(/<h([1-6])[^>]*>([\s\S]*?)<\/h\1>/gi,(_,n:string,t:string)=>'\n'+'#'.repeat(Number(n))+' '+t+'\n').replace(/<(br|\/p|\/div|\/li|\/article|\/section)\b[^>]*>/gi,'\n').replace(/<[^>]*>/g,'').replace(/&nbsp;/g,' ').replace(/&amp;/g,'&').replace(/&lt;/g,'<').replace(/&gt;/g,'>').replace(/&quot;/g,'"').replace(/&#(\d+);/g,(_,n:string)=>String.fromCodePoint(Math.min(0x10ffff,Number(n)))).replace(/[ \t]+/g,' ').replace(/\n\s*\n\s*\n/g,'\n\n').trim();}
export async function fetchPage(url:string,config:Data):Promise<Data>{
 webUrl(url);const old=cache.get(url);if(old && Date.now()-old.at<Number(config.cacheSeconds??300)*1000)return {...old.value,cached:true};
 const wait=Math.max(0,250-(Date.now()-lastRequest));if(wait)await new Promise(r=>setTimeout(r,wait));lastRequest=Date.now();
 let error:unknown;
 for(let attempt=0;attempt<2;attempt++)try{
  const response=await fetch(url,{headers:{'User-Agent':agents[attempt%agents.length]!,'Accept':'text/html,application/json,text/plain;q=0.9,*/*;q=0.5'},signal:AbortSignal.timeout(Number(config.timeoutMs??18000))});
  if(!response.ok)throw Error(`HTTP ${response.status}`);const type=response.headers.get('content-type')??'';
  if(type.includes('application/pdf'))return {url:response.url,type,requires:'PDF text extraction: download to workspace and install poppler-utils with ensure_tools'};
  const reader=response.body!.getReader();const chunks:Uint8Array[]=[];let size=0;
  try{while(true){const {value,done}=await reader.read();if(done)break;size+=value.length;if(size>3*1024*1024)throw Error('Page exceeds 3 MiB; use streaming file download');chunks.push(value);}}finally{await reader.cancel().catch(()=>{});}
  const html=Buffer.concat(chunks).toString('utf8');const value={url:response.url,type,title:markdown(html.match(/<title[^>]*>([\s\S]*?)<\/title>/i)?.[1]??''),markdown:type.includes('html')?markdown(html):html,bytes:size};cache.set(url,{at:Date.now(),value});return value;
 }catch(e){error=e;}
 throw Error(`Fetch failed: ${error instanceof Error?error.message:'network error'}`);
}
export async function search(query:string,config:Data):Promise<Data>{
 if(!query.trim()||query.length>2000)throw Error('Search query must contain 1–2000 characters');
 const q=encodeURIComponent(query);const errors:Data[]=[];
 const sources:Record<string,string>={duckduckgo:`https://html.duckduckgo.com/html/?q=${q}`,bing:`https://www.bing.com/search?q=${q}`,baidu:`https://www.baidu.com/s?wd=${q}`,wikipedia:`https://en.wikipedia.org/w/api.php?action=query&list=search&srsearch=${q}&format=json`,arxiv:`https://export.arxiv.org/api/query?search_query=all:${q}&max_results=5`,github:`https://api.github.com/search/repositories?q=${q}&per_page=5`,npm:`https://registry.npmjs.org/-/v1/search?text=${q}&size=5`,stackexchange:`https://api.stackexchange.com/2.3/search/advanced?site=stackoverflow&q=${q}&pagesize=5`,hackernews:`https://hn.algolia.com/api/v1/search?query=${q}&hitsPerPage=5`};
 for(const [i,url]of (config.searxng??[]).entries())sources['searxng-'+i]=String(url).replace(/\/$/,'')+`/search?q=${q}&format=json`;
 for(const source of [...(config.sources??['duckduckgo','bing','wikipedia']),...Object.keys(sources).filter(s=>s.startsWith('searxng-'))]){
  const url=sources[source];if(!url)continue;
  try{const page=await fetchPage(url,config);const content=String(page.markdown);if(content.length<100 || /anomaly-modal|verify you are human|unusual traffic|captcha/i.test(content.slice(0,5000)))throw Error('Search provider returned a challenge');return {query,source,url,results:content.slice(0,35000),failedSources:errors,citationInstruction:'Read the linked sources and cite original URLs; search snippets alone are not verified facts.'};}
  catch(e){errors.push({source,error:e instanceof Error?e.message:'Failed'});}
 }
 return {query,results:[],failedSources:errors,error:'No configured search source was reachable; adjust sources or system VPN.'};
}

import {createMockLlm} from './server.mjs';
import {createHash,randomUUID} from 'node:crypto';
import {zstdDecompressSync} from 'node:zlib';
const {server}=createMockLlm();const original=server.listeners('request')[0];server.removeAllListeners('request');
const codes=new Map();let access='',refresh='',generation=0,refreshes=0,limitNext=false,firstRequestAt=0,mainRequests=0,codexRequests=0;
const json=(res,value,status=200)=>res.writeHead(status,{'Content-Type':'application/json'}).end(JSON.stringify(value));
function token(){const payload={exp:Math.floor(Date.now()/1000)+3600,'https://api.openai.com/auth':{chatgpt_account_id:'m21-mock-account'}};access=Buffer.from('{"alg":"none"}').toString('base64url')+'.'+Buffer.from(JSON.stringify(payload)).toString('base64url')+'.m21-'+(++generation);refresh='m21-refresh-'+generation;return {access_token:access,refresh_token:refresh,id_token:access,expires_in:3600,token_type:'Bearer'};}
const read=async req=>{let chunks=[];for await(const c of req)chunks.push(c);const raw=Buffer.concat(chunks);return req.headers['content-encoding']==='zstd'?zstdDecompressSync(raw).toString():raw.toString();};
server.on('request',async(req,res)=>{try{
 const url=new URL(req.url,'http://127.0.0.1:8099');const path=url.pathname;
 if(path==='/m21/metrics'){json(res,{now:Date.now(),firstRequestAt,mainRequests,codexRequests,refreshes,tokenGeneration:generation});return;}
 if(path==='/m21/reset'){firstRequestAt=0;mainRequests=0;json(res,{ok:true,now:Date.now()});return;}
 if(path==='/m21/limit'){limitNext=true;json(res,{ok:true});return;}
 if(path==='/oauth/authorize'){
  if(url.searchParams.get('response_type')!=='code'||url.searchParams.get('code_challenge_method')!=='S256'||!url.searchParams.get('state')){json(res,{error:'invalid_request'},400);return;}
  const code=randomUUID();codes.set(code,{challenge:url.searchParams.get('code_challenge'),redirect:url.searchParams.get('redirect_uri'),client:url.searchParams.get('client_id')});const target=new URL(url.searchParams.get('redirect_uri'));target.searchParams.set('code',code);target.searchParams.set('state',url.searchParams.get('state'));res.writeHead(302,{Location:target.toString()}).end();return;
 }
 if(path==='/oauth/token'){
  const body=new URLSearchParams(await read(req));
  if(body.get('grant_type')==='authorization_code'){const saved=codes.get(body.get('code'));const challenge=createHash('sha256').update(body.get('code_verifier')??'').digest('base64url');if(!saved||saved.challenge!==challenge||saved.redirect!==body.get('redirect_uri')||saved.client!==body.get('client_id')){json(res,{error:'invalid_grant'},400);return;}codes.delete(body.get('code'));json(res,token());return;}
  if(body.get('grant_type')==='refresh_token'&&body.get('refresh_token')===refresh){refreshes++;json(res,token());return;}
  json(res,{error:'invalid_grant'},400);return;
 }
 if(path==='/backend-api/codex/responses'){
  const received=Date.now();if(!firstRequestAt)firstRequestAt=received;mainRequests++;codexRequests++;
  if(req.headers.authorization!=='Bearer '+access||req.headers['chatgpt-account-id']!=='m21-mock-account'){json(res,{error:{code:'invalid_token',message:'Please sign in again'}},401);return;}
  const body=JSON.parse(await read(req));if(!body.stream||body.store!==false||!body.model||!Array.isArray(body.input)){json(res,{error:{code:'invalid_request',message:'Expected Codex Responses SSE format'}},400);return;}
  if(limitNext){limitNext=false;json(res,{error:{type:'usage_limit_reached',code:'usage_limit_reached',message:'Subscription quota exhausted'}},429);return;}
  res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});let sequence=0;const event=(type,value)=>res.write('data: '+JSON.stringify({type,sequence_number:sequence++,...value})+'\n\n');
  const id='resp_'+randomUUID();event('response.created',{response:{id,object:'response',status:'in_progress',output:[]}});
  event('response.output_item.added',{output_index:0,item:{id:'rs_m21',type:'reasoning',summary:[]}});event('response.reasoning_summary_part.added',{item_id:'rs_m21',output_index:0,summary_index:0,part:{type:'summary_text',text:''}});event('response.reasoning_summary_text.delta',{item_id:'rs_m21',output_index:0,summary_index:0,delta:'m21 subscription reasoning'});event('response.output_item.done',{output_index:0,item:{id:'rs_m21',type:'reasoning',summary:[{type:'summary_text',text:'m21 subscription reasoning'}]}});
  const message={id:'msg_m21',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'m21 subscription reply',annotations:[]}]};event('response.output_item.added',{output_index:1,item:{...message,status:'in_progress',content:[]}});event('response.content_part.added',{item_id:'msg_m21',output_index:1,content_index:0,part:{type:'output_text',text:'',annotations:[]}});event('response.output_text.delta',{item_id:'msg_m21',output_index:1,content_index:0,delta:'m21 subscription reply'});event('response.output_text.done',{item_id:'msg_m21',output_index:1,content_index:0,text:'m21 subscription reply'});event('response.output_item.done',{output_index:1,item:message});event('response.completed',{response:{id,object:'response',status:'completed',model:body.model,output:[message],usage:{input_tokens:42,output_tokens:12,input_tokens_details:{cached_tokens:3},output_tokens_details:{reasoning_tokens:5}}}});res.end();return;
 }
 if(req.method==='POST'&&path==='/v1/chat/completions'){if(!firstRequestAt)firstRequestAt=Date.now();mainRequests++;}
 await original(req,res);
 }catch{if(!res.headersSent)json(res,{error:'mock fixture failed'},500);else res.end();}});
server.listen(8099,'127.0.0.1');

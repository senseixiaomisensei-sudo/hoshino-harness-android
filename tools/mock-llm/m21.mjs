import {createMockLlm} from './server.mjs';
import {createHash,randomUUID,generateKeyPairSync,createSign} from 'node:crypto';
import {zstdDecompressSync} from 'node:zlib';
const {server}=createMockLlm();const original=server.listeners('request')[0];server.removeAllListeners('request');
// Sign in with ChatGPT fixture (Mika's flow): dynamic client registration, PKCE, nonce, RS256 ID token + JWKS, plan scope.
const ISSUER='http://127.0.0.1:8099';const ISSUED_CLIENT='m21-issued-client';const PLAN='chatgpt.tokens.use.direct';
const keys=generateKeyPairSync('rsa',{modulusLength:2048});const jwk={...keys.publicKey.export({format:'jwk'}),kid:'m21-key',alg:'RS256',use:'sig'};
const codes=new Map();let access='',refresh='',generation=0,refreshes=0,revocations=0,limitNext=false,firstRequestAt=0,mainRequests=0,codexRequests=0;
const json=(res,value,status=200)=>res.writeHead(status,{'Content-Type':'application/json'}).end(JSON.stringify(value));
const b64=v=>Buffer.from(typeof v==='string'?v:JSON.stringify(v)).toString('base64url');
function idToken(client,nonce){const head=b64({alg:'RS256',kid:'m21-key',typ:'JWT'});const body=b64({iss:ISSUER,aud:client,sub:'m21-user',email:'m21@example.test',nonce,exp:Math.floor(Date.now()/1000)+3600,iat:Math.floor(Date.now()/1000)});const sign=createSign('RSA-SHA256');sign.update(head+'.'+body);return head+'.'+body+'.'+sign.sign(keys.privateKey).toString('base64url');}
function token(client,nonce){access='m21-access-'+(++generation)+'-'+randomUUID();refresh='m21-refresh-'+generation+'-'+randomUUID();return {access_token:access,refresh_token:refresh,...(nonce!==undefined?{id_token:idToken(client,nonce)}:{}),expires_in:3600,token_type:'Bearer',scope:`openid profile email offline_access resource.invoke ${PLAN}`};}
const read=async req=>{let chunks=[];for await(const c of req)chunks.push(c);const raw=Buffer.concat(chunks);return req.headers['content-encoding']==='zstd'?zstdDecompressSync(raw).toString():raw.toString();};
server.on('request',async(req,res)=>{try{
 const url=new URL(req.url,ISSUER);const path=url.pathname;
 if(path==='/m21/metrics'){json(res,{now:Date.now(),firstRequestAt,mainRequests,codexRequests,refreshes,revocations,tokenGeneration:generation});return;}
 if(path==='/m21/reset'){firstRequestAt=0;mainRequests=0;json(res,{ok:true,now:Date.now()});return;}
 if(path==='/m21/limit'){limitNext=true;json(res,{ok:true});return;}
 if(path==='/.well-known/jwks.json'){json(res,{keys:[jwk]});return;}
 if(path==='/api/accounts/authorize'){
  const q=url.searchParams;const redirect=new URL(q.get('redirect_uri')??'http://invalid/');
  const valid=q.get('response_type')==='code'&&q.get('code_challenge_method')==='S256'&&q.get('state')&&q.get('nonce')&&q.get('resource')==='https://api.openai.com/v1'&&(q.get('scope')??'').split(' ').includes(PLAN)&&/^urn:uuid:/.test(q.get('ext_agent_host_id')??'')&&redirect.hostname==='127.0.0.1'&&redirect.pathname==='/auth/callback'&&(q.get('client_id')==='dynamic_agent_client'&&q.get('agent_name_hint')==='Hoshino'||q.get('client_id')===ISSUED_CLIENT);
  if(!valid){json(res,{error:'invalid_request'},400);return;}
  const code=randomUUID();codes.set(code,{challenge:q.get('code_challenge'),redirect:q.get('redirect_uri'),nonce:q.get('nonce')});
  redirect.searchParams.set('code',code);redirect.searchParams.set('state',q.get('state'));if(q.get('client_id')==='dynamic_agent_client')redirect.searchParams.set('client_id',ISSUED_CLIENT);
  res.writeHead(302,{Location:redirect.toString()}).end();return;
 }
 if(path==='/api/accounts/oauth/token'){
  const body=new URLSearchParams(await read(req));
  if(body.get('client_id')!==ISSUED_CLIENT||body.get('resource')!=='https://api.openai.com/v1'){json(res,{error:'invalid_client'},401);return;}
  if(body.get('grant_type')==='authorization_code'){const saved=codes.get(body.get('code'));const challenge=createHash('sha256').update(body.get('code_verifier')??'').digest('base64url');if(!saved||saved.challenge!==challenge||saved.redirect!==body.get('redirect_uri')){json(res,{error:'invalid_grant'},400);return;}codes.delete(body.get('code'));json(res,token(ISSUED_CLIENT,saved.nonce));return;}
  if(body.get('grant_type')==='refresh_token'&&body.get('refresh_token')===refresh){refreshes++;json(res,token(ISSUED_CLIENT));return;}
  json(res,{error:'invalid_grant'},400);return;
 }
 if(path==='/api/accounts/oauth/revoke'){revocations++;await read(req);json(res,{});return;}
 if(path==='/v1/models'){if(req.headers.authorization!=='Bearer '+access){json(res,{error:{code:'invalid_token'}},401);return;}json(res,{object:'list',data:[{id:'gpt-6.1-terra'},{id:'gpt-6-sol'}]});return;}
 if(path==='/v1/responses'){
  const received=Date.now();if(!firstRequestAt)firstRequestAt=received;mainRequests++;codexRequests++;
  if(req.headers.authorization!=='Bearer '+access){json(res,{error:{code:'invalid_token',message:'Please sign in again'}},401);return;}
  const body=JSON.parse(await read(req));if(!body.stream||body.store!==false||!body.model||!Array.isArray(body.input)||'max_output_tokens' in body||'temperature' in body){json(res,{error:{code:'invalid_request',message:'Expected plan Responses request'}},400);return;}
  if(limitNext){limitNext=false;json(res,{error:{type:'usage_limit_reached',code:'subscription_sharing_usage_limit_exceeded',message:'Subscription quota exhausted'}},429);return;}
  res.writeHead(200,{'Content-Type':'text/event-stream','Cache-Control':'no-cache'});let sequence=0;const event=(type,value)=>res.write('data: '+JSON.stringify({type,sequence_number:sequence++,...value})+'\n\n');
  const id='resp_'+randomUUID();event('response.created',{response:{id,object:'response',status:'in_progress',output:[]}});
  event('response.output_item.added',{output_index:0,item:{id:'rs_m21',type:'reasoning',summary:[]}});event('response.reasoning_summary_part.added',{item_id:'rs_m21',output_index:0,summary_index:0,part:{type:'summary_text',text:''}});event('response.reasoning_summary_text.delta',{item_id:'rs_m21',output_index:0,summary_index:0,delta:'m21 subscription reasoning'});event('response.output_item.done',{output_index:0,item:{id:'rs_m21',type:'reasoning',summary:[{type:'summary_text',text:'m21 subscription reasoning'}]}});
  const message={id:'msg_m21',type:'message',role:'assistant',status:'completed',content:[{type:'output_text',text:'m21 subscription reply',annotations:[]}]};event('response.output_item.added',{output_index:1,item:{...message,status:'in_progress',content:[]}});event('response.content_part.added',{item_id:'msg_m21',output_index:1,content_index:0,part:{type:'output_text',text:'',annotations:[]}});event('response.output_text.delta',{item_id:'msg_m21',output_index:1,content_index:0,delta:'m21 subscription reply'});event('response.output_text.done',{item_id:'msg_m21',output_index:1,content_index:0,text:'m21 subscription reply'});event('response.output_item.done',{output_index:1,item:message});event('response.completed',{response:{id,object:'response',status:'completed',model:body.model,output:[message],usage:{input_tokens:42,output_tokens:12,input_tokens_details:{cached_tokens:3},output_tokens_details:{reasoning_tokens:5}}}});res.end();return;
 }
 if(req.method==='POST'&&path==='/v1/chat/completions'){if(!firstRequestAt)firstRequestAt=Date.now();mainRequests++;}
 await original(req,res);
 }catch{if(!res.headersSent)json(res,{error:'mock fixture failed'},500);else res.end();}});
server.listen(8099,'127.0.0.1');

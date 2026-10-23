import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { once } from 'node:events';
import { spawn } from 'node:child_process';
import { mkdtemp,mkdir,rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join } from 'node:path';
import WebSocket from 'ws';

for (const [id, type, key, base, configuredModel] of [
  ['anthropic','anthropic',process.env.ANTHROPIC_API_KEY,process.env.ANTHROPIC_BASE_URL??'https://api.anthropic.com',process.env.ANTHROPIC_MODEL],
  ['openai','openai-compatible',process.env.OPENAI_API_KEY,process.env.OPENAI_BASE_URL??'https://api.openai.com/v1',process.env.OPENAI_MODEL],
]) test(`optional real provider: ${id}`,{skip:!key,timeout:180000},async t=>{
  // A secret enables the test. Model discovery avoids inventing a current model id.
  let model=configuredModel;
  if(!model) {
    const response=await fetch(`${base}${type==='anthropic'?'/v1/models':'/models'}`,{headers:type==='anthropic'?{'x-api-key':key,'anthropic-version':'2023-06-01'}:{Authorization:`Bearer ${key}`}});
    assert.ok(response.ok,'Real model catalog unavailable; set provider MODEL variable for gateways without discovery');
    const catalog=await response.json();model=catalog.data.find(m=>type==='anthropic'?m.id.startsWith('claude-'): /^gpt-/.test(m.id))?.id;
  }
  assert.ok(model,'No chat model available');
  const root=await mkdtemp(join(tmpdir(),'hoshino-real-'));const workspace=join(root,'workspace');await mkdir(workspace);
  let ready,toolCalls=0;const readyPromise=new Promise(r=>ready=r);
  const callback=createServer(async(req,res)=>{
    if(req.headers.authorization!=='Bearer real-test-callback'){res.writeHead(401).end();return;}
    let raw='';for await(const c of req)raw+=c;const body=JSON.parse(raw);let result={};
    if(req.url==='/providers')result=[{id,type,baseURL:base,apiKey:'REAL_TEST_KEY',models:[model]}];
    if(req.url==='/credential')result={value:key,source:'ci-secret-memory'};
    if(req.url==='/device-info'){toolCalls++;result={model:'M0-real-api-fixture',androidVersion:'16',battery:63};}
    if(req.url==='/approve')result={approved:true};
    if(req.url==='/ready')ready(body);
    res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(result));
  });callback.listen(0,'127.0.0.1');await once(callback,'listening');
  const child=spawn(process.execPath,['dist/node-host/src/main.js'],{stdio:['pipe','ignore','ignore']});
  child.stdin.end(JSON.stringify({callbackPort:callback.address().port,callbackToken:'real-test-callback',workspace,home:join(root,'home')})+'\n');
  t.after(async()=>{child.kill();callback.closeAllConnections();callback.close();await rm(root,{recursive:true,force:true});});
  const info=await Promise.race([readyPromise,once(child,'exit').then(()=>{throw new Error('Real test kernel failed to boot');})]);
  const host=`http://127.0.0.1:${info.port}`;const exchange=await fetch(`${host}/mobile/connect?token=${info.token}`,{redirect:'manual'});const cookie=exchange.headers.get('set-cookie').split(';')[0];
  const rpc=async(method,request)=>{const r=await fetch(`${host}/api/${method}`,{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method,payload:{args:{request}}})});const value=(await r.json()).result;assert.ok(value.ok,'Real provider RPC failed');return value.value;};
  const socket=new WebSocket(host.replace('http','ws')+'/api/remote.mux',{headers:{Cookie:cookie}});await once(socket,'open');t.after(()=>socket.terminate());
  const {sessionId}=await rpc('session/create',{cwd:workspace});await rpc('session/selectModel',{sessionId,provider:id,model});
  const completed=new Promise((accept,reject)=>socket.on('message',bytes=>{const f=JSON.parse(bytes.toString());const event=f.value?.event;if(event?.type==='turn/end'){if(event.data.reason.kind==='completed')accept();else reject(new Error('Real provider turn failed (payload suppressed)'));}}));
  socket.send(JSON.stringify({type:'open',streamId:'real',endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId},assistantStream:true}}}}));
  await rpc('session/prompt',{sessionId,requestId:crypto.randomUUID(),mode:'queue',content:[{type:'text',text:'Call get_device_info once, then state the returned device model. Do not guess.'}]});
  await completed;assert.equal(toolCalls,1);
});

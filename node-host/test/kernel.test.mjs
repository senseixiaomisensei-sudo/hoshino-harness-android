import { test } from 'node:test';
import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { spawn } from 'node:child_process';
import { mkdtemp, mkdir, readFile, readdir, rm } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { once } from 'node:events';
import WebSocket from 'ws';
import { createMockLlm } from '../../tools/mock-llm/server.mjs';
const listen = async server => { server.listen(0,'127.0.0.1'); await once(server,'listening'); return server.address().port; };
test('original dsh: both protocols, streams, Android callback, built-in write, switch, auth, no credential persistence', {timeout:120000}, async t => {
  const root = await mkdtemp(join(tmpdir(),'hoshino-'));
  const workspace = join(root,'workspace'); await mkdir(workspace);
  const mock = createMockLlm(); const mockPort = await listen(mock.server);
  const canary = `sk-m0-${crypto.randomUUID()}`;
  let ready, deviceCalls = 0, systemCalls=0, systemAuthorized=false, denySystem=false; const approvals = [];
  const kernelReady = new Promise(r => ready = r);
  const callback = createServer(async (req,res) => {
    if (req.headers.authorization !== 'Bearer test-callback-secret') { res.writeHead(401).end(); return; }
    let raw=''; for await(const b of req) raw+=b; const body=JSON.parse(raw);
    let result = {};
    if (req.url === '/providers') result=['claude','compatible'].map((id,i)=>({id,type:i?'openai-compatible':'anthropic',baseURL:`http://127.0.0.1:${mockPort}${i?'/v1/chat/completions':'/v1/messages'}`,apiKey:`KEY_${id.toUpperCase()}`,models:['mock-model'],metadata:{models:[{id:'mock-model',reasoning:false}]}}));
    if (req.url === '/credential') result={value:canary,source:'test-memory'};
    if (req.url === '/device-info') { deviceCalls++; result={model:'M0 virtual Android',androidVersion:'16',battery:72}; }
    if (req.url === '/approve') { approvals.push(body); result={approved:!(denySystem && body.tool==='system_shell')}; }
    if (req.url === '/system-access') result={authorized:systemAuthorized};
    if (req.url === '/system-shell') { systemCalls++;result={uid:2000,cwd:'/data/local/tmp',exitCode:0,stdout:'2000\nfixture Android\n',stderr:'',timedOut:false}; }
    if (req.url === '/ready') ready(body);
    res.writeHead(200,{'Content-Type':'application/json'}).end(JSON.stringify(result));
  });
  const callbackPort=await listen(callback);
  let logs='';
  const child=spawn(process.execPath,['dist/node-host/src/main.js'],{cwd:resolve('.'),stdio:['pipe','pipe','pipe']});
  child.stdout.on('data',b=>logs+=b); child.stderr.on('data',b=>logs+=b);
  child.stdin.end(JSON.stringify({callbackPort,callbackToken:'test-callback-secret',workspace,home:join(root,'home')})+'\n');
  t.after(async()=>{if(child.exitCode===null && child.signalCode===null){const stopped=once(child,'exit');child.kill();await stopped;} mock.server.closeAllConnections();callback.closeAllConnections();await Promise.all([new Promise(r=>mock.server.close(r)),new Promise(r=>callback.close(r))]);await rm(root,{recursive:true,force:true});});
  const info=await Promise.race([kernelReady, once(child,'exit').then(([code])=>{throw new Error(`Kernel exit ${code}: ${logs}`);}),new Promise((_,reject)=>setTimeout(()=>reject(new Error(`Kernel readiness timeout: ${logs}`)),60000).unref())]);
  const base=`http://127.0.0.1:${info.port}`;
  assert.equal((await fetch(`${base}/api/session/list`,{method:'POST'})).status,401);
  const exchange=await fetch(`${base}/mobile/connect?token=${info.token}`,{redirect:'manual'});
  assert.equal(exchange.status,302); const cookie=exchange.headers.get('set-cookie').split(';')[0];
  assert.equal((await fetch(`${base}/mobile/connect?token=${info.token}`,{redirect:'manual'})).status,401);
  const rpc=async(method, request,allowFailure=false)=>{
    const response=await fetch(`${base}/api/${method}`,{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:JSON.stringify({type:'client-request',rpcId:crypto.randomUUID(),method,payload:{args:{...(request===undefined?{}:{[method==='session/list'?'_request':'request']:request})}}})});
    const json=await response.json(); if(allowFailure)return json.result;assert.equal(json.result.ok,true,JSON.stringify(json)); return json.result.value;
  };
  const socket=new WebSocket(base.replace('http','ws')+'/api/remote.mux',{headers:{Cookie:cookie}});await once(socket,'open');t.after(()=>socket.terminate());
  const {sessionId}=await rpc('session/create',{cwd:workspace});
  const frames=[];socket.on('message',data=>frames.push(JSON.parse(data.toString())));
  socket.send(JSON.stringify({type:'open',streamId:'follow',endpoint:'session/follow',payload:{args:{request:{address:{kind:'session',sessionId},assistantStream:true}}}}));
  const wait=async(predicate)=>{const deadline=Date.now()+20000;while(!predicate()){if(Date.now()>deadline)throw new Error(`Timed out; frames=${JSON.stringify(frames).slice(-12000)}; logs=${logs}`);await new Promise(r=>setTimeout(r,50));}};
  let turns=0;
  for(const provider of ['claude','compatible']) {
    const incompatible=await rpc('session/selectModel',{sessionId,provider,model:'mock-model',reasoningEffort:'off'},true);
    assert.equal(incompatible.ok,false);
    assert.equal(incompatible.error.code,'session/model-unavailable');
    await rpc('session/selectModel',{sessionId,provider,model:'mock-model'});
    for(const prompt of ['你好','读取设备 device 信息','写入 hello.txt']) {
      const before=frames.length;
      await rpc('session/prompt',{sessionId,requestId:crypto.randomUUID(),mode:'queue',content:[{type:'text',text:prompt}]});
      await wait(()=>frames.slice(before).some(f=>JSON.stringify(f).includes('turn/end')));turns++;
      const end = frames.slice(before).find(f => f.value?.event?.type === 'turn/end');
      assert.equal(end.value.event.data.reason.kind, 'completed', JSON.stringify(end));
    }
  }
  assert.equal(deviceCalls,2, JSON.stringify({requests:mock.requests, frames, approvals, logs}).slice(-16000));
  assert.equal(await readFile(join(workspace,'hello.txt'),'utf8'),'Hello from dsh\n');
  const wire=JSON.stringify(frames);assert.ok(wire.includes('assistant-stream'));assert.ok(wire.includes('thinking'));assert.ok(wire.includes('get_device_info'));
  assert.ok(frames.some(f=>f.value?.event?.type==='assistant/message' && JSON.stringify(f.value.event.data.message).includes('M0 virtual Android')),'Model reply must use actual device tool result');
  assert.ok(mock.requests.some(r=>r.path==='/v1/messages'));assert.ok(mock.requests.some(r=>r.path==='/v1/chat/completions'));
  assert.ok(mock.requests.filter(r=>r.path==='/v1/messages').every(r=>r.body.thinking?.type!=='enabled'),'No implicit Anthropic thinking budget');
  await rpc('session/rename',{sessionId,title:'History title fixture'});
  const listed=await rpc('session/list',{});
  assert.equal(listed.items.find(row=>row.sessionId===sessionId).projections.values.title,'History title fixture','Native list must consume original title projection');
  const history=await rpc('session/search',{query:'device'});
  assert.ok(history.items.some(row=>row.sessionId===sessionId && row.snippet.includes('device')),'Original history search must find message text unrelated to the renamed title');
  const control=async request=>fetch(`${base}/mobile/control`,{method:'POST',headers:{Cookie:cookie,'Content-Type':'application/json'},body:JSON.stringify({action:'session-options',request})});
  assert.equal((await control({sessionId,tier:'full-access'})).status,400,'Full access must require the actual Android authorization callback');
  systemAuthorized=true;
  assert.equal((await control({sessionId,tier:'full-access'})).status,200);
  for(const denied of [true,false]) {
    denySystem=denied;const before=frames.length;
    await rpc('session/prompt',{sessionId,requestId:crypto.randomUUID(),mode:'queue',content:[{type:'text',text:'system command shizuku id'}]});
    await wait(()=>frames.slice(before).some(f=>f.value?.event?.type==='turn/end'));
    assert.equal(systemCalls,denied?0:1,'A rejected system command must not reach the Kotlin execution callback');
    if(!denied)assert.ok(JSON.stringify(frames.slice(before)).includes('fixture Android'));
    turns++;
  }
  assert.ok(approvals.some(a=>a.kind==='network'));assert.ok(approvals.some(a=>a.tool==='write'));
  assert.ok(!logs.includes(canary));assert.ok(!logs.includes(info.token));
  let durableLog='';
  async function scan(dir){for(const e of await readdir(dir,{withFileTypes:true})){const p=join(dir,e.name);if(e.isDirectory())await scan(p);else {const bytes=await readFile(p);assert.ok(!bytes.includes(Buffer.from(canary)),p);if(e.name.endsWith('.jsonl'))durableLog+=bytes.toString('utf8');}}}
  await scan(root);
  assert.ok(durableLog.includes('user/message') && durableLog.includes('tool/result'),'Original JSONL checkpoints must contain actual conversation and tool events');
  console.log(`dsh turns=${turns}, device callbacks=${deviceCalls}, synthetic system callbacks=${systemCalls}, authenticated original Session/Mux protocol verified`);
});


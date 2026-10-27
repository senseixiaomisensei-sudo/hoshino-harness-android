import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,mkdir,writeFile,readFile,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {unzipSync,strFromU8} from 'fflate';
import {Configuration,resolveApproval,scopePolicy,interpolate} from '../dist/plugins/hoshino-m2/config.js';
import {validateOverrides} from '../dist/plugins/hoshino-self/overrides.js';
import {search} from '../dist/plugins/hoshino-m2/web.js';
import {exportBackup,checkpoint,restoreCheckpoint,selfSnapshot} from '../dist/plugins/hoshino-m2/operations.js';

test('UI schema accepts slot/page/token DSL and points at a malformed nested node',()=>{
 const dsl={version:1,sidebarColumns:2,process:{display:'collapsed'},tokens:{colors:{accent:'#445566'}},slots:{topbar:{type:'row',children:[{type:'battery'}]}},pages:[{route:'diy/about',title:'My page',root:{type:'text',text:'Hi'}}]};
 assert.equal(validateOverrides(dsl).slots.topbar.children[0].type,'battery');
 assert.throws(()=>validateOverrides({slots:{topbar:{type:'row',children:[{type:'execute-remote-js'}]}}}),/\$\.slots\.topbar\.children\[0\]\.type/);
 assert.throws(()=>validateOverrides({accent:'javascript:bad'}),/\$\.accent/);
});
test('four approval modes, scoped priority, high-risk toggle, rules and failure default',()=>{
 const action={tool:'write',path:'/workspace/src/a.kt',command:'echo hello'};
 assert.equal(resolveApproval({mode:'manual'},action).decision,'ask');
 assert.equal(resolveApproval({mode:'rules',rules:[{path:'/workspace/**',decision:'allow'}]},action).decision,'allow');
 assert.equal(resolveApproval({mode:'model'},action).decision,'model');
 assert.equal(resolveApproval({mode:'auto'},action).decision,'allow');
 assert.equal(resolveApproval({mode:'auto'}, {...action,command:'rm -rf target'}).decision,'ask');
 assert.equal(resolveApproval({mode:'auto',highRiskAsk:false}, {...action,command:'rm -rf target'}).decision,'allow');
 assert.equal(resolveApproval({mode:'rules',rules:[{domain:'example.com',decision:'deny'}]}, {url:'https://sub.example.com/path'}).decision,'deny');
 assert.equal(scopePolicy({mode:'manual',scopes:{modes:{auto:{mode:'auto'}},workspaces:{w:{mode:'rules'}},sessions:{s:{mode:'model'}}}},'s','w','auto').mode,'model');
});
test('config reload preserves last good JSON and refuses plaintext credentials',async()=>{
 const root=await mkdtemp(join(tmpdir(),'hh-m2-config-'));try{const c=new Configuration(root);await c.reload();await c.write('approval.json',{mode:'auto',highRiskAsk:false});await writeFile(join(root,'config/approval.json'),'{broken');await c.reload();assert.equal(c.get('approval.json').mode,'auto');assert.equal(c.errors.has('approval.json'),true);await assert.rejects(c.write('build.json',{apiKey:'secret-canary'}),/Keystore/);}finally{await rm(root,{recursive:true,force:true});}
});
test('prompt variables, ordinary workspace checkpoint and streamed ZIP backup preserve data',async()=>{
 const root=await mkdtemp(join(tmpdir(),'hh-m2-backup-'));try{
  const self=join(root,'self'),workspace=join(root,'workspace'),home=join(root,'home');await mkdir(workspace);await mkdir(join(self,'config'),{recursive:true});await mkdir(join(home,'sessions'),{recursive:true});
  await writeFile(join(workspace,'a.txt'),'original');await writeFile(join(self,'config/approval.json'),'{"mode":"manual"}');await writeFile(join(home,'sessions/log.jsonl'),'{"seq":1}\n');
  const id=await checkpoint(self,workspace);await writeFile(join(workspace,'a.txt'),'changed');await restoreCheckpoint(self,workspace,id);assert.equal(await readFile(join(workspace,'a.txt'),'utf8'),'original');
  const snap=await selfSnapshot(self);assert.equal(await readFile(join(self,'snapshots',snap,'config/approval.json'),'utf8'),'{"mode":"manual"}');
  const zip=await exportBackup(self,workspace,home);const files=unzipSync(await readFile(zip.path));assert.equal(strFromU8(files['home/sessions/log.jsonl']),'{"seq":1}\n');assert.equal(strFromU8(files['self/config/approval.json']),'{"mode":"manual"}');
  assert.equal(interpolate('At {{date}} in {{workspace}}',{date:'2026-10-27',workspace:'/work'}),'At 2026-10-27 in /work');
 }finally{await rm(root,{recursive:true,force:true});}
});
test('free search falls back after a blocked provider without a key',async()=>{
 const original=globalThis.fetch;const requests=[];
 globalThis.fetch=async url=>{requests.push(String(url));return String(url).includes('duckduckgo')?new Response('challenge',{status:503}):new Response('<html><title>Results</title><body>'+('A useful result with a source link. '.repeat(8))+'<a href="https://example.com/paper">Primary source</a></body></html>',{headers:{'Content-Type':'text/html'}});};
 try{const result=await search('m2-fixture',{sources:['duckduckgo','bing'],timeoutMs:1000});assert.equal(result.source,'bing');assert.equal(result.failedSources.length,1);assert.match(result.results,/https:\/\/example.com\/paper/);assert.equal(requests.some(u=>u.includes('api_key')),false);}finally{globalThis.fetch=original;}
});

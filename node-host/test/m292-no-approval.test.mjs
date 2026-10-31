// m2.9.2: 「无审批」 is total. Every approval entry of the kernel goes through one seam (hoshino-m2 approval());
// in a session under no-approval — including its subagents — none of them may produce a pending approval item.
import {test} from 'node:test';
import assert from 'node:assert/strict';
import {mkdtemp,readFile,readdir,rm} from 'node:fs/promises';
import {join} from 'node:path';
import {tmpdir} from 'node:os';
import {Configuration,withApprovalMode,approvalSessionFor,PHONE_IRREVERSIBLE_TOOL} from '../dist/plugins/hoshino-m2/config.js';
import {approval,isNoApproval,sessionChain} from '../dist/plugins/hoshino-m2/approval.js';
import {runSteps,checkSteps,NO_APPROVAL_PHONE_PROMPT} from '../dist/plugins/android-phone/steps.js';

/** Every kind of action that reaches the approval seam today (grep of plugins/: android-policy, android-phone, hoshino-m2). */
const ENTRIES=[
 {kind:'tool',tool:'write',path:'/w/a.txt',before:'a',after:'b'},
 {kind:'tool',tool:'edit',path:'/w/a.txt'},
 {kind:'tool',tool:'bash',command:'rm -rf build && git reset --hard'},
 {kind:'tool',tool:'system_shell',command:'pm uninstall com.example'},
 {kind:'tool',tool:'system_shell',command:'reboot'},
 {kind:'tool',tool:'web_fetch',url:'https://example.com'},
 {kind:'tool',tool:'web_render',url:'https://example.com'},
 {kind:'tool',tool:'share_file',arguments:{path:'/w/a.txt'}},
 {kind:'tool',tool:'share_text',arguments:{text:'x'}},
 {kind:'tool',tool:'clipboard_read'},
 {kind:'tool',tool:'clipboard_write'},
 {kind:'tool',tool:'app_screenshot'},
 {kind:'tool',tool:'deliver_file',arguments:{path:'/w/a.txt'}},
 {kind:'tool',tool:'notification_post'},
 {kind:'tool',tool:'self_update'},
 {kind:'tool',tool:'self_build'},
 {kind:'tool',tool:'plugin_install'},
 {kind:'tool',tool:'self_apply'},
 {kind:'tool',tool:'phone_tap'},
 {kind:'tool',tool:'phone_launch_app'},
 {kind:'tool',tool:PHONE_IRREVERSIBLE_TOOL,command:'send: tap "发送"',arguments:{action:'send',app:'com.tencent.mm',once:true}},
 {kind:'tool',tool:PHONE_IRREVERSIBLE_TOOL,command:'pay: tap "立即支付"',arguments:{action:'pay',app:'com.eg.android.AlipayGphone',once:true}},
 {kind:'network',tool:'model'},
];

function fakeCtx(parents={}){
 return {
  mobilePolicy:{options:new Map()},
  androidBridge:{bootstrap:{workspace:'/work'},call:async()=>{throw Error('no Android call expected');}},
  agents:{get:id=>id in parents?{session:{header:{id,parentSession:parents[id]}}}:id==='root'||id==='s'?{session:{header:{id}}}:undefined},
 };
}

async function withConfig(fn){
 const root=await mkdtemp(join(tmpdir(),'hh-m292-'));
 try{const config=new Configuration(root);await config.reload();await fn(config,root);}finally{await rm(root,{recursive:true,force:true});}
}

test('approvalSessionFor: nearest own override wins, else the own id (= default)',()=>{
 const policy={mode:'manual',scopes:{sessions:{root:{mode:'none'},mid:{mode:'auto'}}}};
 assert.equal(approvalSessionFor(policy,['child','root']),'root');
 assert.equal(approvalSessionFor(policy,['leaf','mid','root']),'mid');
 assert.equal(approvalSessionFor(policy,['x','y']),'x');
 assert.equal(approvalSessionFor({},['x']),'x');
 assert.equal(approvalSessionFor(policy,[]),'');
});

test('sessionChain follows parentSession and stops on cycles / depth',()=>{
 const ctx=fakeCtx({c2:'c1',c1:'root',a:'b',b:'a'});
 assert.deepEqual(sessionChain(ctx,'c2'),['c2','c1','root']);
 assert.deepEqual(sessionChain(ctx,'a'),['a','b']);
 assert.deepEqual(sessionChain({mobilePolicy:{options:new Map()},androidBridge:{bootstrap:{}}},'x'),['x']);
});

test('no-approval session: every approval entry is allowed without a pending item, and audited',async()=>withConfig(async(config,root)=>{
 await config.write('approval.json',withApprovalMode(config.get('approval.json'),'none','s'));
 const ctx=fakeCtx();let pending=0;const ask=async()=>{pending++;return {approved:false};};
 for(const entry of ENTRIES){const r=await approval(ctx,config,{...entry,sessionId:'s'},ask);assert.equal(r.approved,true,entry.tool);}
 assert.equal(pending,0,'no approval card in a no-approval session');
 const audit=(await readFile(join(root,'audit/approvals.jsonl'),'utf8')).trim().split('\n').map(l=>JSON.parse(l));
 assert.equal(audit.length,ENTRIES.length);
 for(const a of audit){assert.equal(a.approver,'auto-none');assert.equal(a.decision,'allow');assert.equal(a.mode,'none');}
 // Same entries in a manual session do ask (the default is unchanged).
 for(const entry of ENTRIES)await approval(ctx,config,{...entry,sessionId:'manual-one'},ask);
 assert.equal(pending,ENTRIES.length);
}));

test('subagents of a no-approval session inherit it (root cause of cards in 无审批 sessions)',async()=>withConfig(async(config,root)=>{
 await config.write('approval.json',withApprovalMode(config.get('approval.json'),'none','root'));
 const ctx=fakeCtx({child:'root',grandchild:'child'});let pending=0;const ask=async()=>{pending++;return {approved:false};};
 for(const sid of ['child','grandchild'])for(const entry of ENTRIES)assert.equal((await approval(ctx,config,{...entry,sessionId:sid},ask)).approved,true);
 assert.equal(pending,0);
 assert.equal(isNoApproval(ctx,config,'grandchild'),true);
 assert.equal(isNoApproval(ctx,config,'unrelated'),false);
 const audit=(await readFile(join(root,'audit/approvals.jsonl'),'utf8')).trim().split('\n').map(l=>JSON.parse(l));
 assert.equal(audit[0].inheritedFrom,'root');
 // A child's own override still wins over the parent's.
 await config.write('approval.json',withApprovalMode(config.get('approval.json'),'manual','child'));
 await approval(ctx,config,{tool:'bash',command:'ls',sessionId:'child'},ask);
 assert.equal(pending,1);
}));

test('global default no-approval also covers sessions without their own setting',async()=>withConfig(async config=>{
 await config.write('approval.json',withApprovalMode(config.get('approval.json'),'none'));
 let pending=0;const ask=async()=>{pending++;return {approved:false};};
 for(const entry of ENTRIES)await approval(fakeCtx(),config,{...entry,sessionId:'fresh'},ask);
 assert.equal(pending,0);
}));

test('every plugin approval call goes through the wrapped androidBridge.call (no seam bypass)',async()=>{
 const dir=new URL('../../plugins/',import.meta.url);const offenders=[];const users=[];
 async function walk(u){for(const e of await readdir(u,{withFileTypes:true})){const child=new URL(e.name+(e.isDirectory()?'/':''),u);
  if(e.isDirectory()){if(e.name!=='node_modules')await walk(child);continue;}
  if(!e.name.endsWith('.ts'))continue;const src=await readFile(child,'utf8');
  for(const m of src.matchAll(/(\w[\w.]*)(?:<[^>]*>)?\(\s*'approve'/g)){users.push(e.name);if(!/^(ctx\.androidBridge\.call|originalCall)$/.test(m[1]))offenders.push(`${e.name}: ${m[1]}`);}
  if(/originalCall\('approve'/.test(src)&&!src.includes("approval(ctx,config,body as Data,()=>originalCall('approve',body,signal))"))offenders.push(e.name+': raw originalCall approve');}}
 await walk(dir);
 assert.deepEqual(offenders,[]);
 for(const f of ['index.ts'])assert.ok(users.includes(f));
});

test('phone steps under no-approval: Android is told, and a payment stop goes through the audited seam',async()=>withConfig(async(config,root)=>{
 await config.write('approval.json',withApprovalMode(config.get('approval.json'),'none','s'));
 let pending=0;const approve=action=>approval(fakeCtx(),config,action,async()=>{pending++;return {approved:false};});
 const calls=[];let round=0;
 const call=async(method,body)=>{calls.push(structuredClone(body));if(body.action==='grant')return {ok:true};round++;
  if(round===1)return {ok:false,results:[],pending:{index:0,step:'tap "立即支付"',action:'pay',matched:'立即支付',fingerprint:'b'.repeat(32),app:'com.eg.android.AlipayGphone'}};
  return {ok:true,completed:true,results:[{index:0,step:'tap',ok:true}]};};
 const r=await runSteps(call,approve,'s',checkSteps([{action:'tap',target:'立即支付'}]),undefined,undefined,true);
 assert.equal(r.completed,true);assert.equal(pending,0);
 assert.equal(calls[0].no_approval,true);
 assert.equal(calls.filter(c=>c.action==='grant').length,1);
 const audit=(await readFile(join(root,'audit/approvals.jsonl'),'utf8')).trim().split('\n').map(l=>JSON.parse(l));
 assert.equal(audit[0].risk,'irreversible:pay');assert.equal(audit[0].approver,'auto-none');
 // Without no-approval nothing changes: no flag, payment handed to the user.
 const plain=[];const p=await runSteps(async(m,b)=>{plain.push(b);return {ok:false,results:[],pending:{index:0,step:'tap',action:'pay',matched:'x',fingerprint:'c'.repeat(32)}};},approve,'s',checkSteps([{action:'tap',target:'x'}]));
 assert.equal(p.reason,'payment');assert.equal('no_approval' in plain[0],false);
 assert.match(NO_APPROVAL_PHONE_PROMPT,/No-approval mode/);
}));

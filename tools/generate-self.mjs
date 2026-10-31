import {readFile,writeFile,mkdir,mkdtemp,rm} from 'node:fs/promises';
import {createServer} from 'node:http';
import {spawn,execFileSync} from 'node:child_process';
import {once} from 'node:events';
import {tmpdir} from 'node:os';
import {resolve,join} from 'node:path';
import {fileURLToPath} from 'node:url';
const root=fileURLToPath(new URL('../',import.meta.url));
const kotlin=join(root,'app/src/main/java/app/hoshino/harness');
const read=path=>readFile(path,'utf8');
const routes=[...(await read(join(kotlin,'ui/nav/Routes.kt'))).matchAll(/const val (\w+) = "([^"]+)"/g)].map(m=>({id:m[1],route:m[2]}));
const events=[...(await read(join(kotlin,'core/HarnessClient.kt'))).matchAll(/data class (\w+)\(/g)].map(m=>m[1]);
const tokens=[...(await read(join(kotlin,'ui/theme/Theme.kt'))).split('val LightHoshinoColors')[0].matchAll(/val (\w+): Color/g)].map(m=>m[1]);
const gradle=await read(join(root,'app/build.gradle.kts'));const pkg=JSON.parse(await read(join(root,'node-host/package.json')));
const input={appVersion:gradle.match(/versionName = "([^"]+)"/)[1],dshVersion:pkg.dependencies['@deepseek-ai/dsh'],dshCommit:'f3803c96974ad4a94195fb97e5c637e9c2a3afe3',commit:execFileSync('git',['rev-parse','HEAD'],{cwd:root,encoding:'utf8'}).trim(),repository:'https://github.com/senseixiaomisensei-sudo/hoshino-harness-android',routes,tokens,events};
const dist=join(root,'node-host/dist');await mkdir(dist,{recursive:true});await writeFile(join(dist,'self-input.json'),JSON.stringify(input,null,2)+'\n');
await mkdir(join(dist,'plugins/hh-bridge'),{recursive:true});await writeFile(join(dist,'plugins/hh-bridge/index.d.ts'),await read(join(root,'plugins/hh-bridge/index.d.ts')));
const temp=await mkdtemp(join(tmpdir(),'hoshino-self-'));
const server=createServer(async(req,res)=>{for await(const chunk of req){};res.writeHead(200,{'Content-Type':'application/json'}).end(req.url==='/providers'?'[]':'{}');});
server.listen(0,'127.0.0.1');await once(server,'listening');
const child=spawn(process.execPath,[join(dist,'node-host/src/main.js'),'--self-document-only'],{cwd:join(root,'node-host'),stdio:['pipe','ignore','pipe']});let log='';child.stderr.on('data',b=>log+=b);
const timeout=setTimeout(()=>child.kill(),90000).unref();
child.stdin.end(JSON.stringify({callbackPort:server.address().port,callbackToken:'build-only-no-credentials',workspace:join(temp,'workspace'),home:join(temp,'home'),selfHome:join(dist,'generated-self-root'),safeMode:true})+'\n');
try {const [code]=await once(child,'exit');if(code!==0)throw new Error('Self manual generation failed: '+log.slice(-4000));
  const manifest=JSON.parse(await read(join(dist,'generated-self-root/self/manifest.json')));if(!manifest.tools.length || !manifest.plugins.length)throw new Error('Empty manual registry');
  // m2.9: an optional built-in plugin whose apply() throws only prints "did not activate"; fail the build instead (android-phone shipped without any phone_* tool in m2.8/m2.8.1).
  const toolNames=new Set(manifest.tools.map(t=>t.name??t));const missing=['phone_launch_app','phone_status','phone_do_steps','system_shell','get_device_info','self_doctor'].filter(n=>!toolNames.has(n));if(missing.length)throw new Error('Built-in tools missing from the boot registry (a plugin did not activate): '+missing.join(', ')+'\n'+log.slice(-4000));
  console.log(`Generated manuals: ${manifest.plugins.length} actual boot plugins, ${manifest.tools.length} tools, ${routes.length} routes.`);
} finally {clearTimeout(timeout);server.closeAllConnections();await new Promise(r=>server.close(r));await rm(temp,{recursive:true,force:true});}

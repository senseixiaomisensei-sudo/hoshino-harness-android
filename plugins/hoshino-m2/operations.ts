import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,readdir,lstat,cp,rename,open,rm} from 'node:fs/promises';
import {createReadStream,createWriteStream} from 'node:fs';
import {once} from 'node:events';
import {Zip,ZipDeflate} from 'fflate';
import {join,resolve,dirname,relative} from 'node:path';
import {randomUUID,createHash} from 'node:crypto';
import type {Context} from '../../node-host/src/platform.js';
import {workspacePath} from '../android-policy/paths.js';
import {Configuration,atomic,type Data} from './config.js';
export async function command(program:string,args:string[],cwd:string,timeoutMs=120000):Promise<{code:number;output:string}>{return new Promise((done,reject)=>{
 const child=spawn(program,args,{cwd,stdio:['ignore','pipe','pipe']});let output='';const add=(b:Buffer)=>{output=(output+b.toString()).slice(-128000);};child.stdout.on('data',add);child.stderr.on('data',add);child.on('error',reject);
 const timer=setTimeout(()=>{child.kill('SIGTERM');setTimeout(()=>child.kill('SIGKILL'),2000).unref();},timeoutMs).unref();child.on('close',code=>{clearTimeout(timer);done({code:code??-1,output});});
});}
export async function checkpoint(root:string,workspace:string):Promise<string>{
 const id=Date.now()+'-'+randomUUID().slice(0,8);const target=join(root,'checkpoints',id);await mkdir(target,{recursive:true});const items:Data[]=[];let bytes=0;
 async function visit(dir:string){for(const e of await readdir(dir,{withFileTypes:true})){if(['.git','node_modules','.gradle','build','.hoshino'].includes(e.name))continue;const path=join(dir,e.name);const s=await lstat(path);if(s.isSymbolicLink())continue;if(e.isDirectory())await visit(path);else if(s.isFile()){if(bytes+s.size>512*1024*1024)throw Error('Checkpoint exceeds 512 MiB; narrow the workspace before making changes');bytes+=s.size;const rel=relative(workspace,path);await mkdir(dirname(join(target,'files',rel)),{recursive:true});await cp(path,join(target,'files',rel),{preserveTimestamps:true});items.push({path:rel,bytes:s.size});}}}
 await visit(workspace);await atomic(join(target,'manifest.json'),{id,workspace,items,bytes});await pruneHistory(root,'checkpoints',id);return id;
}
export async function restoreCheckpoint(root:string,workspace:string,id:string){if(!/^\d+-[a-f0-9]{8}$/.test(id))throw Error('Invalid checkpoint');const target=await workspacePath(join(root,'checkpoints'),id);const manifest=JSON.parse(await readFile(join(target,'manifest.json'),'utf8'));if(manifest.workspace!==workspace)throw Error('Checkpoint belongs to another workspace');const safety=await checkpoint(root,workspace);for(const item of manifest.items){const source=await workspacePath(join(target,'files'),item.path);const dest=await workspacePath(workspace,item.path);await mkdir(dirname(dest),{recursive:true});await cp(source,dest);}return {restored:id,safetySnapshot:safety,note:'Files created after the checkpoint remain; no unrelated files are deleted.'};}
export async function installPlugin(ctx:Context,config:Configuration,spec:string){
 if(!spec || spec.length>2000 || spec.startsWith('-') || /[\x00-\x1f]/.test(spec))throw Error('Invalid plugin specification');
 const dir=join(config.root,'plugin-packages');await mkdir(dir,{recursive:true});await atomic(join(dir,'package.json'),{private:true,type:'module'});
 try{await command('npm',['--version'],dir,10000);}catch{const install=await command('apk',['add','--no-cache','npm','git'],dir,600000);if(install.code)throw Error('Could not download optional npm/git; check network and retry');}
 const result=await command('npm',['install','--no-audit','--no-fund','--save-exact','--',spec],dir,600000);
 if(result.code!==0)return {ok:false,error:'Plugin dependency installation failed. Native addons require a musl arm64 prebuild or ensure_tools native-build, then retry.',detail:result.output.slice(-8000)};
 const pkg=JSON.parse(await readFile(join(dir,'package.json'),'utf8'));const name=Object.keys(pkg.dependencies??{}).at(-1);if(!name)throw Error('Package did not declare an entry');
 const entry=join(dir,'node_modules',name);const manifest=JSON.parse(await readFile(join(entry,'package.json'),'utf8'));const id=name.replace(/^@/,'').replace(/[^a-zA-Z0-9-]/g,'-').toLowerCase();const dest=join(config.root,'plugins',id);await mkdir(dest,{recursive:true});
 const {createRequire}=await import('node:module');const require=createRequire(join(dir,'package.json'));const resolved=require.resolve(name);
 await writeFile(join(dest,'index.js'),`export * from ${JSON.stringify('file://'+resolved)};\n`);await atomic(join(dest,'config.json'),{});
 return {ok:true,id,name,version:manifest.version,entry:resolved,next:`self_reload plugin=${id}`,compatibility:'Installed using npm; actual Cordis activation is checked on reload'};
}
export async function gitOperation(workspace:string,action:string,args:Data){
 const allowed:Record<string,string[]>={status:['status','--short'],diff:['diff','--no-ext-diff'],branches:['branch','--list'],commit:['commit','-m',String(args.message??'')],push:['push'],branch:['switch','-c',String(args.name??'')]};
 const argv=allowed[action];if(!argv)throw Error('Unsupported git action');if(action==='branch'&&!/^[\w./-]+$/.test(String(args.name)))throw Error('Invalid branch');
 if(action==='commit'){const paths=(args.paths??[]) as string[];if(!paths.length)throw Error('Choose files to commit');for(const path of paths)await workspacePath(workspace,path);const stage=await command('git',['add','--',...paths],workspace);if(stage.code)return stage;}
 return command('git',argv,workspace);
}
export async function ensureTools(group:string,root:string){
 const packages:Record<string,string[]>={media:['ffmpeg','7zip','imagemagick'],documents:['python3','py3-pip','py3-lxml','py3-pillow','py3-openpyxl','py3-reportlab','poppler-utils'],speech:['espeak-ng'],git:['git','openssh-client'],pandoc:['pandoc'], 'native-build':['python3','make','g++','linux-headers','pkgconf']};
 if(!packages[group])throw Error('Choose media, documents, speech, git, pandoc or native-build');const result=await command('apk',['add','--no-cache',...packages[group]],root,600000);if(result.code||group!=='documents')return result;
 const home=process.env.HOME??'/root';const venv=join(home,'.hoshino/tools/python');const setup=await command('python3',['-m','venv','--system-site-packages',venv],root,120000);if(setup.code)return setup;
 return command(join(venv,'bin/python'),['-m','pip','install','--disable-pip-version-check','python-docx==1.2.0','python-pptx==1.0.2'],root,600000);
}
export async function exportBackup(root:string,workspace:string,home?:string){
 const path=join(workspace,'Hoshino-backup-'+Date.now()+'.zip');const output=createWriteStream(path);let error:unknown;output.on('error',e=>{error=e;});
 const zip=new Zip((e,chunk,final)=>{if(e){error=e;output.destroy(e);return;}output.write(chunk);if(final)output.end();});
 async function add(dir:string,prefix:string){for(const e of await readdir(dir,{withFileTypes:true}).catch(()=>[])){if(['node_modules','.git','updates','checkpoints','snapshots','audit'].includes(e.name))continue;const full=join(dir,e.name);const s=await lstat(full);if(s.isSymbolicLink())continue;const name=prefix+e.name;if(e.isDirectory())await add(full,name+'/');else if(s.isFile()){const file=new ZipDeflate(name,{level:1});zip.add(file);for await(const data of createReadStream(full,{highWaterMark:65536})){file.push(data as Buffer,false);if(output.writableNeedDrain)await once(output,'drain');if(error)throw error;}file.push(new Uint8Array(),true);}}}
 for(const part of ['config','plugins','skills','ui','prompts','modes'])await add(join(root,part),'self/'+part+'/');
 if(home)for(const part of ['sessions','storages'])await add(join(home,part),'home/'+part+'/');
 zip.end();await once(output,'finish');if(error)throw error;return {path,credentials:'Keystore secrets are excluded; re-enter credentials on a different device.'};
}
export async function restoreBackup(root:string,workspace:string,home:string,path:string){
 const source=await workspacePath(workspace,path);const f=await open(source,'r');
 try{
  const stat=await f.stat();const tail=Buffer.alloc(Math.min(stat.size,65557));await f.read(tail,0,tail.length,stat.size-tail.length);let eocd=-1;for(let i=tail.length-22;i>=0;i--)if(tail.readUInt32LE(i)===0x06054b50){eocd=i;break;}if(eocd<0)throw Error('Not a supported ZIP archive');
  const count=tail.readUInt16LE(eocd+10),size=tail.readUInt32LE(eocd+12),offset=tail.readUInt32LE(eocd+16);if(count>20000||size>16*1024*1024)throw Error('Backup catalog too large');const catalog=Buffer.alloc(size);await f.read(catalog,0,size,offset);let i=0,total=0;
  while(i<catalog.length){if(catalog.readUInt32LE(i)!==0x02014b50)throw Error('Invalid ZIP catalog');const len=catalog.readUInt16LE(i+28),extra=catalog.readUInt16LE(i+30),comment=catalog.readUInt16LE(i+32),name=catalog.subarray(i+46,i+46+len).toString('utf8');const mode=catalog.readUInt32LE(i+38)>>>16;total+=catalog.readUInt32LE(i+24);if(!/^(self\/(config|plugins|skills|ui|prompts|modes)|home\/(sessions|storages))\//.test(name)||name.split(/[\\/]/).includes('..')||name.includes('\\')||(mode&0xf000)===0xa000||total>1024*1024*1024)throw Error('Unsafe or oversized backup entry');i+=46+len+extra+comment;}
 }finally{await f.close();}
 const safety=await exportBackup(root,workspace,home);const staging=join(root,'restore-'+randomUUID());await mkdir(staging);const result=await command('unzip',['-q',source,'-d',staging],workspace,300000);if(result.code)throw Error('ZIP extraction failed; original data is unchanged');
 for(const part of ['config','plugins','skills','ui','prompts','modes'])try{await cp(join(staging,'self',part),join(root,part),{recursive:true,force:true});}catch(e){if((e as NodeJS.ErrnoException).code!=='ENOENT')throw e;}
 for(const part of ['sessions','storages'])try{await cp(join(staging,'home',part),join(home,part),{recursive:true,force:true});}catch(e){if((e as NodeJS.ErrnoException).code!=='ENOENT')throw e;}
 await rm(staging,{recursive:true,force:true});return {restored:true,safetyBackup:safety.path,restartRequired:true};
}
export async function selfSnapshot(root:string){const id=Date.now()+'-'+randomUUID().slice(0,8);for(const part of ['config','plugins','skills','ui','source','prompts','modes']){const dir=join(root,part);await mkdir(dir,{recursive:true});await cp(dir,join(root,'snapshots',id,part),{recursive:true,dereference:false,filter:s=>!['node_modules','.git'].includes(s.split(/[\\/]/).at(-1)!)});}await pruneHistory(root,'snapshots',id);return id;}
async function pruneHistory(root:string,folder:string,current:string){
 const base=join(root,folder);let kept=0,total=0;
 async function bytes(path:string):Promise<number>{const s=await lstat(path);if(s.isSymbolicLink())return 0;if(s.isFile())return s.size;let n=0;for(const e of await readdir(path))n+=await bytes(join(path,e));return n;}
 for(const id of (await readdir(base)).filter(x=>/^\d+-[a-f0-9]{8}$/.test(x)).sort().reverse()){
  const path=await workspacePath(base,id);const size=await bytes(path);
  if(id!==current&&(kept>=20||kept>0&&total+size>512*1024*1024))await rm(path,{recursive:true,force:true});else{kept++;total+=size;}
 }
}
export async function safePublicPath(root:string,path:string){if(!/^(config|ui|skills|plugins|source|prompts|modes)\//.test(path))throw Error('Public self paths must start with config/, ui/, skills/, plugins/, source/, prompts/ or modes/');return workspacePath(root,path);}

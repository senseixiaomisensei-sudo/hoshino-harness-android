import {spawn} from 'node:child_process';
import {mkdir,readFile,writeFile,readdir,lstat,cp,rename} from 'node:fs/promises';
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
 await visit(workspace);await atomic(join(target,'manifest.json'),{id,workspace,items,bytes});return id;
}
export async function restoreCheckpoint(root:string,workspace:string,id:string){if(!/^\d+-[a-f0-9]{8}$/.test(id))throw Error('Invalid checkpoint');const target=await workspacePath(join(root,'checkpoints'),id);const manifest=JSON.parse(await readFile(join(target,'manifest.json'),'utf8'));if(manifest.workspace!==workspace)throw Error('Checkpoint belongs to another workspace');const safety=await checkpoint(root,workspace);for(const item of manifest.items){const source=await workspacePath(join(target,'files'),item.path);const dest=await workspacePath(workspace,item.path);await mkdir(dirname(dest),{recursive:true});await cp(source,dest);}return {restored:id,safetySnapshot:safety,note:'Files created after the checkpoint remain; no unrelated files are deleted.'};}
export async function installPlugin(ctx:Context,config:Configuration,spec:string){
 if(!spec || spec.length>2000 || spec.startsWith('-') || /[\x00-\x1f]/.test(spec))throw Error('Invalid plugin specification');
 const dir=join(config.root,'plugin-packages');await mkdir(dir,{recursive:true});await atomic(join(dir,'package.json'),{private:true,type:'module'});
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
 const packages:Record<string,string[]>={media:['ffmpeg','p7zip','imagemagick'],documents:['pandoc','python3','py3-pillow','py3-openpyxl','py3-docx','py3-reportlab','poppler-utils'],speech:['espeak-ng'],git:['git','openssh-client'], 'native-build':['python3','make','g++','linux-headers','pkgconf']};
 if(!packages[group])throw Error('Choose media, documents, speech, git or native-build');return command('apk',['add','--no-cache',...packages[group]],root,600000);
}
export async function exportBackup(root:string,workspace:string){const out=join(workspace,'Hoshino-backup-'+Date.now()+'.tar.gz');const result=await command('tar',['-czf',out,'--exclude=plugin-packages/node_modules','--exclude=updates','--exclude=audit','-C',root,'.'],workspace,300000);if(result.code)throw Error('Backup archive failed');return {path:out,credentials:'Keystore secrets are excluded; re-enter credentials on a different device.'};}
export async function safePublicPath(root:string,path:string){if(!/^(config|ui|skills|plugins|source|prompts|modes)\//.test(path))throw Error('Public self paths must start with config/, ui/, skills/, plugins/, source/, prompts/ or modes/');return workspacePath(root,path);}

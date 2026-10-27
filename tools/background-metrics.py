"""One idle/background observation, no model calls or stress workload."""
import subprocess,time,json,pathlib,re,datetime
out=pathlib.Path('reports');out.mkdir(exist_ok=True)
def adb(*args,check=True):return subprocess.run(['adb',*args],text=True,stdout=subprocess.PIPE,stderr=subprocess.STDOUT,check=check).stdout.strip()
def json_file(path):
 try:return json.loads(adb('shell','run-as','dev.hoshino.app','cat',path))
 except (subprocess.CalledProcessError,ValueError):return None
adb('shell','am','force-stop','dev.hoshino.app')
launch=adb('shell','am','start','-W','-n','dev.hoshino.app/app.hoshino.harness.MainActivity')
deadline=time.monotonic()+300
while time.monotonic()<deadline:
 health=json_file('files/m2-runtime-health.json')
 if health and health.get('phase')=='ready':break
 time.sleep(2)
else:raise SystemExit('Kernel did not become ready during cold launch')
idle=adb('shell','dumpsys','meminfo','dev.hoshino.app')
initial_app=adb('shell','pidof','dev.hoshino.app',check=False)
initial_node=(json_file('files/hoshino/runtime-process.json') or {}).get('pid')
node_memory=adb('shell','run-as','dev.hoshino.app','cat',f'/proc/{initial_node}/status',check=False) if isinstance(initial_node,int) else ''
adb('shell','input','keyevent','KEYCODE_HOME');adb('shell','input','keyevent','KEYCODE_POWER')
samples=[];start=time.monotonic()
for index in range(31):
 if index:time.sleep(max(0,start+index*60-time.monotonic()))
 app=adb('shell','pidof','dev.hoshino.app',check=False)
 record=json_file('files/hoshino/runtime-process.json') or {}
 pid=record.get('pid');node=False
 if isinstance(pid,int) and pid>1:node='State:' in adb('shell','run-as','dev.hoshino.app','cat',f'/proc/{pid}/status',check=False)
 samples.append({'elapsedSeconds':round(time.monotonic()-start,2),'appPid':app,'kernelPid':pid,'appAlive':bool(re.fullmatch(r'[\d ]+',app)) and app==initial_app,'kernelAlive':node and pid==initial_node})
 (out/'m2-background-progress.json').write_text(json.dumps(samples,indent=2))
result={'environment':'GitHub Actions API 36 x86_64 AOSP emulator; charging status/OEM policies differ from a physical phone','startedAt':datetime.datetime.now(datetime.timezone.utc).isoformat(),'observationSeconds':round(time.monotonic()-start,2),'samples':samples,'survivalRate':sum(r['appAlive'] and r['kernelAlive'] for r in samples)/len(samples),'coldActivityLaunch':launch,'coldStartToInput':json_file('files/m2-input-ready.json'),'firstBootKernelReadyMs':health['readyAt']-health['startedAt'],'idleMeminfo':idle}
(out/'m2-background-metrics.json').write_text(json.dumps(result,indent=2))
(out/'m2-idle-node-status.txt').write_text(node_memory)
assert result['survivalRate']==1,'App/kernel did not survive every background sample'
print('30 minute background observation passed; raw measurements saved')

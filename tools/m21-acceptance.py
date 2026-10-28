"""Only the seven requested acceptance groups. This runner covers groups 2–7."""
import subprocess,pathlib,json,hashlib,time,shlex,datetime,re
ROOT=pathlib.Path('reports');ROOT.mkdir(exist_ok=True)
PKG='dev.hoshino.app';TEST=PKG+'.test/androidx.test.runner.AndroidJUnitRunner'
def adb(*args,check=True,input=None):
 result=subprocess.run(['adb',*args],input=input,stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if check and result.returncode:raise RuntimeError('adb command failed: '+result.stderr.decode(errors='replace')[:500])
 return result.stdout

def shell(*args):return adb('shell',*args).decode().strip()
def read(path):return adb('exec-out','run-as',PKG,'cat',path)
def write(path,data):adb('exec-out','run-as',PKG,'sh','-c','cat > '+shlex.quote(path),input=data)
def instrument(klass,name):
 result=adb('shell','am','instrument','-w','-r','-e','class',klass,TEST).decode(errors='replace');(ROOT/(name+'.txt')).write_text(result)
 if not re.search(r'OK \(1 test\)',result):raise RuntimeError(name+' failed; see native report')
def age_fixture():
 seed=json.loads(read('files/m21-upgrade-seed.json'));ids=seed['sessions'];now=int(shell('date','+%s'))*1000
 paths=shell('run-as',PKG,'find','files/dsh-home/sessions','-type','f','-name','*.jsonl').splitlines();witness={};expected={}
 for path in paths:
  if not path.startswith('files/dsh-home/sessions/'):raise RuntimeError('Unexpected fixture path')
  lines=[json.loads(s) for s in read(path).decode().splitlines() if s.strip()]
  if not lines or lines[0].get('id') not in ids:continue
  sid=lines[0]['id'];index=ids.index(sid);age=[3600000,86400000,604800000][index];target=now-age-5000
  original=max((e.get('event',e).get('time',0) for e in lines[1:] if e.get('event',e).get('type') in ['user/message','assistant/message']),default=0)
  if not original:raise RuntimeError('Fixture has no original message timestamp')
  shift=original-target
  def move(value):
   if isinstance(value,dict):return {k:move(v) for k,v in value.items()}
   if isinstance(value,list):return [move(v) for v in value]
   if isinstance(value,(int,float)) and value>100000000000:return value-shift
   return value
  data=('\n'.join(json.dumps(move(row),ensure_ascii=False,separators=(',',':')) for row in lines)+'\n').encode();write(path,data);witness[path]=hashlib.sha256(data).hexdigest();expected[sid]=target
 seed['lastTimes']=expected
 if len(expected)!=3:raise RuntimeError('Expected exactly three historical fixture sessions')
 write('files/m21-upgrade-seed.json',json.dumps(seed).encode());(ROOT/'history-before.json').write_text(json.dumps(witness,indent=2));return witness

witness={}
try:
 adb('reverse','tcp:8099','tcp:8099')
 old='old-m2.apk';new='artifacts/build/deliverables/Hoshino-Harness-0.2.1-m2-x86_64-debug.apk';test='artifacts/app/build/outputs/apk/androidTest/x86/debug/app-x86-debug-androidTest.apk'
 adb('install','--no-streaming','-r',old);adb('install','--no-streaming','-r',test);adb('shell','pm','grant',PKG,'android.permission.POST_NOTIFICATIONS')
 instrument('app.hoshino.harness.M21UpgradeSeedTest','case2-old-data-setup')
 adb('shell','am','force-stop',PKG);witness=age_fixture();adb('install','--no-streaming','-r',new)
 for index,method in [(2,'OverwritePreservesConfigurationAndSessions'),(3,'ResidentKernelReconnectsWithinOneSecond'),(4,'ColdFrameAndDurableWaitingMessage'),(5,'ClickToFirstHttpWithin500ms'),(6,'OriginalHistoryTimesSurviveColdReconnect'),(7,'MockChatGptOAuthStreamingRefreshAndQuota')]:
  if index in (4,6):adb('shell','am','force-stop',PKG)
  instrument('app.hoshino.harness.M21AcceptanceTest#case'+str(index)+method,'case'+str(index))
  if index==6:
   after={p:hashlib.sha256(read(p)).hexdigest() for p in witness};(ROOT/'history-after.json').write_text(json.dumps(after,indent=2));assert after==witness,'Opening/reconnecting modified original historical JSONL'
 metrics=json.loads(read('files/m21-acceptance.json'));assert set(metrics)==set(map(str,range(2,8))) and all(v['passed'] for v in metrics.values())
 metrics['6']['originalJsonlUnchanged']=True;(ROOT/'m21-acceptance.json').write_text(json.dumps(metrics,ensure_ascii=False,indent=2));print('Requested acceptance groups 2–7 passed')
finally:
 for source,name in [('files/m21-acceptance.json','m21-partial-acceptance.json'),('files/m2-input-ready.json','m21-first-frame.json')]:
  result=adb('exec-out','run-as',PKG,'cat',source,check=False)
  if result:(ROOT/name).write_bytes(result)
 crash=adb('logcat','-b','crash','-d','-v','brief',check=False).decode(errors='replace');crash=re.sub(r'Bearer [^\s]+','Bearer [redacted]',crash);crash=re.sub(r'eyJ[A-Za-z0-9_.-]+','[redacted JWT]',crash);(ROOT/'crash.txt').write_text(crash)

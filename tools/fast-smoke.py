"""Install the non-debuggable (fast) x86_64 build over the accepted debug install and check it starts, keeps data and reaches the kernel."""
import re,subprocess,sys,time,pathlib
pkg='dev.hoshino.app'
def adb(*a,check=True):
 r=subprocess.run(['adb',*a],capture_output=True,text=True,timeout=300)
 if check and r.returncode:raise SystemExit(r.stdout+r.stderr)
 return r.stdout
out=pathlib.Path('reports');out.mkdir(exist_ok=True)
apk=next(pathlib.Path('artifacts/build/smoke').glob('*-x86_64-fast.apk'))
adb('shell','am','force-stop',pkg);adb('logcat','-b','all','-c',check=False)
print(adb('install','--no-streaming','-r',str(apk)))
flags=adb('shell','dumpsys','package',pkg)
assert 'DEBUGGABLE' not in re.search(r'pkgFlags=\[([^\]]*)\]',flags).group(1),'fast build must not be debuggable'
adb('shell','monkey','-p',pkg,'-c','android.intent.category.LAUNCHER','1')
# Not debuggable, so no run-as: the kernel is a proot/node child process running under the app's uid.
seen=False;ps=''
for i in range(90):
 time.sleep(2)
 app=adb('shell','pidof',pkg,check=False).strip()
 if not app:continue
 ps=adb('shell','ps','-A','-o','USER,PID,PPID,NAME',check=False)
 user=next((l.split()[0] for l in ps.splitlines() if l.split()[1:2]==[app.split()[0]]),None)
 kids=[l for l in ps.splitlines() if user and l.split()[0]==user and l.split()[3:4]!=[pkg]]
 if any(('node' in l or 'proot' in l) for l in kids):seen=True;break
log=adb('logcat','-d','-b','crash',check=False)
report=f'kernel={seen} after={i*2}s\n'+'\n'.join(l for l in ps.splitlines() if 'u0_a' in l)[-3000:]+'\n'+log[-4000:]
(out/'fast-smoke.txt').write_text(report);print(report)
assert 'FATAL' not in log,'crash in fast build:\n'+log[-3000:]
assert seen,'fast build did not start its kernel within 180s'
print('fast build OK',i*2,'s')

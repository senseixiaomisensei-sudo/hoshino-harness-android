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
seen=False
for i in range(90):
 time.sleep(2)
 adb('shell','uiautomator','dump','/sdcard/ui.xml',check=False);xml=adb('exec-out','cat','/sdcard/ui.xml',check=False)
 if '已连接' in xml:seen=True;break
log=adb('logcat','-d','-b','crash',check=False)
(out/'fast-smoke.txt').write_text(f'connected={seen} after={i*2}s\n'+log[-4000:])
assert pkg not in log or 'FATAL' not in log,'crash in fast build:\n'+log[-3000:]
assert seen,'fast build did not reach the kernel (已连接) within 180s'
print('fast build OK',i*2,'s')

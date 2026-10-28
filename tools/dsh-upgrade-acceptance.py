import subprocess,pathlib,re,json
out=pathlib.Path('reports');out.mkdir(exist_ok=True);pkg='dev.hoshino.app';runner=pkg+'.test/androidx.test.runner.AndroidJUnitRunner'
def adb(*args,check=True):
 r=subprocess.run(['adb',*args],stdout=subprocess.PIPE,stderr=subprocess.PIPE)
 if check and r.returncode:raise RuntimeError(r.stderr.decode(errors='replace')[:500])
 return r.stdout

def test(name,label):
 data=adb('shell','am','instrument','-w','-r','-e','class',name,runner).decode(errors='replace');(out/(label+'.txt')).write_text(data)
 if not re.search(r'OK \(1 test\)',data):raise RuntimeError(label+' failed; see instrument report')
try:
 adb('reverse','tcp:8099','tcp:8099');adb('install','--no-streaming','-r','old-m2.apk');adb('install','--no-streaming','-r','artifacts/app/build/outputs/apk/androidTest/x86/debug/app-x86-debug-androidTest.apk');adb('shell','pm','grant',pkg,'android.permission.POST_NOTIFICATIONS')
 test('app.hoshino.harness.M21UpgradeSeedTest','old-data-setup')
 adb('shell','am','force-stop',pkg);adb('install','--no-streaming','-r','artifacts/build/deliverables/Hoshino-Harness-0.2.1-m2-dsh-alpha.2-x86_64-debug.apk')
 test('app.hoshino.harness.DshUpgradeAcceptanceTest#overwriteConnectsAndRetainsOldSessions','case2-overwrite')
 test('app.hoshino.harness.DshUpgradeAcceptanceTest#realKernelChatsAndExecutesTools','case3-chat-tools')
 data=json.loads(adb('exec-out','run-as',pkg,'cat','files/dsh-upgrade-acceptance.json'));assert set(data)=={'2','3'} and all(v['passed'] for v in data.values());(out/'dsh-upgrade-acceptance.json').write_text(json.dumps(data,ensure_ascii=False,indent=2))
finally:
 partial=adb('exec-out','run-as',pkg,'cat','files/dsh-upgrade-acceptance.json',check=False)
 if partial:(out/'partial.json').write_bytes(partial)
 health=adb('exec-out','run-as',pkg,'cat','files/m2-runtime-health.json',check=False)
 if health:(out/'runtime-health.json').write_bytes(health)
 raw=adb('logcat','-b','crash','-d','-v','brief',check=False).decode(errors='replace');raw=re.sub(r'Bearer [^\s]+','Bearer [redacted]',raw);(out/'crash.txt').write_text(raw)

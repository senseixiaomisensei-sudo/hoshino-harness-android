"""Start only the pinned official manager's adb server on a selected test emulator."""
import argparse,subprocess,time
parser=argparse.ArgumentParser();parser.add_argument('--serial',required=True);args=parser.parse_args()
assert args.serial.startswith('emulator-'),'This helper is restricted to test emulators'
base=['adb','-s',args.serial,'shell']
package=subprocess.check_output(base+['dumpsys','package','moe.shizuku.privileged.api'],text=True)
lib=next(line.strip().split('=',1)[1] for line in package.splitlines() if 'legacyNativeLibraryDir=' in line)
abi=subprocess.check_output(base+['getprop','ro.product.cpu.abi'],text=True).strip()
arch={'x86_64':'x86_64','arm64-v8a':'arm64'}[abi]
existing=subprocess.run(base+['pidof','shizuku_server'],capture_output=True,text=True)
if existing.returncode:
    subprocess.run(base+[lib+'/'+arch+'/libshizuku.so'],check=True,timeout=30)
deadline=time.monotonic()+30
while True:
    result=subprocess.run(base+['pidof','shizuku_server'],capture_output=True,text=True)
    if not result.returncode:break
    assert time.monotonic()<deadline,'Shizuku test server failed to start'
    time.sleep(.5)
print('Official Shizuku fixture server ready, pid',result.stdout.strip())

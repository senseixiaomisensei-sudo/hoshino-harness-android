"""Package signed dual-ABI APKs and measured byte/hash metadata."""
import hashlib,json,pathlib,shutil,zipfile
root=pathlib.Path(__file__).resolve().parents[1];output=root/'build/deliverables';output.mkdir(parents=True,exist_ok=True)
records=[]
smoke=root/'build/smoke';smoke.mkdir(parents=True,exist_ok=True)
# arm64: user build (fast = same signature, not debuggable). x86_64: debug build used by the emulator acceptance.
for flavor,abi,btype,suffix,dest in [('arm64','arm64-v8a','fast','',output),('x86','x86_64','debug','-debug',output),('x86','x86_64','fast','-fast',smoke)]:
 source=root/f'app/build/outputs/apk/{flavor}/{btype}/app-{flavor}-{btype}.apk'
 with zipfile.ZipFile(source) as apk:
  assert {n.split('/')[1] for n in apk.namelist() if n.startswith('lib/')}=={abi}
  assert f'assets/kernel/{abi}.txz' in apk.namelist()
  assert 'assets/harness-source.zip' in apk.namelist()
 name=f'Hoshino-Harness-0.2.1-m2.5-{abi}{suffix}.apk';target=dest/name;shutil.copyfile(source,target)
 if dest is output:records.append({'abi':abi,'apk':name,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()})
(output/'SHA256SUMS-0.2.1-m2.5.txt').write_text(''.join(f"{r['sha256']}  {r['apk']}\n" for r in records))
(root/'docs/dsh-upgrade-apk-sizes.json').write_text(json.dumps(records,indent=2)+'\n')
print(json.dumps(records))

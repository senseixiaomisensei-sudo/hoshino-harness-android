"""Produce versioned M1 deliverables and validate real APK ABI/assets and hashes."""
import hashlib,json,pathlib,shutil,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
output=root/'build/deliverables';output.mkdir(parents=True,exist_ok=True)
records=[]
for flavor,abi in [('arm64','arm64-v8a'),('x86','x86_64')]:
    source=root/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
    with zipfile.ZipFile(source) as apk:
        names=apk.namelist()
        assert [n for n in names if n.startswith('assets/kernel/') and n.endswith('.txz')]==[f'assets/kernel/{abi}.txz']
        assert {n.split('/')[1] for n in names if n.startswith('lib/')}=={abi}
        largest=[{'path':i.filename,'bytes':i.file_size} for i in sorted(apk.infolist(),key=lambda i:i.file_size,reverse=True)[:10]]
    name=f'Hoshino-Harness-0.1.0-m1-{abi}-debug.apk';target=output/name;shutil.copyfile(source,target)
    records.append({'abi':abi,'apk':name,'bytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'within150MB':target.stat().st_size<=150000000,'largestFiles':largest})
(root/'docs/m1-apk-sizes.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
(output/'SHA256SUMS-0.1.0-m1.txt').write_text(''.join(f"{r['sha256']}  {r['apk']}\n" for r in records),encoding='utf-8')
print(json.dumps([{'abi':r['abi'],'bytes':r['bytes'],'sha256':r['sha256']} for r in records]))

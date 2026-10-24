"""Record actual ABI APK sizes, hashes and contents; no estimated sizes."""
import hashlib,json,pathlib,zipfile
ROOT=pathlib.Path(__file__).resolve().parents[1]
result=[]
for flavor,abi in [('arm64','arm64-v8a'),('x86','x86_64')]:
    path=ROOT/f'app/build/outputs/apk/{flavor}/debug/app-{flavor}-debug.apk'
    with zipfile.ZipFile(path) as archive:
        archives=[n for n in archive.namelist() if n.startswith('assets/kernel/') and n.endswith('.txz')]
        native={n.split('/')[1] for n in archive.namelist() if n.startswith('lib/')}
        assert archives==[f'assets/kernel/{abi}.txz'],(flavor,archives)
        assert native=={abi},(flavor,native)
        largest=[{'path':i.filename,'bytes':i.file_size} for i in sorted(archive.infolist(),key=lambda i:i.file_size,reverse=True)[:10]]
    digest=hashlib.sha256()
    with path.open('rb') as source:
        for block in iter(lambda:source.read(1048576),b''):digest.update(block)
    result.append({'abi':abi,'apk':path.relative_to(ROOT).as_posix(),'bytes':path.stat().st_size,'sha256':digest.hexdigest(),'within150MB':path.stat().st_size<=150000000,'largestFiles':largest})
(ROOT/'docs/m0-apk-sizes.json').write_text(json.dumps(result,indent=2)+'\n')
print(json.dumps(result))

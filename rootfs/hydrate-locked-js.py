"""Restore only missing architecture-independent dsh npm packages from the immutable lock."""
import base64,hashlib,io,json,pathlib,posixpath,sys,tarfile,urllib.request
root=pathlib.Path(__file__).resolve().parents[1];sys.path.insert(0,str(root/'rootfs'))
from pack import pack
lock=json.loads((root/'node-host/package-lock.json').read_text(encoding='utf-8'))['packages']
prefix='opt/hoshino/node-host/';cache=root/'.research/runtime'
for abi in ['arm64-v8a','x86_64']:
    source=root/f'app/src/main/assets/kernel/{abi}.txz';packages={};added={}
    with tarfile.open(source,'r:*') as archive:
        for member in archive:
            name=member.name.removeprefix('./')
            if name.startswith(prefix+'node_modules/') and name.endswith('/package.json') and member.isfile():
                try:packages[posixpath.dirname(name)]=json.load(archive.extractfile(member))
                except (ValueError,UnicodeError):pass
    def locate(name,parent=prefix.rstrip('/')):
        current=parent
        while True:
            path=posixpath.join(current,'node_modules',name)
            if path in packages:return path
            previous=posixpath.dirname(current)
            if previous==current:break
            current=previous
        assert name.startswith('@deepseek-ai/dsh-'),'Refuse non-dsh/native package hydration: '+name
        record=lock['node_modules/'+name];assert record['version']=='0.2.1-rc.2'
        payloadPath=cache/(name.split('/')[-1]+'-'+record['version']+'.tgz')
        if not payloadPath.exists():
            with urllib.request.urlopen(record['resolved'],timeout=60) as response:payloadPath.write_bytes(response.read())
        payload=payloadPath.read_bytes();algorithm,expected=record['integrity'].split('-',1)
        assert base64.b64encode(hashlib.new(algorithm,payload).digest()).decode()==expected
        path=prefix+'node_modules/'+name;members=[]
        with tarfile.open(fileobj=io.BytesIO(payload),mode='r:gz') as archive:
            for member in archive:
                local=member.name.removeprefix('package/')
                assert '..' not in pathlib.PurePosixPath(local).parts and not local.startswith('/')
                assert member.isfile() or member.isdir()
                assert not local.endswith(('.node','.so','.dll','.exe','.a')),'Refuse native bytes in JS hydration'
                data=archive.extractfile(member).read() if member.isfile() else None
                if local=='package.json':
                    manifest=json.loads(data);assert manifest['name']==name and manifest['version']==record['version'];packages[path]=manifest
                member.name=path+'/'+local;members.append((member,data))
        assert path in packages;added[path]=members;return path
    seen=set()
    def visit(name,parent=prefix.rstrip('/'),optional=False):
        try:path=locate(name,parent)
        except (AssertionError,KeyError):
            if optional:return
            raise
        if path in seen:return
        seen.add(path);manifest=packages[path]
        for child in {**manifest.get('dependencies',{}),**manifest.get('peerDependencies',{})}:
            visit(child,path,manifest.get('peerDependenciesMeta',{}).get(child,{}).get('optional',False))
    for seed in json.loads((root/'rootfs/runtime-seeds.json').read_text()):visit(seed)
    print(abi,'locked pure-JS packages restored',list(added),flush=True)
    temporary=cache/(abi+'-meter-hydrated.tar')
    with tarfile.open(source,'r:*') as archive,tarfile.open(temporary,'w') as output:
        for member in archive:output.addfile(member,archive.extractfile(member) if member.isfile() else None)
        for members in added.values():
            for member,data in members:output.addfile(member,io.BytesIO(data) if data is not None else None)
    pack(temporary,abi,source,cache/(abi+'.tgz'),refresh=True);temporary.unlink()
assets=root/'app/src/main/assets/kernel'
(assets/'manifest.json').write_text(json.dumps({abi:hashlib.sha256((assets/(abi+'.txz')).read_bytes()).hexdigest() for abi in ['arm64-v8a','x86_64']},indent=2)+'\n',encoding='utf-8')

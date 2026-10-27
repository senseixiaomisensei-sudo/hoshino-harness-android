"""Select the M1 dependency closure and stream identical tar content into gzip and xz.
Only deployment files are selected; no upstream implementation is edited.
"""
import base64,gzip,hashlib,io,json,lzma,pathlib,posixpath,tarfile,time,subprocess
ROOT=pathlib.Path(__file__).resolve().parents[1]
PREFIX='opt/hoshino/node-host/'
DENIED=set() # M1 enables native dsh attachments and therefore restores Sharp/libvips.

def pack(source, abi, target, baseline, refresh=False, add_dependencies=False):
    started=time.monotonic();packages={}
    with tarfile.open(source,'r:*') as archive:
        for item in archive:
            name=item.name.removeprefix('./')
            if name.startswith(PREFIX+'node_modules/') and name.endswith('/package.json') and item.isfile():
                try:packages[posixpath.dirname(name)]=json.load(archive.extractfile(item))
                except (ValueError,UnicodeError):pass
    additions={}
    if add_dependencies:
        host=ROOT/'node-host/node_modules'
        for manifest in host.rglob('package.json'):
            directory=manifest.parent
            try:data=json.loads(manifest.read_text(encoding='utf-8'))
            except (ValueError,UnicodeError):continue
            # Package roots only, not implementation fixture package.json files.
            rel=directory.relative_to(host).as_posix()
            if data.get('name') is None:continue
            name=PREFIX+'node_modules/'+rel
            if name not in packages:packages[name]=data;additions[name]=directory
    def locate(name,parent=PREFIX.rstrip('/')):
        current=parent
        while True:
            candidate=posixpath.join(current,'node_modules',name)
            if candidate in packages:return candidate
            ancestor=posixpath.dirname(current)
            if ancestor==current:raise RuntimeError('Missing required deployment dependency: '+name)
            current=ancestor
    selected=set()
    def visit(name,parent=PREFIX.rstrip('/'),optional=False):
        if name in DENIED:return
        try:directory=locate(name,parent)
        except RuntimeError:
            if optional:return
            raise
        if directory in selected:return
        selected.add(directory);manifest=packages[directory]
        for child in {**manifest.get('dependencies',{}),**manifest.get('peerDependencies',{})}:
            visit(child,directory,manifest.get('peerDependenciesMeta',{}).get(child,{}).get('optional',False))
    for seed in json.loads((ROOT/'rootfs/runtime-seeds.json').read_text()):visit(seed)
    visit('@deepseek-ai/node-addon-system-linux-'+('arm64' if abi=='arm64-v8a' else 'x64'))
    for native in ['@img/sharp-linuxmusl-','@img/sharp-libvips-linuxmusl-']:
        visit(native+('arm64' if abi=='arm64-v8a' else 'x64'))
    selected.add(locate('@deepseek-ai/dsh'))
    def keep(name):
        if name.split('/')[0] in ['dev','proc','sys']:return False
        if any(name==p or name.startswith(p+'/') for p in ['usr/include','usr/share/doc','usr/share/man','usr/share/locale','usr/lib/node_modules/npm','root/.npm','build']):return False
        if name.startswith(PREFIX+'node_modules/'):
            rest=name[len(PREFIX+'node_modules/'):]
            chunks=rest.split('/');count=2 if chunks[0].startswith('@') else 1
            package=PREFIX+'node_modules/'+'/'.join(chunks[:count])
            # Resolve the deepest package when npm has nested dependencies.
            for index,part in enumerate(chunks):
                if part=='node_modules' and index+1<len(chunks):
                    end=index+3 if chunks[index+1].startswith('@') else index+2
                    package=PREFIX+'node_modules/'+'/'.join(chunks[:end])
            if package not in selected:
                return any(path.startswith(name.rstrip('/')+'/') for path in selected)
            local=name[len(package)+1:]
            # Names inside dist/lib are implementation paths (yaml/dist/doc is required code).
            if local.split('/')[0] in ['docs','doc','test','tests','__tests__','examples','example']:return False
            lower=posixpath.basename(name).lower()
            if lower.endswith(('.map','.d.ts','.h','.hpp')):return False
            if lower.endswith('.md') and not any(k in lower for k in ['license','copying','copyright','notice']):return False
            if '/bin/glibc/' in name or name.endswith('/landlock-run'):return False
        return True
    target.parent.mkdir(parents=True,exist_ok=True);baseline.parent.mkdir(parents=True,exist_ok=True)
    temporary=target.with_suffix('.packing');gztemporary=baseline.with_suffix('.packing')
    files=[];unpacked=0;present=set()
    # Earlier deployment archives stripped yaml/dist/doc. Repair from the exact npm-lock
    # tarball so --refresh-host can reuse the native build without copying Windows binaries.
    restored={}
    if refresh:
        locked=json.loads((ROOT/'node-host/package-lock.json').read_text(encoding='utf-8'))['packages']['node_modules/yaml']
        package=locate('yaml');assert packages[package]['version']==locked['version']
        cache=ROOT/'.research/runtime'/('yaml-'+locked['version']+'.tgz');cache.parent.mkdir(parents=True,exist_ok=True)
        if not cache.exists():subprocess.run(['curl','-fLsS','--retry','3',locked['resolved'],'-o',str(cache)],check=True)
        expected=locked['integrity'].removeprefix('sha512-')
        assert base64.b64encode(hashlib.sha512(cache.read_bytes()).digest()).decode()==expected,'YAML npm integrity mismatch'
        with tarfile.open(cache,'r:gz') as original:
            for item in original:
                local=item.name.removeprefix('package/')
                if item.isfile() and local.startswith('dist/doc/') and local.endswith('.js'):
                    restored[package+'/'+local]=original.extractfile(item).read()
    with tarfile.open(source,'r:*') as archive,gzip.open(gztemporary,'wb',compresslevel=6) as gz,lzma.open(temporary,'wb',preset=6) as xz:
        class Both:
            def write(self,data):gz.write(data);xz.write(data);return len(data)
        with tarfile.open(fileobj=Both(),mode='w|',format=tarfile.PAX_FORMAT) as output:
            for item in archive:
                item.name=item.name.removeprefix('./')
                if item.name=='opt/hoshino/runtime-selection.json':continue
                if not keep(item.name):continue
                if refresh and (item.name==PREFIX+'dist' or item.name.startswith(PREFIX+'dist/')):continue
                if item.name in restored:continue
                data=archive.extractfile(item) if item.isfile() or item.islnk() else None
                if item.islnk():item.type=tarfile.REGTYPE;item.linkname='';payload=data.read();item.size=len(payload);data=io.BytesIO(payload)
                output.addfile(item,data)
                if item.isfile():files.append({'path':item.name,'bytes':item.size});unpacked+=item.size
            if refresh:
                # Reuse the exact musl runtime/native binaries. Add only missing locked JS packages.
                # This avoids replacing Node just because an Alpine rolling mirror pruned its APK.
                for name,directory in sorted(additions.items()):
                    if name not in selected:continue
                    for path in sorted(directory.rglob('*')):
                        if not path.is_file() or path.is_symlink():continue
                        target_name=name+'/'+path.relative_to(directory).as_posix()
                        if not keep(target_name):continue
                        if path.suffix in ['.node','.so','.dll','.dylib'] or path.name=='binding.gyp':raise RuntimeError('New native dependency requires a target build: '+target_name)
                        size=path.stat().st_size;output.add(path,arcname=target_name,recursive=False);files.append({'path':target_name,'bytes':size});unpacked+=size
                for name,payload in restored.items():
                    item=tarfile.TarInfo(name);item.size=len(payload);item.mode=0o644;output.addfile(item,io.BytesIO(payload));files.append({'path':name,'bytes':len(payload)});unpacked+=len(payload)
                for path in sorted((ROOT/'node-host/dist').rglob('*')):
                    if path.is_file():
                        name=PREFIX+'dist/'+path.relative_to(ROOT/'node-host/dist').as_posix();size=path.stat().st_size
                        output.add(path,arcname=name,recursive=False);files.append({'path':name,'bytes':size});unpacked+=size
            manifest=json.dumps({'packages':sorted(selected),'imageProcessing':True,'upstreamSourcesModified':False}).encode()
            entry=tarfile.TarInfo('opt/hoshino/runtime-selection.json');entry.size=len(manifest);entry.mode=0o644;output.addfile(entry,io.BytesIO(manifest))
            unpacked+=len(manifest)
    temporary.replace(target);gztemporary.replace(baseline)
    report={'abi':abi,'unpackedBytes':unpacked,'xzBytes':target.stat().st_size,'gzipBytes':baseline.stat().st_size,'selectedPackages':len(selected),'largestFiles':sorted(files,key=lambda x:x['bytes'],reverse=True)[:10],'packSeconds':round(time.monotonic()-started,3),'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}
    (ROOT/'docs'/('m1-size-'+abi+'.json')).write_text(json.dumps(report,indent=2)+'\n')
    print(json.dumps(report))
    return report

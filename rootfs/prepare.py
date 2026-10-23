"""Prepare APK assets from official Alpine + independently executed Termux proot.
Run --lock once to pin the signed-repository package file hashes; subsequent runs verify them.
No runtime downloads, npm execution, or package installation happens on the phone.
"""
import argparse, gzip, hashlib, io, json, pathlib, subprocess, tarfile

ROOT = pathlib.Path(__file__).resolve().parents[1]
CACHE = ROOT / '.research' / 'runtime'
REPO = 'https://packages.termux.dev/apt/termux-main/'
ABIS = {'arm64-v8a': ('aarch64', 'arm64', 'arm64'), 'x86_64': ('x86_64', 'amd64', 'x64')}

def download(url, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(['curl', '-fLsS', '--retry', '3', '--retry-all-errors', '-C', '-', url, '-o', str(target)], check=True)
    return target.read_bytes()

def deb_payload(data):
    assert data[:8] == b'!<arch>\n'
    pos = 8
    while pos + 60 <= len(data):
        header = data[pos:pos+60]; size = int(header[48:58]); pos += 60
        name = header[:16].decode().strip().rstrip('/')
        if name.startswith('data.tar'): return data[pos:pos+size]
        pos += size + size % 2
    raise ValueError('Missing deb payload')

def termux(abi, locks, update):
    arch = ABIS[abi][0]
    if update:
        target = CACHE / f'{arch}-Packages.gz'
        # Index changes between releases; never resume an old index while updating a lock.
        if target.exists(): target.unlink()
        index = gzip.decompress(download(REPO+f'dists/stable/main/binary-{arch}/Packages.gz', target)).decode()
        packages = {}
        for block in index.split('\n\n'):
            fields = dict(l.split(': ',1) for l in block.splitlines() if ': ' in l and not l.startswith(' '))
            if fields.get('Package') in ['proot','libtalloc','libandroid-shmem'] and fields['Package'] not in packages:
                packages[fields['Package']] = {k:fields[k] for k in ['Version','Filename','SHA256']}
        if len(packages) != 3: raise ValueError('Missing proot dependencies')
        locks[abi] = packages
        (ROOT/'rootfs/termux-lock.json').write_text(json.dumps(locks,indent=2)+'\n')
    files = {}
    for name, p in locks[abi].items():
        data = download(REPO+p['Filename'],CACHE/pathlib.Path(p['Filename']).name)
        assert hashlib.sha256(data).hexdigest() == p['SHA256'], 'Termux package checksum mismatch'
        with tarfile.open(fileobj=io.BytesIO(deb_payload(data))) as tar:
            for entry in tar:
                if entry.isfile(): files[entry.name.removeprefix('./')] = tar.extractfile(entry).read()
    dest = ROOT/'app/src/main/jniLibs'/abi; dest.mkdir(parents=True,exist_ok=True)
    wanted = {'/bin/proot':'libproot.so','/libexec/proot/loader':'libproot-loader.so','/lib/libtalloc.so':'libtalloc.so','/lib/libandroid-shmem.so':'libandroid-shmem.so'}
    for suffix, name in wanted.items():
        hits = [v for k,v in files.items() if k.endswith(suffix) or (name == 'libtalloc.so' and '/lib/libtalloc.so.' in k)]
        if len(hits) != 1: raise ValueError(f'Missing {suffix}: {list(files)}')
        (dest/name).write_bytes(hits[0])
    notices = ROOT/'app/src/main/assets/licenses'/abi
    notices.mkdir(parents=True,exist_ok=True)
    (notices/'proot-COPYING').write_bytes((ROOT/'rootfs/proot-COPYING').read_bytes())
    for path, data in files.items():
        if path.endswith('/copyright'):
            (notices/(path.split('/')[-2]+'.txt')).write_bytes(data)
    # APK native libraries must end in .so. Adjust only the standalone proot's dynamic-library name.
    subprocess.run(['docker','run','--rm','-v',str(dest)+':/out','alpine:3.23','sh','-c',
        'apk add --no-cache patchelf binutils >/dev/null && patchelf --replace-needed libtalloc.so.2 libtalloc.so /out/libproot.so && readelf -d /out/libproot.so | grep NEEDED'],check=True)

def rootfs(abi):
    _, platform, node_arch = ABIS[abi]
    tag = 'hoshino-m0-'+platform
    subprocess.run(['docker','build','--platform','linux/'+platform,'--build-arg','NODE_ARCH='+node_arch,'-f','rootfs/Dockerfile','-t',tag,'.'],cwd=ROOT,check=True)
    cid = subprocess.check_output(['docker','create','--platform','linux/'+platform,tag]).decode().strip()
    target = ROOT/'app/src/main/assets/kernel'/f'{abi}.tar.gz';target.parent.mkdir(parents=True,exist_ok=True)
    exported = CACHE/f'{abi}-export.tar'
    try:
        subprocess.run(['docker','export','-o',str(exported),cid],check=True)
        with tarfile.open(exported) as src, tarfile.open(target,'w:gz',dereference=True) as dst:
            for entry in src:
                if entry.name.split('/')[0] in ['dev','proc','sys']: continue
                if entry.islnk():
                    data = src.extractfile(entry);entry.type=tarfile.REGTYPE;entry.linkname='';entry.size=len(data.read());data.seek(0);dst.addfile(entry,data)
                elif entry.isfile(): dst.addfile(entry,src.extractfile(entry))
                else: dst.addfile(entry)
        print(json.dumps({'abi':abi,'compressedBytes':target.stat().st_size,'sha256':hashlib.sha256(target.read_bytes()).hexdigest()}))
    finally: subprocess.run(['docker','rm',cid],check=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lock',action='store_true');parser.add_argument('--abi',choices=ABIS,action='append');parser.add_argument('--proot-only',action='store_true');args=parser.parse_args()
    CACHE.mkdir(parents=True,exist_ok=True)
    lock_file=ROOT/'rootfs/termux-lock.json';locks=json.loads(lock_file.read_text()) if lock_file.exists() else {}
    for abi in args.abi or ABIS:
        termux(abi,locks,args.lock)
        if args.lock: lock_file.write_text(json.dumps(locks,indent=2)+'\n')
        if not args.proot_only: rootfs(abi)

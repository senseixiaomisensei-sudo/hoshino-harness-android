"""Prepare APK assets from official Alpine + independently executed Termux proot.
Run --lock once to pin the signed-repository package file hashes; subsequent runs verify them.
No runtime downloads, npm execution, or package installation happens on the phone.
"""
import argparse, gzip, hashlib, io, json, pathlib, subprocess, sys, tarfile
from pack import pack

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
    # Both SONAMEs fit the existing ELF string-table slot; preserve every offset.
    # The locked package has exactly one matching DT_NEEDED string. Fail on drift.
    executable = dest/'libproot.so'
    payload = executable.read_bytes()
    old = b'libtalloc.so.2\0'; new = b'libtalloc.so\0\0\0'
    assert payload.count(old) == 1 and len(old) == len(new), 'Unexpected proot ELF string table'
    executable.write_bytes(payload.replace(old,new))

def rootfs(abi):
    _, platform, node_arch = ABIS[abi]
    tag = 'hoshino-m0-'+platform
    subprocess.run(['docker','build','--platform','linux/'+platform,'--build-arg','NODE_ARCH='+node_arch,'-f','rootfs/Dockerfile','-t',tag,'.'],cwd=ROOT,check=True)
    cid = subprocess.check_output(['docker','create','--platform','linux/'+platform,tag]).decode().strip()
    target = ROOT/'app/src/main/assets/kernel'/f'{abi}.txz';target.parent.mkdir(parents=True,exist_ok=True)
    exported = CACHE/f'{abi}-export.tar'
    try:
        subprocess.run(['docker','export','-o',str(exported),cid],check=True)
        pack(exported,abi,target,CACHE/f'{abi}.tgz')
    finally: subprocess.run(['docker','rm',cid],check=True)

def refresh_host(abi):
    """Replace only our compiled host/plugins; keep locked upstream dependencies."""
    target=ROOT/'app/src/main/assets/kernel'/f'{abi}.txz'
    pack(target,abi,target,CACHE/f'{abi}.tgz',refresh=True)

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--lock',action='store_true');parser.add_argument('--abi',choices=ABIS,action='append');parser.add_argument('--proot-only',action='store_true');parser.add_argument('--rootfs-only',action='store_true');parser.add_argument('--refresh-host',action='store_true');parser.add_argument('--repack-local',action='store_true');args=parser.parse_args()
    if args.refresh_host and (args.lock or args.proot_only or args.rootfs_only): parser.error('--refresh-host cannot update runtime dependencies')
    if args.rootfs_only and (args.lock or args.proot_only): parser.error('--rootfs-only cannot update proot')
    CACHE.mkdir(parents=True,exist_ok=True)
    lock_file=ROOT/'rootfs/termux-lock.json';locks=json.loads(lock_file.read_text()) if lock_file.exists() else {}
    for abi in args.abi or ABIS:
        if args.repack_local: pack(ROOT/'app/src/main/assets/kernel'/f'{abi}.tgz',abi,ROOT/'app/src/main/assets/kernel'/f'{abi}.txz',CACHE/f'{abi}.tgz',refresh=True)
        elif args.refresh_host: refresh_host(abi)
        else:
            if args.lock: termux(abi,locks,True)
            if not args.rootfs_only: subprocess.run([sys.executable,str(ROOT/'rootfs/build-proot.py'),'--abi',abi],check=True)
            if not args.proot_only: rootfs(abi)
    if not args.proot_only:
        assets=ROOT/'app/src/main/assets/kernel'
        manifest={abi:hashlib.sha256((assets/f'{abi}.txz').read_bytes()).hexdigest() for abi in ABIS if (assets/f'{abi}.txz').exists()}
        (assets/'manifest.json').write_text(json.dumps(manifest,indent=2)+'\n')

"""Reproducible independent GPL proot build; does not touch dsh sources."""
import argparse, hashlib, io, json, os, pathlib, shutil, subprocess, sys, tarfile
from prepare import ROOT, CACHE, ABIS, REPO, download, deb_payload, termux

def build(abi, diagnostics=False):
    lock=json.loads((ROOT/'rootfs/proot-source-lock.json').read_text())
    packages=json.loads((ROOT/'rootfs/termux-lock.json').read_text())
    ndk=pathlib.Path(os.environ.get('ANDROID_HOME','/usr/local/lib/android/sdk'))/'ndk'/lock['ndk']
    toolchain=ndk/'toolchains/llvm/prebuilt/linux-x86_64/bin'
    if not toolchain.is_dir(): raise RuntimeError('Install the locked Android NDK before building independent proot')
    termux(abi,packages,False,include_proot=False)
    source=CACHE/'proot-source.tar.gz';data=download(lock['url'],source)
    assert hashlib.sha256(data).hexdigest()==lock['sha256']
    work=CACHE/('proot-build-'+abi);work.mkdir(parents=True,exist_ok=True)
    with tarfile.open(fileobj=io.BytesIO(data)) as archive: archive.extractall(work,filter='data')
    src=work/('proot-'+lock['revision'])/'src'
    include=work/'include';include.mkdir(exist_ok=True)
    lib=work/'lib';lib.mkdir(exist_ok=True)
    for package in ['libtalloc','libandroid-shmem']:
        p=packages[abi][package];payload=download(REPO+p['Filename'],CACHE/pathlib.Path(p['Filename']).name)
        assert hashlib.sha256(payload).hexdigest()==p['SHA256']
        with tarfile.open(fileobj=io.BytesIO(deb_payload(payload))) as archive:
            for item in archive:
                if not item.isfile(): continue
                if '/usr/include/' in item.name: target=include/item.name.split('/usr/include/',1)[1]
                elif '/usr/lib/lib' in item.name and '.so' in item.name: target=lib/pathlib.Path(item.name).name
                else: continue
                target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(archive.extractfile(item).read())
    talloc=next(lib.glob('libtalloc.so.*'));shutil.copyfile(talloc,lib/'libtalloc.so')
    family='aarch64' if abi=='arm64-v8a' else 'x86_64'
    compiler=work/'cc.py'
    compiler.write_text('#!/usr/bin/env python3\nimport subprocess,sys\nargs=sys.argv[1:]\n'+
        f'compiler={str(toolchain/(family+"-linux-android29-clang"))!r}\n'+
        f'if "-m32" in args: args.remove("-m32");compiler={str(toolchain/("armv7a-linux-androideabi29-clang" if family=="aarch64" else "i686-linux-android29-clang"))!r}\n'+
        'if any(a.endswith("ashmem_memfd.c") for a in args): args=["-include","string.h"]+args\n'+
        'sys.exit(subprocess.call([compiler]+args))\n')
    compiler.chmod(0o755)
    env=os.environ.copy();env.update(CC=str(compiler),GIT='true',STRIP=str(toolchain/'llvm-strip'),
        CPPFLAGS=f'-I{include} -DARG_MAX=131072 -DVERSION=\\"{lock["revision"]}\\"',
        CFLAGS='-g -O2 -fno-omit-frame-pointer',LDFLAGS=f'-L{lib} -ldl',
        PROOT_UNBUNDLE_LOADER='/unused',PROOT_WITH_LIBANDROID_SHMEM='true')
    if diagnostics:
        event=src/'tracee/event.c';text=event.read_text()
        needle='\tkill_all_tracees();\n\n\t/* Exit immediately'
        assert text.count(needle)==1
        text='#include "hs-diagnostics.h"\n'+text.replace(needle,'\ths_backtrace();\n'+needle)
        event.write_text(text)
        (src/'hs-diagnostics.h').write_bytes((ROOT/'rootfs/proot-diagnostics.h').read_bytes())
    subprocess.run(['make','-C',str(src),'-j2'],env=env,check=True)
    target=ROOT/'app/src/main/jniLibs'/abi
    shutil.copyfile(src/'proot',target/'libproot.so')
    shutil.copyfile(src/'loader/loader',target/'libproot-loader.so')
    executable=target/'libproot.so';payload=executable.read_bytes();old=b'libtalloc.so.2\0';new=b'libtalloc.so\0\0\0'
    assert payload.count(old)==1;executable.write_bytes(payload.replace(old,new))
    print(json.dumps({'abi':abi,'prootRevision':lock['revision'],'diagnostics':diagnostics,'bytes':executable.stat().st_size}))

if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--abi',choices=ABIS,action='append');parser.add_argument('--diagnostics',action='store_true');args=parser.parse_args()
    CACHE.mkdir(parents=True,exist_ok=True)
    for abi in args.abi or ABIS: build(abi,args.diagnostics)

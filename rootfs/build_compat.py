"""Build only Hoshino's musl syscall adapter; preserve the cached original runtime."""
import pathlib,subprocess

ROOT=pathlib.Path(__file__).resolve().parents[1]
def build(abi):
    platform='linux/'+('arm64' if abi=='arm64-v8a' else 'amd64')
    tag='hoshino-dsh-compat-'+abi
    subprocess.run(['docker','build','--platform',platform,'--target','compatibility','-f','rootfs/Dockerfile','-t',tag,'.'],cwd=ROOT,check=True)
    cid=subprocess.check_output(['docker','create','--platform',platform,tag],cwd=ROOT).decode().strip()
    target=ROOT/'.research/compat'/abi/'libandroid-compat.so';target.parent.mkdir(parents=True,exist_ok=True)
    try:subprocess.run(['docker','cp',cid+':/build/libandroid-compat.so',str(target)],check=True)
    finally:subprocess.run(['docker','rm',cid],check=True)
    return target.read_bytes()

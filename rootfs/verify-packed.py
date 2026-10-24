"""Import every real boot module after packaging; catch omitted runtime resources."""
import argparse,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],action='append');args=parser.parse_args()
for abi in args.abi or ['arm64-v8a','x86_64']:
    platform='arm64' if abi=='arm64-v8a' else 'amd64'
    asset=root/f'app/src/main/assets/kernel/{abi}.txz';assert asset.is_file()
    subprocess.run(['docker','run','--rm','--platform','linux/'+platform,'-v',str(asset.resolve())+':/runtime.txz:ro',
        'alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63',
        '/bin/sh','-ec','mkdir /verify; tar -xJf /runtime.txz -C /verify; chroot /verify /usr/bin/node /opt/hoshino/node-host/dist/node-host/src/verify-runtime.js'],check=True)

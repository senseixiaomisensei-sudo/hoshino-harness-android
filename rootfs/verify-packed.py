"""Import every real boot module after packaging; catch omitted runtime resources."""
import argparse,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
parser=argparse.ArgumentParser();parser.add_argument('--abi',choices=['arm64-v8a','x86_64'],action='append');args=parser.parse_args()
for abi in args.abi or ['arm64-v8a','x86_64']:
    asset=root/f'app/src/main/assets/kernel/{abi}.txz';assert asset.is_file()
    # Keep the helper on the CI host architecture; registered binfmt handles the
    # target Node inside chroot. Pulling one manifest-list digest for two helper
    # architectures fails with Docker's classic image store (cannot overwrite digest).
    subprocess.run(['docker','run','--rm','--platform','linux/amd64','-v',str(asset.resolve())+':/runtime.txz:ro',
        'alpine:3.23@sha256:ff7f0c727dd796aa36787bfcd9a64bf35a6ce72a73451b3359fde849b6703d63',
        '/bin/sh','-ec','mkdir /verify; tar -xJf /runtime.txz -C /verify; chroot /verify /usr/bin/node /opt/hoshino/node-host/dist/node-host/src/verify-runtime.js'],check=True)

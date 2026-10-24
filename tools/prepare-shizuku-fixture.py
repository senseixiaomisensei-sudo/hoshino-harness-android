"""Fetch the original open-source Shizuku manager only for emulator testing."""
import hashlib,json,pathlib,subprocess
root=pathlib.Path(__file__).resolve().parents[1]
lock=json.loads((root/'tools/test-fixtures/shizuku-manager.json').read_text(encoding='utf-8'))
target=root/'.research/shizuku-manager/manager.apk'
target.parent.mkdir(parents=True,exist_ok=True)
if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=lock['sha256']:
    subprocess.run(['curl','-fLsS','--retry','3','--max-time','120',lock['url'],'-o',str(target)],check=True)
assert hashlib.sha256(target.read_bytes()).hexdigest()==lock['sha256'],'Shizuku fixture checksum mismatch'
print('Verified official Shizuku manager',lock['version'],lock['sha256'])

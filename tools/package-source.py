"""Bundle only tracked public Harness sources; never bundle keys or local environments."""
import subprocess,pathlib,zipfile
root=pathlib.Path(__file__).resolve().parents[1]
paths=subprocess.check_output(['git','ls-files','-z'],cwd=root).decode().split('\0')
target=root/'app/src/main/assets/harness-source.zip';target.parent.mkdir(parents=True,exist_ok=True)
with zipfile.ZipFile(target,'w',zipfile.ZIP_DEFLATED) as archive:
 for name in paths:
  if not name or name.startswith(('.research/','docs/m0-memory/','docs/verification/','.codex-attachments/')):continue
  p=root/name
  if not p.is_file() or p.is_symlink() or p.stat().st_size>1024*1024:continue
  if name.endswith(('.apk','.keystore','.jks','.txz','.tgz')) or p.name=='local.properties':continue
  archive.write(p,name)
print(f'Harness public source ZIP: {target.stat().st_size} bytes')

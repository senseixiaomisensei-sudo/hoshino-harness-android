"""Check the shell adapter's atomic create and no-clobber semantics."""
import ctypes,errno,pathlib,subprocess,tempfile
root=pathlib.Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    base=pathlib.Path(directory);library=base/'compat.so'
    subprocess.run(['cc','-shared','-fPIC','-O2','-Wall','-Wextra','-Werror','-o',str(library),str(root/'rootfs/android-compat.c')],check=True)
    link=ctypes.CDLL(str(library),use_errno=True).link;link.argtypes=[ctypes.c_char_p,ctypes.c_char_p];link.restype=ctypes.c_int
    def call(source,target):return link(bytes(source),bytes(target))
    stage=base/'.hello.txt.123.905a17a6-01a1-4ee6-a710-bca924844fdd.tmpdir';stage.mkdir();source=stage/'hello.txt.tmp';target=base/'hello.txt'
    source.write_text('kernel');assert call(source,target)==0;assert not source.exists();stage.rmdir();assert target.read_text()=='kernel'
    stage.mkdir();source.write_text('replacement');assert call(source,target)==-1 and ctypes.get_errno()==errno.EEXIST;assert source.read_text()=='replacement' and target.read_text()=='kernel'
    ordinary=base/'ordinary';ordinary.write_text('normal');assert call(ordinary,base/'linked')==0 and ordinary.exists()
print('DSH_STAGED_CREATE_ATOMIC_NO_CLOBBER_OK')

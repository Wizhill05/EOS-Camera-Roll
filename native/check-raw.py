"""Exercise the same full-resolution LibRaw processing settings on a real R50 V CR3."""
from pathlib import Path
import subprocess,concurrent.futures,re,urllib.request,hashlib
root=Path(__file__).resolve().parent
build=root.parent/'build/raw-check';build.mkdir(parents=True,exist_ok=True)
sample=build/'R50V-sample.CR3';url='https://raw.pixls.us/getfile.php/8403/nice/Canon%20-%20EOS%20R50%20V%20-%20RAW%20%283%3A2%29.CR3'
if not sample.exists():urllib.request.urlretrieve(url,sample)
assert hashlib.sha256(sample.read_bytes()).hexdigest()=='955ff98710505498ce7f98cbcc5875793ef2d6941b3b5692ed3baf2817ee7c9f'
source=root/'LibRaw';manifest=(source/'Makefile.am').read_text().split('lib_libraw_a_SOURCES =',1)[1].split('\n\n',1)[0]
files=[source/name for name in re.findall(r'src/[\w/]+\.cpp',manifest)]+[root/'check-raw.cpp']
flags=['-O2','-std=c++11','-DUSE_ZLIB','-I'+str(source)]
def compile(path):
 obj=build/(str(path.relative_to(root)).replace('/','_')+'.o')
 if not obj.exists() or obj.stat().st_mtime<path.stat().st_mtime:subprocess.run(['g++',*flags,'-c',str(path),'-o',str(obj)],check=True)
 return obj
with concurrent.futures.ThreadPoolExecutor(max_workers=2) as pool:objects=list(pool.map(compile,files))
exe=build/'check-raw';subprocess.run(['g++',*map(str,objects),'-lz','-o',str(exe)],check=True)
subprocess.run([str(exe),str(sample)],check=True)

#!/usr/bin/env python3
"""Package source only; deliberately exclude all local data and generated artifacts."""
from pathlib import Path
import subprocess,sys,zipfile,hashlib
root=Path(__file__).resolve().parents[1]
subprocess.run([sys.executable,str(root/'tools/check-public.py')],check=True)
out=root/'outputs';out.mkdir(exist_ok=True)
archive=out/'求职助手-public-source.zip'
exclude={'build','.gradle','.kotlin','node_modules','__pycache__','.git','outputs','runtime','build-tools','local-data'}
private={'.apk','.aab','.zip','.pdf','.docx','.jks','.keystore','.p12','.pem','.key','.sqlite','.db','.pyc'}
with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
 for p in sorted(root.rglob('*')):
  rel=p.relative_to(root)
  if not p.is_file() or p.is_symlink() or set(rel.parts)&exclude or p.name in {'local.properties','.DS_Store'} or p.name.startswith('.env') or p.suffix.lower() in private:continue
  z.write(p,'job-assistant/'+str(rel))
with zipfile.ZipFile(archive) as z:
 assert z.testzip() is None
 assert 'job-assistant/README.md' in z.namelist()
 assert all(not(set(Path(name).parts)&exclude) for name in z.namelist())
print(archive.name,archive.stat().st_size,'bytes',hashlib.sha256(archive.read_bytes()).hexdigest())

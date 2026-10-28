#!/usr/bin/env python3
"""Validate public distribution assets and catch accidental private files."""
from pathlib import Path
import json,re
root=Path(__file__).resolve().parents[1]
skip={'build','.gradle','.kotlin','node_modules','__pycache__','.git'}
assets=root/'android/app/src/main/assets'
s=json.loads((assets/'initial-snapshot.json').read_text())
for key in ('companies','jobs','tasks','resumes'):assert s[key]==[],key
p=s['profile']
for key in ('name','email','phone','education','experience','skills','preferences','photo'):assert not p[key],key
assert p['factSections']==[]
assert json.loads((assets/'personal-facts.json').read_text())['sections']==[]
assert json.loads((root/'data/profile.json').read_text())==p
assert json.loads((root/'data/website-seed.json').read_text())['companies']==[]
assert json.loads((root/'data/recruitment-entries.json').read_text())==[]
for file in root.rglob('*'):
 if not file.is_file() or set(file.relative_to(root).parts)&skip:continue
 assert file.suffix.lower() not in {'.pdf','.docx','.jks','.keystore','.p12','.sqlite','.db','.pem','.key'},str(file.relative_to(root))
 if file.name=='local.properties':continue # Local build setup, excluded from publication.
 if file.suffix in {'.jar','.png','.webp'}:continue
 text=file.read_text()
 assert not re.search(r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|sk-[A-Za-z0-9_-]{24,}|eyJ[A-Za-z0-9_-]{30,}\.[A-Za-z0-9_-]+\.',text),str(file.relative_to(root))
 assert not [m.group(0) for m in re.finditer(r'(?<![A-Za-z0-9_.+-])[A-Za-z0-9_.+-]+@[A-Za-z0-9.-]+[.][A-Za-z]{2,}',text) if not (file.suffix=='.kt' and m.group(0).startswith(('this@','super@')))],str(file.relative_to(root))
assert 'android:label="求职助手"' in (root/'android/app/src/main/AndroidManifest.xml').read_text()
assert 'io.github.plutab.jobassistant' in (root/'android/app/build.gradle.kts').read_text()
print('PASS: empty companies/jobs/profile/resumes; no bundled credentials or private documents; independent application identity.')

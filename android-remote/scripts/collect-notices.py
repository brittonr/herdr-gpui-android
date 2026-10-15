#!/usr/bin/env python3
"""Collect exact cached dependency notices from Cargo metadata and normal/build trees.
Input directory: cargo-metadata-*.json and cargo-tree-*.txt from both Android targets.
No downloads, compilation, credentials, or application changes are performed.
"""
import argparse, hashlib, json, os, re
from pathlib import Path

p=argparse.ArgumentParser(description=__doc__)
p.add_argument('input',type=Path);p.add_argument('apache_license',type=Path);p.add_argument('output',type=Path)
a=p.parse_args(); selected=set(); packages={}
for path in a.input.glob('cargo-tree-*.txt'):
 for line in path.read_text().splitlines():
  match=re.match(r'(\S+) v([^ ]+)',line)
  if match:selected.add(match.groups())
for path in a.input.glob('cargo-metadata-*.json'):
 for package in json.loads(path.read_text())['packages']:
  if (package['name'],package['version']) in selected:packages[package['id']]=package
if not packages:raise SystemExit('No resolved packages found')
a.output.parent.mkdir(parents=True,exist_ok=True)
blocks=[b'''HERDR GPUI REMOTE: ACCOMPANYING DEPENDENCY NOTICES
These notices accompany the APK and source distribution. Existing Herdr,
GPUI Mobile and font licenses/notices remain included in the source and APK.
This collection covers the union of Android arm64/x86_64 normal/build dependency
trees; inclusion does not mean every package is linked into the final library.
Shipped license and notice contents below are retained without alteration.
Where Apache-2.0 is offered as an alternative, that option is selected and the
shared full license below applies. Additional notices are preserved separately.
Package metadata and explicit missing-text entries are an inventory, not a legal
certification. Upstream packages sometimes omit license files from their archives.

SHARED APACHE LICENSE 2.0 (the project's existing LICENSE)
''',a.apache_license.read_bytes(),b'\n']
rows=[]
for package in sorted(packages.values(),key=lambda x:(x['name'],x['version'])):
 root=Path(package['manifest_path']).parent;files=[]
 for directory,dirs,names in os.walk(root):
  dirs[:]=[d for d in dirs if d not in {'target','build','.git','.gradle','node_modules'} and not (Path(directory)/d).is_symlink()]
  for name in names:
   path=Path(directory)/name
   if path.is_symlink() or not any(name.upper().startswith(k) for k in ('LICENSE','LICENCE','COPYING','NOTICE','COPYRIGHT','AUTHORS')):continue
   if path.is_file() and path.stat().st_size<=2_000_000:files.append(path)
 explicit=package.get('license_file')
 if explicit:
  path=(root/explicit).resolve()
  if path.is_file() and path not in files:files.append(path)
 entry={k:package.get(k) for k in ('name','version','license','authors','repository','source')}
 entry['notice_files']=[]
 blocks.append(('\n\nPACKAGE '+package['name']+' '+package['version']+'\n'+json.dumps(entry,ensure_ascii=False,sort_keys=True)+'\n').encode())
 for path in sorted(files):
  data=path.read_bytes();relative=os.path.relpath(path,root)
  entry['notice_files'].append({'path':relative,'bytes':len(data),'sha256':hashlib.sha256(data).hexdigest()})
  blocks.extend([('\nFILE '+relative+'\n').encode(),data,b'\n'])
 if not files:
  apache='Apache-2.0' in (package.get('license') or '')
  entry['missing_text']='No shipped notice file found; shared Apache text applies' if apache else 'No shipped license text found; declared metadata retained for upstream reference'
  blocks.append((entry['missing_text']+'\n').encode())
 rows.append(entry)
a.output.write_bytes(b''.join(blocks))
inventory={'scope':'Android normal/build tree union; exact shipped notices plus shared Apache text','packages':rows,'count':len(rows),'output_sha256':hashlib.sha256(a.output.read_bytes()).hexdigest()}
a.output.with_suffix('.inventory.json').write_text(json.dumps(inventory,indent=2,ensure_ascii=False)+'\n')
print(json.dumps({'packages':len(rows),'notice_files':sum(len(x['notice_files']) for x in rows),'no_shipped_text':sum(not x['notice_files'] for x in rows),'bytes':a.output.stat().st_size,'sha256':inventory['output_sha256']}))

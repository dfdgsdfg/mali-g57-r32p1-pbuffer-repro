"""Download original public JPEGs at the pinned revision and verify SHA-256."""
from pathlib import Path
import hashlib, json, urllib.request
root=Path(__file__).resolve().parent
manifest=json.loads((root/'jpeg-assets.json').read_text())
dest=root/'app/src/main/assets/images'; dest.mkdir(parents=True,exist_ok=True)
for name,expected in manifest['files'].items():
    url='https://raw.githubusercontent.com/vishnunew/flutter-mali-gles-freeze-repro/'+manifest['revision']+'/assets/images/'+name
    with urllib.request.urlopen(url,timeout=60) as response: data=response.read()
    if hashlib.sha256(data).hexdigest()!=expected: raise RuntimeError('JPEG hash mismatch: '+name)
    (dest/name).write_bytes(data)
print('Verified',len(manifest['files']),'original JPEGs')

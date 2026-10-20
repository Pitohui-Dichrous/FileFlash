"""Create a source-only distribution; never includes local SDKs, keys or live credentials."""
from pathlib import Path
import hashlib
import re
import zipfile
from source_manifest import source_files

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
OUT = ROOT / 'dist' / f'FileFlash-{VERSION}-source.zip'
files = source_files(ROOT)
OUT.parent.mkdir(exist_ok=True)
with zipfile.ZipFile(OUT, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
    for file in sorted(files):
        archive.write(file, Path('FileFlash') / file.relative_to(ROOT))
with zipfile.ZipFile(OUT) as archive:
    assert archive.testzip() is None, 'Source ZIP CRC failure'
    assert len(archive.namelist()) == len(files)
print(str(OUT))
print('Files:', len(files))
print('SHA256:', hashlib.sha256(OUT.read_bytes()).hexdigest())

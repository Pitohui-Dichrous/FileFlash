"""Create a source-only distribution; never includes local SDKs, keys or live credentials."""
from pathlib import Path
import hashlib
import re
import zipfile

ROOT = Path(__file__).resolve().parent.parent
VERSION = re.search(r'versionName\s*=\s*"([^"]+)"', (ROOT / 'app/build.gradle.kts').read_text(encoding='utf-8')).group(1)
OUT = ROOT / 'dist' / f'FileFlash-{VERSION}-source.zip'
TOP_FILES = ['README.md', 'BUILD.cmd', '.gitignore', '.gitattributes', 'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'core/build.gradle.kts']
ROOTS = ['app/src', 'core/src', 'gradle', 'docs', 'scripts']
EXCLUDED = {'node_modules', '__pycache__', 'web-artifacts'}
files = {ROOT / name for name in TOP_FILES}
for folder in ROOTS:
    files.update(p for p in (ROOT / folder).rglob('*') if p.is_file() and not any(part in EXCLUDED for part in p.relative_to(ROOT).parts))
for file in files:
    rel = file.relative_to(ROOT)
    if any(part in {'.tools', '.signing', '.test-data', 'build'} for part in rel.parts) or file.suffix in {'.p12', '.jks', '.keystore', '.class'}:
        raise RuntimeError('Unexpected private/build file: ' + str(rel))
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

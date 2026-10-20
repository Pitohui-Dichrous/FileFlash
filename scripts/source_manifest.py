"""Shared source-package manifest; local tools, credentials and build output stay out."""
from pathlib import Path

TOP_FILES = ['README.md', 'BUILD.cmd', '.gitignore', '.gitattributes', 'settings.gradle.kts', 'build.gradle.kts', 'gradle.properties', 'gradlew', 'gradlew.bat', 'app/build.gradle.kts', 'core/build.gradle.kts']
ROOTS = ['app/src', 'core/src', 'gradle', 'docs', 'scripts']
EXCLUDED = {'node_modules', '__pycache__', 'web-artifacts'}


def source_files(root: Path):
    files = {root / name for name in TOP_FILES}
    for folder in ROOTS:
        files.update(p for p in (root / folder).rglob('*') if p.is_file() and not any(part in EXCLUDED for part in p.relative_to(root).parts))
    for file in files:
        relative = file.relative_to(root)
        if not file.resolve().is_relative_to(root.resolve()):
            raise RuntimeError('Source file resolves outside project: ' + str(relative))
        if (any(part in {'.tools', '.signing', '.test-data', 'build'} for part in relative.parts)
                or file.name == 'local.properties'
                or file.suffix.lower() in {'.p12', '.pem', '.key', '.jks', '.keystore', '.class', '.apk', '.aab'}):
            raise RuntimeError('Unexpected private/build file: ' + str(relative))
    return files

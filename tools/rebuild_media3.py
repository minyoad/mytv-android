#!/usr/bin/env python3
"""Build pinned candidate Java Media3 AARs into a separate review directory; never replace bundled binaries."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def run(args, cwd):
    return subprocess.check_output(args, cwd=cwd, text=True, stderr=subprocess.STDOUT).strip()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--source', type=Path, required=True, help='Clean checkout of minyoad/media at the pinned candidate revision')
    parser.add_argument('--output', type=Path, required=True, help='Empty directory for candidate AARs and comparison report')
    args = parser.parse_args()
    source = args.source.resolve()
    output = args.output.resolve()
    pin = json.loads((ROOT / 'player-sources.json').read_text())['media3_java']
    if run(['git', 'rev-parse', 'HEAD'], source) != pin['candidate_revision']:
        parser.error('Source HEAD does not match player-sources.json')
    if run(['git', 'status', '--porcelain', '--untracked-files=all'], source):
        parser.error('Source checkout must be clean')
    if output.exists() and any(output.iterdir()):
        parser.error('Output directory must be empty')
    tasks = [':lib-' + name + ':assembleRelease' for name in pin['modules']]
    # Invoke the pinned wrapper and the exact modules, avoiding the fork assembleLibs Windows move.bat hook.
    subprocess.run(['bash', './gradlew', '--no-daemon', *tasks], cwd=source, check=True)
    output.mkdir(parents=True, exist_ok=True)
    report = {'source_revision': pin['candidate_revision'], 'java_version': run(['java', '-version'], source),
              'android_home': os.environ.get('ANDROID_HOME'), 'artifacts': []}
    for name in pin['modules']:
        original = source / 'libraries' / name.replace('-', '_') / 'build/outputs/aar' / ('lib-' + name + '-release.aar')
        target = output / ('media3-' + name + '.aar')
        shutil.copyfile(original, target)
        with zipfile.ZipFile(target) as new, zipfile.ZipFile(ROOT / 'tv/libs' / target.name) as old:
            def contents(archive):
                return {name: hashlib.sha256(archive.read(name)).hexdigest() for name in archive.namelist() if not name.endswith('/')}
            old_files, new_files = contents(old), contents(new)
        report['artifacts'].append({'file': target.name, 'sha256': hashlib.sha256(target.read_bytes()).hexdigest(),
            'payload_equal_to_bundled': old_files == new_files,
            'changed_entries': sorted(key for key in old_files.keys() | new_files.keys() if old_files.get(key) != new_files.get(key))})
    (output / 'rebuild-report.json').write_text(json.dumps(report, indent=2) + '\n')
    print(output / 'rebuild-report.json')


if __name__ == '__main__':
    main()

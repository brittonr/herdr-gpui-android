#!/usr/bin/env python3
"""Choose the next free automatic preview release name for the source version.

Existing tag refs are read from stdin, so a run can claim the first candidate that
no other run has already created. Names stay outside the `android-v*` release
namespace used by hand-tagged, stably signed releases.
"""
import pathlib
import re
import sys

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'app/build.gradle'
REF_SAFE_VERSION = re.compile(r'[A-Za-z0-9._-]+')
NUMBERED = re.compile(r'android-preview-(?P<version>.+)\.(?P<number>\d+)$')
MAX_CANDIDATES = 50
USAGE = 'usage: next-preview-release.py [candidate-count]'


def version_name():
    versions = re.findall(r"\bversionName\s+'([^']+)'", SOURCE.read_text())
    if len(versions) != 1 or not REF_SAFE_VERSION.fullmatch(versions[0]):
        raise SystemExit('app/build.gradle must declare exactly one ref-safe versionName')
    return versions[0]


def candidates(existing, version, count):
    taken = set()
    for line in existing:
        name = line.strip()
        if name.startswith('refs/tags/'):
            name = name[len('refs/tags/'):]
        match = NUMBERED.fullmatch(name)
        if match and match.group('version') == version:
            taken.add(int(match.group('number')))
    first = max(taken, default=0) + 1
    return ['android-preview-{}.{}'.format(version, first + offset) for offset in range(count)]


if __name__ == '__main__':
    if len(sys.argv) > 2:
        raise SystemExit(USAGE)
    try:
        count = 1 if len(sys.argv) == 1 else int(sys.argv[1])
    except ValueError:
        raise SystemExit(USAGE)
    if not 1 <= count <= MAX_CANDIDATES:
        raise SystemExit('candidate count must be between 1 and {}'.format(MAX_CANDIDATES))
    print('\n'.join(candidates(sys.stdin, version_name(), count)))

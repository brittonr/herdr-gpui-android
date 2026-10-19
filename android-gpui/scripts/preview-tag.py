#!/usr/bin/env python3
"""Derive the automatic preview tag for one commit from the source version.

Preview tags live in their own namespace. `android-v*` tags stay the published
release path, and creating a preview tag never triggers it.
"""
import pathlib
import re
import sys

SOURCE = pathlib.Path(__file__).resolve().parents[1] / 'app/build.gradle'
COMMIT = re.compile(r'[0-9a-f]{40}')
REF_SAFE_VERSION = re.compile(r'[A-Za-z0-9._-]+')


def preview_tag(commit):
    if not COMMIT.fullmatch(commit):
        raise SystemExit('Pass the full 40-character lowercase commit SHA')
    versions = re.findall(r"\bversionName\s+'([^']+)'", SOURCE.read_text())
    if len(versions) != 1 or not REF_SAFE_VERSION.fullmatch(versions[0]):
        raise SystemExit('app/build.gradle must declare exactly one ref-safe versionName')
    return 'android-preview-{}-{}'.format(versions[0], commit[:12])


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('usage: preview-tag.py <40-character commit SHA>')
    print(preview_tag(sys.argv[1]))

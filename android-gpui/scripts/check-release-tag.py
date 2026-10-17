#!/usr/bin/env python3
"""Require release tags to match the version actually embedded by Gradle."""
import pathlib
import re
import sys

source = pathlib.Path(__file__).resolve().parents[1] / 'app/build.gradle'
versions = re.findall(r"\bversionName\s+'([^']+)'", source.read_text())
if len(versions) != 1 or sys.argv[1:] != ['android-v' + versions[0]]:
    raise SystemExit('Tag must equal android-v<versionName> from app/build.gradle')
print('Release tag matches Android versionName')

#!/usr/bin/env python3
"""Read the checked-in Gradle identity; never silently instrument the old APK."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
config = (root / 'app/build.gradle').read_text()
match = re.search(r"^\s*applicationId\s+['\"]([a-zA-Z0-9_.]+)['\"]\s*$", config, re.M)
if match is None:
    raise SystemExit('Cannot resolve literal applicationId from app/build.gradle')
target = match.group(1)
test = target + '.vmtests'
if len(sys.argv) == 1:
    print(target)
elif len(sys.argv) == 2:
    android = '{http://schemas.android.com/apk/res/android}'
    ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')
    tree = ET.parse(root / 'vm-tests/AndroidManifest.xml')
    tree.getroot().set('package', test)
    for runner in tree.getroot().findall('instrumentation'):
        runner.set(android + 'targetPackage', target)
    tree.write(sys.argv[1], encoding='utf-8', xml_declaration=True)
else:
    raise SystemExit('Usage: package-config.py [generated-manifest.xml]')

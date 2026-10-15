#!/usr/bin/env python3
"""Target-identity regression tests; no SDK, APK install or device access."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import xml.etree.ElementTree as ET

HERE = Path(__file__).resolve().parent
ANDROID = '{http://schemas.android.com/apk/res/android}'

class PackageConfigTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='herdr-vm-package-')
        self.root = Path(self.tmp.name)
        (self.root / 'app').mkdir()
        (self.root / 'vm-tests').mkdir()
        shutil.copyfile(HERE / 'package-config.py', self.root / 'vm-tests/package-config.py')
        shutil.copyfile(HERE / 'AndroidManifest.xml', self.root / 'vm-tests/AndroidManifest.xml')

    def tearDown(self):
        self.tmp.cleanup()

    def run_config(self, config, *args):
        (self.root / 'app/build.gradle').write_text(config)
        return subprocess.run(['python3', str(self.root / 'vm-tests/package-config.py'), *args],
                              text=True, capture_output=True)

    def test_literal_identity(self):
        result = self.run_config("  applicationId 'dev.herdr.android.gpui.compact'\n")
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'dev.herdr.android.gpui.compact')

    def test_new_identity_updates_both_manifest_fields(self):
        output = self.root / 'out.xml'
        result = self.run_config('applicationId "dev.example.future"\n', str(output))
        self.assertEqual(result.returncode, 0, result.stderr)
        manifest = ET.parse(output).getroot()
        self.assertEqual(manifest.attrib['package'], 'dev.example.future.vmtests')
        self.assertEqual(manifest.find('instrumentation').attrib[ANDROID + 'targetPackage'], 'dev.example.future')
        self.assertEqual(manifest.find('instrumentation').attrib[ANDROID + 'name'], 'dev.herdr.android.vm.GpuiVmInstrumentation')
        runners = manifest.findall('instrumentation')
        self.assertEqual(len(runners), 2)
        self.assertTrue(all(r.attrib[ANDROID + 'targetPackage'] == 'dev.example.future' for r in runners))
        self.assertEqual(runners[1].attrib[ANDROID + 'name'], 'dev.herdr.android.vm.RemoteVmInstrumentation')

    def test_no_silent_fallback_for_computed_id(self):
        result = self.run_config("applicationId System.getenv('APP_ID')\n")
        self.assertNotEqual(result.returncode, 0)
        self.assertIn('Cannot resolve literal applicationId', result.stderr)

    def test_comments_do_not_supply_identity(self):
        result = self.run_config("// applicationId 'dev.herdr.old'\n")
        self.assertNotEqual(result.returncode, 0)

    def test_missing_identity_fails(self):
        self.assertNotEqual(self.run_config('versionCode 3\n').returncode, 0)

if __name__ == '__main__':
    unittest.main()

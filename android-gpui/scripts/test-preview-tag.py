#!/usr/bin/env python3
"""Preview tag regressions; no GitHub, tag, release or device access."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
COMMIT = 'a1b2c3d4e5f6' + '0' * 28


class PreviewTagTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='herdr-preview-tag-')
        self.root = Path(self.tmp.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'app').mkdir()
        shutil.copyfile(HERE / 'preview-tag.py', self.root / 'scripts/preview-tag.py')

    def tearDown(self):
        self.tmp.cleanup()

    def run_tag(self, config, *args):
        (self.root / 'app/build.gradle').write_text(config)
        return subprocess.run(['python3', str(self.root / 'scripts/preview-tag.py'), *args],
                              text=True, capture_output=True)

    def test_tag_combines_source_version_and_abbreviated_commit(self):
        result = self.run_tag("        versionName '0.5.0-gpui-remote'\n", COMMIT)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'android-preview-0.5.0-gpui-remote-' + COMMIT[:12])

    def test_tag_stays_out_of_the_published_release_namespace(self):
        result = self.run_tag("        versionName '1.2.3'\n", COMMIT)
        self.assertTrue(result.stdout.strip().startswith('android-preview-'), result.stdout)
        self.assertFalse(result.stdout.strip().startswith('android-v'), result.stdout)

    def test_short_uppercase_or_missing_commit_is_rejected(self):
        for argument in (COMMIT[:12], COMMIT.upper(), '', 'main', COMMIT + 'a'):
            result = self.run_tag("        versionName '0.5.0'\n", argument)
            self.assertNotEqual(result.returncode, 0, argument)

    def test_version_that_cannot_name_a_ref_is_rejected(self):
        for version in ('', '1.0 0', '1.0/0', '1.0~0', '1.0^0', '1.0:0', '1.0*x'):
            result = self.run_tag("        versionName '{}'\n".format(version), COMMIT)
            self.assertNotEqual(result.returncode, 0, repr(version))

    def test_missing_or_ambiguous_version_is_rejected(self):
        self.assertNotEqual(self.run_tag('        versionCode 5\n', COMMIT).returncode, 0)
        self.assertNotEqual(self.run_tag("        versionName '1'\n        versionName '2'\n", COMMIT).returncode, 0)
        # The single-quoted Gradle form is the one convention, as in check-release-tag.py.
        self.assertNotEqual(self.run_tag('        versionName "1"\n', COMMIT).returncode, 0)

    def test_wrong_argument_count_is_rejected(self):
        self.assertNotEqual(self.run_tag("        versionName '1'\n").returncode, 0)
        self.assertNotEqual(self.run_tag("        versionName '1'\n", COMMIT, 'extra').returncode, 0)


if __name__ == '__main__':
    unittest.main()

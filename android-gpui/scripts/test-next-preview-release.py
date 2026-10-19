#!/usr/bin/env python3
"""Preview release numbering regressions; no GitHub, tag or release access."""
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

HERE = Path(__file__).resolve().parent
VERSION = '0.5.0-gpui-remote'


class NextPreviewReleaseTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory(prefix='herdr-preview-release-')
        self.root = Path(self.tmp.name)
        (self.root / 'scripts').mkdir()
        (self.root / 'app').mkdir()
        shutil.copyfile(HERE / 'next-preview-release.py', self.root / 'scripts/next-preview-release.py')

    def tearDown(self):
        self.tmp.cleanup()

    def choose(self, config, existing='', *args):
        (self.root / 'app/build.gradle').write_text(config)
        return subprocess.run(['python3', str(self.root / 'scripts/next-preview-release.py'), *args],
                              input=existing, text=True, capture_output=True)

    def test_first_release_of_a_version_starts_at_one(self):
        result = self.choose("        versionName '{}'\n".format(VERSION))
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertEqual(result.stdout.strip(), 'android-preview-{}.1'.format(VERSION))

    def test_numbering_continues_from_the_highest_existing_tag(self):
        existing = '\n'.join(['refs/tags/android-preview-0.5.0-gpui-remote.1',
                              'android-preview-0.5.0-gpui-remote.2',
                              'refs/tags/android-v0.5.0-gpui-remote',
                              'refs/tags/android-preview-0.4.0-gpui-remote.9',
                              'refs/tags/android-preview-0.5.0-gpui-remote-6304538fad2f',
                              '']) + '\n'
        result = self.choose("        versionName '{}'\n".format(VERSION), existing)
        self.assertEqual(result.stdout.strip(), 'android-preview-{}.3'.format(VERSION), result.stderr)

    def test_gaps_do_not_reuse_an_earlier_number(self):
        existing = 'refs/tags/android-preview-{}.1\nrefs/tags/android-preview-{}.5\n'.format(VERSION, VERSION)
        result = self.choose("        versionName '{}'\n".format(VERSION), existing)
        self.assertEqual(result.stdout.strip(), 'android-preview-{}.6'.format(VERSION))

    def test_other_versions_and_unrelated_tags_are_ignored(self):
        existing = '\n'.join(['refs/tags/android-preview-7.7.7.4', 'refs/tags/v1.2.3',
                              'refs/tags/android-preview.12', 'refs/tags/android-preview-smoke-verify1']) + '\n'
        result = self.choose("        versionName '{}'\n".format(VERSION), existing)
        self.assertEqual(result.stdout.strip(), 'android-preview-{}.1'.format(VERSION))

    def test_candidate_count_returns_a_sequence_to_claim_from(self):
        existing = 'refs/tags/android-preview-{}.2\n'.format(VERSION)
        result = self.choose("        versionName '{}'\n".format(VERSION), existing, '3')
        self.assertEqual(result.stdout.split(), ['android-preview-{}.{}'.format(VERSION, number) for number in (3, 4, 5)])

    def test_missing_ambiguous_or_unsafe_version_is_rejected(self):
        for config in ('        versionCode 5\n',
                       "        versionName '1'\n        versionName '2'\n",
                       '        versionName "1"\n',
                       "        versionName '1.0 0'\n",
                       "        versionName '1.0/0'\n"):
            self.assertNotEqual(self.choose(config).returncode, 0, config)

    def test_invalid_candidate_count_is_rejected(self):
        for args in (['0'], ['51'], ['x'], ['1', 'extra']):
            self.assertNotEqual(self.choose("        versionName '1'\n", '', *args).returncode, 0, args)


if __name__ == '__main__':
    unittest.main()

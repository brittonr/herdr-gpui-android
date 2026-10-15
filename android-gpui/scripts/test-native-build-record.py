#!/usr/bin/env python3
"""Regression coverage for architecture-specific native freshness records."""
import json
import pathlib
import subprocess
import sys
import tempfile
import unittest


SCRIPT = pathlib.Path(__file__).with_name('native-build-record.py')


class NativeBuildRecordTests(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = pathlib.Path(self.directory.name)
        for name in ['rust/src', 'rust/assets/fonts', 'scripts', 'app']:
            (self.root / name).mkdir(parents=True)
        for name in ['rust/Cargo.toml', 'rust/Cargo.lock', 'rust/src/lib.rs',
                     'scripts/build-native.sh', 'scripts/native-build-record.py']:
            (self.root / name).write_text('fixture input\n')
        (self.root / 'app/build.gradle').write_text('minSdk 29\n')
        (self.root / 'rust/assets/fonts/font.fixture').write_text('non-font asset hash fixture\n')
        (self.root / 'rust/assets/fonts/LICENSE.txt').write_text('fixture license\n')
        self.record = self.root / 'record.json'
        self.library = self.root / 'digest-fixture.txt'
        # Non-executable data tests hashing only, never an installable .so.
        self.library.write_text('non-executable digest fixture\n')

    def run_record(self, operation, abi=None, succeeds=True):
        args = [sys.executable, str(SCRIPT), operation, str(self.root), str(self.record)]
        if operation != 'snapshot':
            args.append(str(self.library))
        if operation == 'record':
            args.extend(['--min-sdk', '29'])
        if abi:
            args.extend(['--abi', abi])
        result = subprocess.run(args, text=True, capture_output=True)
        if succeeds:
            self.assertEqual(result.returncode, 0, result.stderr)
        else:
            self.assertNotEqual(result.returncode, 0)
        return result

    def test_default_is_arm64(self):
        self.run_record('snapshot')
        record = json.loads(self.record.read_text())
        self.assertEqual(record['abi'], 'arm64-v8a')
        self.assertEqual(record['rust_target'], 'aarch64-linux-android')
        self.run_record('record')
        self.run_record('verify')

    def test_explicit_x86_64(self):
        for operation in ['snapshot', 'record', 'verify']:
            self.run_record(operation, 'x86_64')
        self.assertEqual(json.loads(self.record.read_text())['rust_target'], 'x86_64-linux-android')

    def test_rejects_cross_architecture_record(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        result = self.run_record('verify', 'arm64-v8a', succeeds=False)
        self.assertIn('ABI', result.stderr)

    def test_rejects_changed_source(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'rust/src/lib.rs').write_text('new source\n')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_changed_path_dependency(self):
        dependency = self.root / '../android-remote/rust/src'
        dependency.mkdir(parents=True, exist_ok=True)
        source = dependency / 'fixture.rs'
        source.write_text('dependency before')
        self.addCleanup(source.unlink, missing_ok=True)
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        source.write_text('dependency after')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_changed_library(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        self.library.write_text('different non-executable data\n')
        result = self.run_record('verify', 'x86_64', succeeds=False)
        self.assertIn('does not match', result.stderr)

    def test_rejects_changed_bundled_asset(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'rust/assets/fonts/font.fixture').write_text('changed non-font asset\n')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_asset_changed_during_build(self):
        self.run_record('snapshot', 'x86_64')
        (self.root / 'rust/assets/fonts/font.fixture').write_text('changed before record\n')
        self.run_record('record', 'x86_64', succeeds=False)

    def test_rejects_changed_asset_license(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'rust/assets/fonts/LICENSE.txt').write_text('changed fixture license\n')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_new_asset(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'rust/assets/fonts/new.fixture').write_text('new asset\n')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_removed_asset(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'rust/assets/fonts/font.fixture').unlink()
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_changed_min_sdk(self):
        self.run_record('snapshot', 'x86_64')
        self.run_record('record', 'x86_64')
        (self.root / 'app/build.gradle').write_text('minSdk 30\n')
        self.run_record('verify', 'x86_64', succeeds=False)

    def test_rejects_unsupported_abi(self):
        self.run_record('snapshot', 'armeabi-v7a', succeeds=False)


if __name__ == '__main__':
    unittest.main()

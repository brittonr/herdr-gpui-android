#!/usr/bin/env python3
"""Non-installable ZIP fixtures test strict single-ABI native selection."""
import importlib.util
import pathlib
import struct
import sys
import tempfile
import unittest
import zipfile

sys.dont_write_bytecode = True


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, pathlib.Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


checker = module('apk_verifier', 'verify-apk-structure.py')
fixtures = module('native_fixtures', 'test-native-verifier.py')


class ApkAbiTests(unittest.TestCase):
    def assert_native_gate(self, libraries, error, abi='arm64-v8a'):
        with tempfile.TemporaryDirectory() as directory:
            path = pathlib.Path(directory) / 'non-installable.zip'
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_STORED) as archive:
                archive.writestr('AndroidManifest.xml', struct.pack('<H', 3))
                archive.writestr('resources.arsc', struct.pack('<H', 2))
                for name, content in libraries.items():
                    archive.writestr(name, content)
            with self.assertRaisesRegex(ValueError, error):
                checker.check_apk(path, abi)

    def split_pair(self, directory, duplicate_asset=False):
        paths = [pathlib.Path(directory) / 'base.apk', pathlib.Path(directory) / 'config.apk']
        for path in paths:
            with zipfile.ZipFile(path, 'w', zipfile.ZIP_STORED) as archive:
                archive.writestr('AndroidManifest.xml', struct.pack('<H', 3))
                archive.writestr('resources.arsc', struct.pack('<H', 2))
                if duplicate_asset or path.name == 'base.apk':
                    archive.writestr('assets/about/index.html', b'about')
        return paths

    def test_default_arm64_reaches_dex_checks(self):
        self.assert_native_gate({'lib/arm64-v8a/libherdr_gpui_android.so': fixtures.host_elf_fixture()},
                                'Missing DEX')

    def test_x86_64_reaches_dex_checks(self):
        self.assert_native_gate({'lib/x86_64/libherdr_gpui_android.so': fixtures.host_elf_fixture(machine=62)},
                                'Missing DEX', 'x86_64')

    def test_default_rejects_x86_64_only(self):
        self.assert_native_gate({'lib/x86_64/libherdr_gpui_android.so': fixtures.host_elf_fixture(machine=62)},
                                'arm64-v8a native library missing')

    def test_rejects_mixed_abi(self):
        self.assert_native_gate({
            'lib/arm64-v8a/libherdr_gpui_android.so': fixtures.host_elf_fixture(),
            'lib/x86_64/libherdr_gpui_android.so': fixtures.host_elf_fixture(machine=62),
        }, 'Unexpected JNI library or ABI')

    def test_rejects_mislabeled_native_library(self):
        self.assert_native_gate({'lib/x86_64/libherdr_gpui_android.so': fixtures.host_elf_fixture()},
                                'Expected x86_64', 'x86_64')

    def test_rejects_foreign_runtime(self):
        self.assert_native_gate({
            'lib/x86_64/libherdr_gpui_android.so': fixtures.host_elf_fixture(machine=62),
            'lib/x86_64/libc++_shared.so': fixtures.elf_fixture(),
        }, 'Expected x86_64', 'x86_64')

    def test_resources_table_may_appear_in_each_split(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = self.split_pair(directory)
            with self.assertRaisesRegex(ValueError, 'Actual GPUI arm64-v8a native library missing'):
                checker.check_apks(paths)

    def test_rejects_entry_owned_by_multiple_splits(self):
        with tempfile.TemporaryDirectory() as directory:
            paths = self.split_pair(directory, duplicate_asset=True)
            with self.assertRaisesRegex(ValueError, 'assets/about/index.html is owned by more than one APK split'):
                checker.check_apks(paths)


if __name__ == '__main__':
    unittest.main()

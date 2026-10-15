#!/usr/bin/env python3
"""In-memory parser fixtures, never installable libraries or APK payloads."""
import importlib.util
import pathlib
import struct
import sys
import unittest

sys.dont_write_bytecode = True

path = pathlib.Path(__file__).with_name('verify-native-library.py')
spec = importlib.util.spec_from_file_location('native_verifier', path)
checker = importlib.util.module_from_spec(spec)
spec.loader.exec_module(checker)


def elf_fixture(machine=183):
    data = bytearray(184)
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 0, 64, 120, 0, 64, 56, 1, 64, 1, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, len(data), len(data), 16384)
    return data


def host_elf_fixture(api=29, machine=183):
    """Structural data only: no machine code, never written as a library."""
    names = ['Java_dev_gpui_mobile_GpuiInputActivity_nativeIme'] + [
        'Java_dev_herdr_android_GpuiTerminalActivity_' + name for name in (
            'nativeStart', 'nativeSurfaceCreated', 'nativeSurfaceDestroyed',
            'nativeResumed', 'nativePaused', 'nativeTouch', 'nativeKey',
            'nativeSetSnapshot', 'nativeSetActive', 'nativeTakeCommand', 'nativeSendKey',
                'nativeSetImeSession', 'nativeImeEvent', 'nativeSendCommand', 'nativeSetDisplayPreferences',
        )
    ]
    names.extend('Java_dev_herdr_android_RemoteNative_' + name for name in
                 ('initialize', 'inspect', 'connect', 'poll', 'input', 'focus', 'close'))
    strings = bytearray(b'\0')
    symbols = bytearray(24)
    for name in names:
        offset = len(strings)
        strings.extend(name.encode() + b'\0')
        symbols.extend(struct.pack('<IBBHQQ', offset, 0x12, 0, 1, 0, 0))
    note = struct.pack('<III', 8, 4, 1) + b'Android\0' + struct.pack('<I', api)
    strings_offset = 120 + 4 * 64
    symbols_offset = strings_offset + len(strings)
    note_offset = symbols_offset + len(symbols)
    data = bytearray(note_offset + len(note))
    data[:7] = b'\x7fELF\x02\x01\x01'
    struct.pack_into('<HHIQQQIHHHHHH', data, 16, 3, machine, 1, 0, 64, 120, 0, 64, 56, 1, 64, 4, 0)
    struct.pack_into('<IIQQQQQQ', data, 64, 1, 5, 0, 0, 0, len(data), len(data), 16384)
    struct.pack_into('<IIQQQQIIQQ', data, 184, 0, 3, 0, 0, strings_offset, len(strings), 0, 0, 1, 0)
    struct.pack_into('<IIQQQQIIQQ', data, 248, 0, 11, 0, 0, symbols_offset, len(symbols), 1, 1, 8, 24)
    struct.pack_into('<IIQQQQIIQQ', data, 312, 0, 7, 0, 0, note_offset, len(note), 0, 0, 4, 0)
    data[strings_offset:symbols_offset] = strings
    data[symbols_offset:note_offset] = symbols
    data[note_offset:] = note
    return data


class VerifyNativeTests(unittest.TestCase):
    def test_full_host_export_and_api_structure(self):
        result = checker.verify_elf(host_elf_fixture(), expected_api=29)
        self.assertEqual(result['android_api'], 29)
        self.assertEqual(len(result['exports']), 23)

    def test_rejects_old_api(self):
        with self.assertRaisesRegex(ValueError, 'requires Android API 29'):
            checker.verify_elf(host_elf_fixture(28))

    def test_rejects_apk_api_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'does not match APK minSdk'):
            checker.verify_elf(host_elf_fixture(), expected_api=30)

    def test_runtime_structure(self):
        self.assertEqual(checker.verify_elf(elf_fixture(), runtime=True)['load_segments'], 1)

    def test_rejects_missing_jni(self):
        with self.assertRaisesRegex(ValueError, 'JNI bridge'):
            checker.verify_elf(elf_fixture())

    def test_rejects_4k_only(self):
        data = elf_fixture()
        struct.pack_into('<Q', data, 112, 4096)
        with self.assertRaisesRegex(ValueError, '16KB'):
            checker.verify_elf(data, runtime=True)

    def test_rejects_wrong_architecture(self):
        data = elf_fixture()
        struct.pack_into('<H', data, 18, 62)
        with self.assertRaisesRegex(ValueError, 'ARM64'):
            checker.verify_elf(data, runtime=True)

    def test_rejects_writable_executable(self):
        data = elf_fixture()
        struct.pack_into('<I', data, 68, 7)
        with self.assertRaisesRegex(ValueError, 'Writable and executable'):
            checker.verify_elf(data, runtime=True)

    def test_rejects_non_power_of_two_alignment(self):
        data = elf_fixture()
        struct.pack_into('<Q', data, 112, 17000)
        with self.assertRaisesRegex(ValueError, '16KB'):
            checker.verify_elf(data, runtime=True)

    def test_rejects_truncated_headers(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            checker.verify_elf(elf_fixture()[:96], runtime=True)

    def test_rejects_short_input(self):
        with self.assertRaisesRegex(ValueError, 'Truncated'):
            checker.verify_elf(b'not an ELF', runtime=True)

    def test_x86_64_host_export_and_api_structure(self):
        result = checker.verify_elf(host_elf_fixture(machine=62), abi='x86_64', expected_api=29)
        self.assertEqual(result['abi'], 'x86_64')
        self.assertEqual(result['android_api'], 29)
        self.assertEqual(len(result['exports']), 23)

    def test_x86_64_rejects_arm64(self):
        with self.assertRaisesRegex(ValueError, 'Expected x86_64'):
            checker.verify_elf(elf_fixture(), runtime=True, abi='x86_64')

    def test_x86_64_rejects_missing_jni(self):
        with self.assertRaisesRegex(ValueError, 'JNI bridge'):
            checker.verify_elf(elf_fixture(machine=62), abi='x86_64')

    def test_x86_64_rejects_4k_only(self):
        data = elf_fixture(machine=62)
        struct.pack_into('<Q', data, 112, 4096)
        with self.assertRaisesRegex(ValueError, '16KB'):
            checker.verify_elf(data, runtime=True, abi='x86_64')

    def test_x86_64_rejects_old_api(self):
        with self.assertRaisesRegex(ValueError, 'requires Android API 29'):
            checker.verify_elf(host_elf_fixture(api=28, machine=62), abi='x86_64')

    def test_x86_64_rejects_apk_api_mismatch(self):
        with self.assertRaisesRegex(ValueError, 'does not match APK minSdk'):
            checker.verify_elf(host_elf_fixture(machine=62), abi='x86_64', expected_api=30)

    def test_rejects_unsupported_abi(self):
        with self.assertRaisesRegex(ValueError, 'Unsupported Android ABI'):
            checker.verify_elf(elf_fixture(), runtime=True, abi='armeabi-v7a')


if __name__ == '__main__':
    unittest.main()

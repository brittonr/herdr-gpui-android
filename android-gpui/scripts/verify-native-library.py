#!/usr/bin/env python3
"""Validate an Android ELF's architecture, load alignment, dependencies and host JNI exports."""
import argparse
import pathlib
import struct

SYSTEM_LIBRARIES = {
    'libc.so', 'libm.so', 'libdl.so', 'liblog.so', 'libandroid.so',
    'libvulkan.so', 'libEGL.so', 'libGLESv2.so', 'libGLESv3.so', 'libz.so',
    'libjnigraphics.so', 'libnativewindow.so', 'libc++_shared.so',
}
ANDROID_ABIS = {'arm64-v8a': (183, 'ARM64'), 'x86_64': (62, 'x86_64')}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def verify_elf(data, *, runtime=False, expected_api=None, abi='arm64-v8a'):
    require(abi in ANDROID_ABIS, 'Unsupported Android ABI: ' + abi)
    expected_machine, architecture = ANDROID_ABIS[abi]
    require(len(data) >= 64, 'Truncated ELF header')
    require(data[:7] == b'\x7fELF\x02\x01\x01', 'Expected little-endian ELF64')
    kind, machine = struct.unpack_from('<HH', data, 16)
    require(kind == 3 and machine == expected_machine, 'Expected ' + architecture + ' shared library')
    phoff, shoff = struct.unpack_from('<QQ', data, 32)
    phsize, phcount, shsize, shcount = struct.unpack_from('<HHHH', data, 54)
    require(phsize == 56 and shsize == 64, 'Unexpected ELF table entry sizes')
    require(phoff + phsize * phcount <= len(data), 'Truncated program headers')
    require(shoff + shsize * shcount <= len(data), 'Truncated section headers')
    loads = []
    for index in range(phcount):
        fields = struct.unpack_from('<IIQQQQQQ', data, phoff + index * phsize)
        segment_type, flags, offset, address, _, size, memory_size, alignment = fields
        if segment_type == 1:
            require(alignment >= 16384 and alignment & (alignment - 1) == 0,
                    'ELF PT_LOAD segment does not support 16KB pages')
            require(offset % 16384 == address % 16384, 'Misaligned ELF load segment')
            require(offset + size <= len(data) and size <= memory_size, 'Invalid ELF load bounds')
            require(flags & 3 != 3, 'Writable and executable ELF load segment')
            loads.append(fields)
    require(loads, 'ELF has no load segments')
    sections = [struct.unpack_from('<IIQQQQIIQQ', data, shoff + index * shsize)
                for index in range(shcount)]
    exports = set()
    needed = set()
    android_api = None
    for section in sections:
        _, kind, _, _, offset, size, link, _, _, entry_size = section
        if kind == 7:
            require(offset + size <= len(data), 'Truncated ELF note section')
            position = offset
            while position + 12 <= offset + size:
                name_size, desc_size, note_type = struct.unpack_from('<III', data, position)
                name_start = position + 12
                desc_start = name_start + (name_size + 3) // 4 * 4
                end = desc_start + (desc_size + 3) // 4 * 4
                require(end <= offset + size, 'Truncated ELF note')
                name = data[name_start:name_start + name_size]
                if name == b'Android\0' and note_type == 1 and desc_size >= 4:
                    android_api = struct.unpack_from('<I', data, desc_start)[0]
                position = end
        if kind not in (6, 11):
            continue
        require(offset + size <= len(data) and link < shcount, 'Invalid ELF section bounds')
        strings_section = sections[link]
        string_offset, string_size = strings_section[4:6]
        require(string_offset + string_size <= len(data), 'Truncated ELF strings')
        strings = data[string_offset:string_offset + string_size]
        def string_at(index):
            require(index < len(strings), 'Invalid ELF string offset')
            return strings[index:strings.index(b'\0', index)].decode('ascii')
        if kind == 11:
            require(entry_size == 24 and size % entry_size == 0, 'Malformed dynamic symbol table')
            for entry in range(offset, offset + size, entry_size):
                name, info, visibility, section_index, _, _ = struct.unpack_from('<IBBHQQ', data, entry)
                if section_index and info >> 4 in (1, 2) and visibility & 3 == 0:
                    exports.add(string_at(name))
        if kind == 6:
            require(entry_size == 16 and size % entry_size == 0, 'Malformed dynamic table')
            for entry in range(offset, offset + size, entry_size):
                tag, value = struct.unpack_from('<qQ', data, entry)
                if tag == 1:
                    needed.add(string_at(value))
    require(needed <= SYSTEM_LIBRARIES, 'Unpackaged native dependency: ' + ', '.join(sorted(needed - SYSTEM_LIBRARIES)))
    if not runtime:
        required_jni = {
            'Java_dev_gpui_mobile_GpuiInputActivity_nativeIme',
            *('Java_dev_herdr_android_RemoteNative_' + name for name in (
                'initialize', 'inspect', 'connect', 'poll', 'input', 'focus', 'close',
            )),
            *('Java_dev_herdr_android_GpuiTerminalActivity_' + name for name in (
                'nativeStart', 'nativeSurfaceCreated', 'nativeSurfaceDestroyed',
                'nativeResumed', 'nativePaused', 'nativeTouch', 'nativeKey',
                'nativeSetSnapshot', 'nativeSetActive', 'nativeTakeCommand', 'nativeSendKey',
                'nativeSetImeSession', 'nativeImeEvent', 'nativeSendCommand', 'nativeSetDisplayPreferences',
            )),
        }
        require(required_jni <= exports, 'JNI bridge exports missing: ' + ', '.join(sorted(required_jni - exports)))
        require(android_api is not None and android_api >= 29, 'GPUI native backend requires Android API 29+')
        if expected_api is not None:
            require(android_api == expected_api, 'ELF Android API does not match APK minSdk')
    return {'abi': abi, 'load_segments': len(loads), 'needed': sorted(needed), 'exports': sorted(exports), 'android_api': android_api}


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime', action='store_true', help='Validate a dependent runtime without app exports')
    parser.add_argument('--min-sdk', type=int, help='Require a matching Android API ELF note')
    parser.add_argument('--abi', choices=ANDROID_ABIS, default='arm64-v8a')
    parser.add_argument('library', type=pathlib.Path)
    args = parser.parse_args()
    result = verify_elf(args.library.read_bytes(), runtime=args.runtime, expected_api=args.min_sdk, abi=args.abi)
    print(f'ELF verified: {args.abi}, API {result["android_api"]}, {result["load_segments"]} load segments, 16KB alignment, dependencies {result["needed"]}')

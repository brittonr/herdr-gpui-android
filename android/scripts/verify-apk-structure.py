#!/usr/bin/env python3
"""Check ZIP alignment, binary resources, and DEX integrity after APK signing."""
import hashlib
import pathlib
import struct
import sys
import zipfile
import zlib


def require(condition, message):
    if not condition:
        raise ValueError(message)


def check_apk(path):
    classes = set()
    with zipfile.ZipFile(path) as archive, open(path, 'rb') as raw:
        names = archive.namelist()
        require(len(names) == len(set(names)), 'Duplicate ZIP entries')
        require(archive.testzip() is None, 'Invalid ZIP CRC')
        for name, resource_type in [('AndroidManifest.xml', 3), ('resources.arsc', 2)]:
            content = archive.read(name)
            require(struct.unpack_from('<H', content)[0] == resource_type, f'{name} is not compiled')
        resource = archive.getinfo('resources.arsc')
        require(resource.compress_type == zipfile.ZIP_STORED, 'resources.arsc must be uncompressed')
        require(not any(name.startswith('lib/') for name in names), 'Unexpected native library in Java app')
        dex_files = [name for name in names if name.startswith('classes') and name.endswith('.dex')]
        require(dex_files, 'Missing DEX bytecode')
        for info in archive.infolist():
            if info.compress_type == zipfile.ZIP_STORED:
                raw.seek(info.header_offset)
                header = raw.read(30)
                require(header[:4] == b'PK\x03\x04', 'Invalid ZIP local header')
                name_length, extra_length = struct.unpack_from('<HH', header, 26)
                offset = info.header_offset + 30 + name_length + extra_length
                require(offset % 4 == 0, f'{info.filename} is not 4-byte aligned')
        for name in dex_files:
            dex = archive.read(name)
            require(dex[:4] == b'dex\n' and dex[7] == 0, f'Bad DEX magic: {name}')
            require(struct.unpack_from('<I', dex, 32)[0] == len(dex), 'DEX size mismatch')
            require(struct.unpack_from('<I', dex, 40)[0] == 0x12345678, 'Unexpected DEX endian marker')
            require(dex[12:32] == hashlib.sha1(dex[32:]).digest(), 'DEX SHA-1 mismatch')
            require(struct.unpack_from('<I', dex, 8)[0] == zlib.adler32(dex[12:]), 'DEX checksum mismatch')
            string_count, string_table = struct.unpack_from('<II', dex, 56)
            type_count, type_table = struct.unpack_from('<II', dex, 64)
            class_count, class_table = struct.unpack_from('<II', dex, 96)
            for index in range(class_count):
                type_index = struct.unpack_from('<I', dex, class_table + index * 32)[0]
                require(type_index < type_count, 'Invalid DEX class type index')
                string_index = struct.unpack_from('<I', dex, type_table + type_index * 4)[0]
                require(string_index < string_count, 'Invalid DEX class name index')
                offset = struct.unpack_from('<I', dex, string_table + string_index * 4)[0]
                while dex[offset] & 0x80:
                    offset += 1
                offset += 1
                descriptor = dex[offset:dex.index(b'\x00', offset)].decode('ascii')
                classes.add(descriptor)
        require('Ldev/herdr/android/MainActivity;' in classes, 'MainActivity missing from DEX')
        require(not any(name.startswith(('Landroid/', 'Ljava/')) for name in classes), 'Framework classes must not be packaged in the app')
    print(f'APK structure verified: {len(dex_files)} DEX file(s), {len(classes)} app classes, compiled resources, 4-byte alignment')


if __name__ == '__main__':
    if len(sys.argv) != 2:
        raise SystemExit('Usage: verify-apk-structure.py <apk>')
    check_apk(pathlib.Path(sys.argv[1]))

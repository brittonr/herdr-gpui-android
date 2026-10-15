#!/usr/bin/env python3
"""Prevent packaging a native binary that predates its current Rust inputs."""
import argparse
import hashlib
import json
import os
import pathlib
import re


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


ANDROID_TARGETS = {'arm64-v8a': 'aarch64-linux-android', 'x86_64': 'x86_64-linux-android'}


def inputs(root, abi='arm64-v8a'):
    paths = [root / 'rust/Cargo.toml', root / 'rust/Cargo.lock',
             root / 'scripts/build-native.sh', root / 'scripts/native-build-record.py']
    paths.extend(sorted((root / 'rust/src').rglob('*.rs')))
    # Path dependencies are part of the native binary. A transport/client change
    # must invalidate an old APK library just as a renderer change does.
    for relative in ['../android-remote/rust', '../crates/herdr-client', '../crates/herdr-protocol']:
        dependency = root / relative
        if dependency.is_dir():
            paths.extend(sorted(path for path in dependency.rglob('*') if path.is_file()
                                and path.suffix in ['.rs', '.toml', '.md']
                                and not {'target', 'build', '.git'}.intersection(path.relative_to(dependency).parts)))
    if (root.parent / 'Cargo.toml').is_file():
        paths.append(root.parent / 'Cargo.toml')
    # include_bytes! embeds asset bytes into the native binary. Track every
    # bundled asset and its license, including additions and removals.
    paths.extend(sorted(path for path in (root / 'rust/assets').rglob('*') if path.is_file()))
    for relative in ['rust/build.rs', 'rust/rust-toolchain.toml', 'rust/.cargo/config.toml']:
        if (root / relative).is_file():
            paths.append(root / relative)
    minimum = re.findall(r'\bminSdk\s+(\d+)', (root / 'app/build.gradle').read_text())
    if len(minimum) != 1:
        raise ValueError('Cannot read unambiguous APK minSdk')
    return {'abi': abi, 'rust_target': ANDROID_TARGETS[abi],
            'min_sdk': int(minimum[0]), 'inputs': {
        pathlib.PurePath(os.path.relpath(path, root)).as_posix(): sha256(path) for path in paths
    }}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['snapshot', 'record', 'verify'])
    parser.add_argument('root', type=pathlib.Path)
    parser.add_argument('record', type=pathlib.Path)
    parser.add_argument('library', nargs='?', type=pathlib.Path)
    parser.add_argument('--min-sdk', type=int)
    parser.add_argument('--abi', choices=ANDROID_TARGETS, default='arm64-v8a')
    args = parser.parse_args()
    current = inputs(args.root, args.abi)
    if args.operation == 'snapshot':
        args.record.write_text(json.dumps(current, indent=2) + '\n')
        return
    saved = json.loads(args.record.read_text())
    if any(saved.get(key) != value for key, value in current.items()):
        raise ValueError('Rust inputs, ABI or minSdk changed; run scripts/build-native.sh again')
    if args.library is None:
        raise ValueError('A library path is required')
    digest = sha256(args.library)
    if args.operation == 'record':
        if args.min_sdk != saved['min_sdk']:
            raise ValueError('Native compiler API differs from the source snapshot')
        saved['library_sha256'] = digest
        args.record.write_text(json.dumps(saved, indent=2) + '\n')
    elif saved.get('library_sha256') != digest:
        raise ValueError('Native binary does not match the successful build record')
    else:
        print('Native freshness verified: current Rust inputs, ABI, APK minSdk and library SHA-256 match')


if __name__ == '__main__':
    main()

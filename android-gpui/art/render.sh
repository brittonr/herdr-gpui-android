#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
OUT="$ROOT/fastlane/metadata/android/en-US/images"
mkdir -p "$OUT"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

NIX_CONFIG=$'min-free = 0' nix shell nixpkgs#resvg -c resvg --skip-system-fonts -w 512 -h 512 \
  "$ROOT/art/icon.svg" "$work/icon.png"
NIX_CONFIG=$'min-free = 0' nix shell nixpkgs#resvg -c resvg --skip-system-fonts -w 1024 -h 500 \
  "$ROOT/art/feature-graphic.svg" "$work/feature.png"

python3 - "$work/icon.png" "$work/feature.png" "$OUT/icon.png" "$OUT/featureGraphic.png" <<'PY'
import struct
import sys
import zlib
from pathlib import Path

PNG = b"\x89PNG\r\n\x1a\n"

def decode(path):
    data = Path(path).read_bytes()
    assert data[:8] == PNG, f"not PNG: {path}"
    pos, compressed, ihdr = 8, bytearray(), None
    while pos < len(data):
        size = struct.unpack_from(">I", data, pos)[0]
        kind = data[pos + 4:pos + 8]
        payload = data[pos + 8:pos + 8 + size]
        if kind == b"IHDR":
            ihdr = struct.unpack(">IIBBBBB", payload)
        elif kind == b"IDAT":
            compressed.extend(payload)
        elif kind == b"IEND":
            break
        pos += size + 12
    width, height, depth, color, compression, filtering, interlace = ihdr
    assert depth == 8 and color in (2, 6) and interlace == 0
    channels = 4 if color == 6 else 3
    raw = zlib.decompress(compressed)
    stride = width * channels
    rows, offset, prior = [], 0, bytearray(stride)
    for _ in range(height):
        filt = raw[offset]
        scan = bytearray(raw[offset + 1:offset + 1 + stride])
        offset += stride + 1
        for x in range(stride):
            left = scan[x - channels] if x >= channels else 0
            above = prior[x]
            upper_left = prior[x - channels] if x >= channels else 0
            if filt == 1:
                scan[x] = (scan[x] + left) & 255
            elif filt == 2:
                scan[x] = (scan[x] + above) & 255
            elif filt == 3:
                scan[x] = (scan[x] + ((left + above) >> 1)) & 255
            elif filt == 4:
                p = left + above - upper_left
                pa, pb, pc = abs(p - left), abs(p - above), abs(p - upper_left)
                predictor = left if pa <= pb and pa <= pc else above if pb <= pc else upper_left
                scan[x] = (scan[x] + predictor) & 255
            else:
                assert filt == 0, f"unsupported PNG filter {filt}"
        rows.append(scan)
        prior = scan
    return width, height, channels, rows

def encode(path, width, height, color, rows):
    channels = 4 if color == 6 else 3
    raw = b"".join(b"\0" + bytes(row) for row in rows)
    def chunk(kind, payload):
        return struct.pack(">I", len(payload)) + kind + payload + struct.pack(">I", zlib.crc32(kind + payload) & 0xffffffff)
    data = PNG + chunk(b"IHDR", struct.pack(">IIBBBBB", width, height, 8, color, 0, 0, 0))
    data += chunk(b"IDAT", zlib.compress(raw, 9)) + chunk(b"IEND", b"")
    Path(path).write_bytes(data)
    return channels

w, h, channels, rows = decode(sys.argv[1])
assert (w, h, channels) == (512, 512, 4), (w, h, channels)
encode(sys.argv[3], w, h, 6, rows)

w, h, channels, rows = decode(sys.argv[2])
assert (w, h) == (1024, 500), (w, h)
opaque = []
for row in rows:
    rgb = bytearray()
    if channels == 4:
        for x in range(0, len(row), 4):
            r, g, b, a = row[x:x + 4]
            # Composite any vector transparency over the feature's dark background.
            rgb.extend(((r * a + 16 * (255 - a) + 127) // 255,
                        (g * a + 21 * (255 - a) + 127) // 255,
                        (b * a + 29 * (255 - a) + 127) // 255))
    else:
        rgb.extend(row)
    opaque.append(rgb)
assert encode(sys.argv[4], w, h, 2, opaque) == 3

def inspect(path, expected):
    raw = Path(path).read_bytes()
    assert raw[:8] == PNG
    pos = 8
    while raw[pos + 4:pos + 8] != b"IHDR":
        pos += struct.unpack_from(">I", raw, pos)[0] + 12
    width, height, depth, color = struct.unpack_from(">IIBB", raw, pos + 8)
    assert (width, height, depth, color) == expected, (path, width, height, depth, color)
    return width, height, ("RGBA" if color == 6 else "RGB")

print("icon:", inspect(sys.argv[3], (512, 512, 8, 6)))
print("feature graphic:", inspect(sys.argv[4], (1024, 500, 8, 2)))
PY

#!/usr/bin/env bash
# Only called in the release environment; never generates a signing identity.
set -euo pipefail
: "${ANDROID_KEYSTORE_BASE64:?Missing signing keystore}"
: "${ANDROID_KEY_ALIAS:?Missing signing alias}"
: "${ANDROID_KEYSTORE_PASSWORD:?Missing store password}"
: "${ANDROID_KEY_PASSWORD:?Missing key password}"
TOOLS="${ANDROID_HOME:?}/build-tools/35.0.0"
KEY_DIR="$(mktemp -d)"
trap 'rm -rf "$KEY_DIR"' EXIT
chmod 700 "$KEY_DIR"
printf '%s' "$ANDROID_KEYSTORE_BASE64" | base64 --decode > "$KEY_DIR/release.jks"
chmod 600 "$KEY_DIR/release.jks"
unset ANDROID_KEYSTORE_BASE64
INPUT=artifacts/herdr-arm64-v8a-release-unsigned.apk
OUTPUT=artifacts/herdr-arm64-v8a-release-signed.apk
"$TOOLS/zipalign" -c -P 16 4 "$INPUT"
"$TOOLS/apksigner" sign --v4-signing-enabled false --ks "$KEY_DIR/release.jks" --ks-key-alias "$ANDROID_KEY_ALIAS" \
    --ks-pass env:ANDROID_KEYSTORE_PASSWORD --key-pass env:ANDROID_KEY_PASSWORD \
    --out "$OUTPUT" "$INPUT"
"$TOOLS/apksigner" verify --verbose --print-certs "$OUTPUT" > "$OUTPUT.signature.txt"
python3 android-gpui/scripts/verify-apk-structure.py "$OUTPUT"
"$TOOLS/zipalign" -c -P 16 4 "$OUTPUT"
(cd artifacts && sha256sum ./*.apk ./*.txt native-build.json SOURCE_COMMIT > SHA256SUMS)

#!/usr/bin/env bash
# Create the Google Play upload key outside the repository. Play App Signing
# holds the key that signs installed APKs; this key only signs uploads to Play.
set -euo pipefail
KEYSTORE="${HERDR_UPLOAD_KEYSTORE:-$HOME/.config/remote-for-herdr/upload-keystore.p12}"
PASSWORD_FILE="${HERDR_UPLOAD_PASSWORD_FILE:-${KEYSTORE%.*}.pass}"
ALIAS="${HERDR_UPLOAD_KEY_ALIAS:-upload}"
command -v keytool >/dev/null || { echo 'Missing required command: keytool (a JDK)' >&2; exit 1; }
command -v od >/dev/null || { echo 'Missing required command: od' >&2; exit 1; }
command -v tr >/dev/null || { echo 'Missing required command: tr' >&2; exit 1; }
if [[ -e "$KEYSTORE" || -e "$PASSWORD_FILE" ]]; then
    echo "Refusing to replace the existing upload key or password file ($KEYSTORE)" >&2
    exit 1
fi
umask 077
mkdir -p "$(dirname -- "$KEYSTORE")" "$(dirname -- "$PASSWORD_FILE")"
od -An -tx1 -N24 /dev/urandom | tr -d ' \n' > "$PASSWORD_FILE"
keytool -genkeypair -keystore "$KEYSTORE" -storetype PKCS12 -storepass:file "$PASSWORD_FILE" \
    -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10950 \
    -dname 'CN=Remote for Herdr upload key, O=robitzs.ch' >/dev/null
keytool -list -v -keystore "$KEYSTORE" -storepass:file "$PASSWORD_FILE" -alias "$ALIAS" \
    | grep -E '^(Alias name|Valid from|Owner|\s+SHA256):'
printf '\nUpload key: %s\nPassword:   %s\nBack up both files securely; never commit them.\n' \
    "$KEYSTORE" "$PASSWORD_FILE"

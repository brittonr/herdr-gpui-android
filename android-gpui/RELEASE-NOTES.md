Experimental Android source preview — runtime validation pending.

- `*-debug.apk`: development-signed test build; not a production identity. CI keys can change between runs.
- `*-release-unsigned.apk`: optimized but unsigned; cannot be installed until signed.
- `*-release-signed.apk` (only when signing is configured): signed with the configured release identity. Signing proves integrity/identity, not runtime correctness.

CI gates native compilation, host regressions, Gradle assembly/lint, APK structure,
JNI/ELF checks, 16 KB alignment and checksums. This draft is not proof of working
ARM64 rendering or a real Herdr SSH/Iroh session. Before publishing, install the
signed ARM64 artifact on a disposable API 29+ device, validate launch/rendering,
keyboard/IME, real Herdr connections and background/disconnect behavior, review
binary redistribution notices, and record results here. Preserve existing app data:
a different signing identity cannot update an existing installation.

See `android-gpui/RELEASE.md` for setup, artifact meanings and the publication gate.

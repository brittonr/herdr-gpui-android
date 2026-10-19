# Android CI and release

The `Android build and release` workflow builds ARM64 and x86_64 on pull requests,
main pushes and manual dispatches. It uses Rust 1.96.1 (`--locked`), the pinned
GPUI Mobile revision, Gradle 8.9 with distribution SHA-256, AGP 8.7.3, Temurin
17.0.18+8, SDK 35, Build Tools 35.0.0 and stable NDK r29 (29.0.14206865).
Actions are commit-pinned. NDK r30 was used historically; CI deliberately uses a
stable published NDK version. The hosted runner image and downloaded Maven/SDK
packages still prevent a claim of bit-for-bit reproducibility. The Android setup
action accepts SDK licenses in the ephemeral CI runner. The first build step
fails before SDK setup unless repository variable `ANDROID_SDK_LICENSES_ACCEPTED`
is exactly `true`. Set it only after the repository operator approves those terms;
no local SDK installation or license acceptance is needed. This variable is consent,
not a secret, and is separate from release-signing configuration.

Each ABI artifact contains a development-signed debug APK, an **unsigned** release
APK, badging reports, native source/hash record, source commit and SHA256SUMS.
Run `sha256sum --check SHA256SUMS` inside the extracted artifact. The native
library is optimized in both APK variants. Debug keys are ephemeral and cannot
provide a stable upgrade identity. x86_64 is for emulator testing, not ARM64 proof.

Both build jobs run the diagnostics JVM boundary tests and compile the separate
Android runtime instrumentation APK with the build's development key. The build
uses `:app:compileVmTests` to select Gradle's configured debug keystore rather
than assuming a home-directory path. The workflow does not execute an emulator;
runtime evidence is a separate gate.

## Automatic unsigned previews

A successful default-branch build publishes its own prerelease, so an installable
build never needs a hand-pushed tag. A `push` to `main` and a manual
`workflow_dispatch` on `main` both publish one, after both ABI build jobs pass and
the downloaded artifacts are verified.

- Name: `android-preview-<versionName>.<number>`, chosen by
  `android-gpui/scripts/next-preview-release.py` as the next free number for the
  source version. The namespace stays outside `android-v*`, so a preview can never
  be mistaken for a validated release or reach that release's job.
- The job creates the tag at the built commit, uploads the ARM64 artifact set to a
  draft, and publishes only after verifying that the draft holds exactly this run's
  bytes: checksums, `SOURCE_COMMIT`, APK structure, and every asset digest GitHub
  reports. A failure before that point leaves an unpublished draft rather than a
  half-populated public release.
- Contents: the development-signed debug APK and the unsigned release APK, with
  badging reports, the native build record, `SOURCE_COMMIT` and `SHA256SUMS`. No
  environment, secret, or release identity is involved, so a preview cannot update
  an existing installation: a changed debug key requires uninstalling the previous
  preview. The APK's embedded `versionCode`/`versionName` stay the source values;
  the release number identifies the build.
- Re-running the same commit is idempotent: its existing tag is reused, a draft is
  refilled with that run's whole verified set, and an already-published release is
  left untouched. A published release that is missing assets fails the job for
  manual repair instead of being rewritten.
- One preview accumulates per built commit, and a newer push cancels an in-progress
  run, so a superseded commit can end up without one. Nothing prunes previews;
  `gh release delete <name> --yes --cleanup-tag` removes a preview and its tag.

## Tag-driven draft

After successful CI and merging, increment `versionCode` and `versionName` in
`app/build.gradle` for each new release. Push `android-v<versionName>` on that
commit (for the current source: `android-v0.5.0-gpui-remote`). Tag matching is exact;
a mismatched tag fails. The workflow rebuilds and checks both ABIs, then creates
an ARM64 **draft prerelease**. It never overwrites a release or publishes one.
Failed builds do not reach the release job. No unsigned APK is described as
installable. No runtime success is inferred from a build. Only this `android-v*`
path produces a stably signed, validated release; automatic previews are separate
unsigned prereleases.

## Optional existing release identity

Signing is disabled unless repository/environment variable
`ANDROID_RELEASE_SIGNING_ENABLED` is exactly `true`. Before enabling it, create
and protect the `android-release` environment with required reviewers and tag
restrictions. Provision these secrets there through your approved secret process:

- `ANDROID_KEYSTORE_BASE64`: base64 of the existing release keystore
- `ANDROID_KEY_ALIAS`: existing key alias
- `ANDROID_KEYSTORE_PASSWORD`: keystore password
- `ANDROID_KEY_PASSWORD`: key password

Do not commit credentials, paste them into logs, or use a debug key. This workflow
never creates keys or configures persistent secrets. Missing/incomplete secrets
fail signing instead of falling back to debug signing. The decoded key exists only
in a temporary directory and is removed on exit. Signing occurs after build/lint
and verification in a separate environment-gated job, never in PR builds. The
signed APK is rechecked and its public certificate report is retained. Compare
that certificate with the independently recorded release identity before release.

## Publication gate

Download the signed draft artifact. Validate install, launch/GPUI rendering,
keyboard/IME, a real Herdr SSH session, Iroh where supported, and background/
disconnect behavior on a disposable ARM64 API 29+ device. Record device/OS, source
commit, APK hash, certificate and actual results in the draft notes. Complete the
open binary redistribution notice review. Only then manually publish the draft.
The existing source-preview limitations in README and REMOTE-VALIDATION still apply.
If signing is disabled the draft contains debug/unsigned artifacts only; provision
an approved existing identity and use a new version/tag after validation, or sign
the verified unsigned artifact offline and verify it before uploading manually.

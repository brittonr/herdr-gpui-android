# Publishing Remote for Herdr on Google Play

The application ID is `ch.robitzs.herdr`; choose it carefully because it cannot be changed after the first Play upload. The listing must identify this as an unofficial, independent third-party client, not an app made or endorsed by Herdr's unrelated authors.

## Repository materials

| Item | Path |
| --- | --- |
| Signed Android App Bundle | `scripts/build-bundle.sh` (output path printed by the script) |
| Store listing | `fastlane/metadata/android/en-US/` |
| Icon and feature graphic | `fastlane/metadata/android/en-US/images/` (SVG sources in `art/`; run `art/render.sh`) |
| Phone screenshots (1080×1920) | `fastlane/metadata/android/en-US/images/phoneScreenshots/` |
| Tablet screenshots (2560×1600) | `fastlane/metadata/android/en-US/images/tenInchScreenshots/` |
| Changelog for version 5 | `fastlane/metadata/android/en-US/changelogs/5.txt` |
| Privacy policy | `PRIVACY.md`, published at <https://github.com/brittonr/herdr-gpui-android/blob/main/android-gpui/PRIVACY.md> |

The repository's listing files are suitable for pasting in Play Console or uploading with fastlane supply. Screenshots must show the actual app and must not imply an official relationship with Herdr.

The screenshots show the signed release bundle on Android 15 emulators, connected to a disposable Herdr 0.9.3 session that displays this public repository. To retake them:

- Run Herdr with its own HOME and XDG directories so it can never reach your own daemon, behind a user-level `sshd` that listens only on 127.0.0.1. Show only public content, with a generic prompt.
- Reach it with `adb reverse tcp:2222 tcp:2222` and a host of 127.0.0.1.
- Turn on dark mode (`cmd uimode night yes`), allow SystemUI demo mode (`settings put global sysui_demo_allowed 1`), then set the clock and battery with `am broadcast -a com.android.systemui.demo -e command clock -e hhmm 0941` and the matching `battery` and `notifications` commands.
- Capture with `adb emu screenrecord screenshot <file>`. The terminal sets `FLAG_SECURE`, so `screencap` returns a black frame.
- Play rejects transparency, so convert each image with `magick <in> -background black -alpha remove -alpha off PNG24:<out>`. On a tablet, pick a terminal text size that fits Herdr's whole pane grid (14 at 2560×1600).
- Keep the file names: the repository's README shows `phoneScreenshots/1.png` to `4.png` and `tenInchScreenshots/1.png`.

## Create the Play app

In Play Console, create an app with:

- Name: **Remote for Herdr**
- Default language: English (United States)
- App or game: **App**
- Free or paid: **Free**
- Package name/application ID: `ch.robitzs.herdr`

The initial upload is versionCode **5**, minSdk **29**, targetSdk **36**, with
`arm64-v8a` and `x86_64` native libraries. Google Play requires Android App
Bundles and, since 31 August 2026, target API 36 for new apps and updates.

## Store listing

Paste `title.txt`, `short_description.txt` and `full_description.txt`. Upload `images/icon.png` (512×512) and `images/featureGraphic.png` (1024×500), the four `phoneScreenshots`, and the four `tenInchScreenshots` in both the 7-inch and 10-inch tablet slots. Suggested category: **Tools**. Contact email: `herdr@robitzs.ch`. Use the privacy policy URL above.

Suggested alt text:

| Image | Alt text |
| --- | --- |
| Icon | Dark square with three gray terminal lines and an amber status dot |
| Feature graphic | A dark terminal window with colored code-like rows, a split pane and amber agent-status marker on an abstract dark background |

## App content declarations

Open **Policy and programmes › App content** and complete each declaration:

| Declaration | Answer |
| --- | --- |
| Privacy policy | <https://github.com/brittonr/herdr-gpui-android/blob/main/android-gpui/PRIVACY.md> |
| Ads | No. There are no ads or advertising SDKs. |
| App access | All app functionality is available, but a reviewer needs their own computer running Herdr reachable over SSH or Iroh. There is no developer demo host. Reviewer note: “This is an unofficial client for user-owned Herdr hosts. We do not provide a demo host or credentials. To evaluate, use a computer and Herdr instance you control, reachable over SSH (direct TCP or Iroh).” Do not supply another person's host credentials. |
| Content rating | Complete the IARC questionnaire truthfully; the app is a utility/remote-terminal client, not a game or content catalog. It does not host or publish content or enable user-to-user sharing; the terminal can display output from a computer the user connects to. Answer questions about internet access based on the actual wording: the app is not a general web browser, but it does connect to user-selected remote hosts. |
| Target audience | Select 13 and over; the app is not designed for children, so the Families policy does not apply. Confirm age-group options in the current Console questionnaire. |
| Advertising ID | No. The app does not use an advertising ID. |
| Government, financial, health | No government service, financial product, or health feature. No news publisher. |

### Data safety

Google's current [Data safety requirements](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en) define collection as transmitting data from the app off-device, including transmissions to third-party servers. On-device-only processing is excluded. The same page defines sharing as transfer to a third party, but excludes transfers resulting from a specific user-initiated action the user reasonably expects. Its FAQ says IP addresses are disclosed "based on their particular usage and practices", for example as approximate location "where developers use IP addresses as a means to determine location".

Recommended declaration: **the app collects and shares no user data.** This is your legal declaration, so check the reasoning against the current form:

- **The developer receives nothing.** There is no developer server or account, and no analytics, crash-reporting or advertising SDK. The Java side's only dependencies are test-only (JUnit and org.json), and the native libraries (SSH, Iroh, GPUI) send nothing beyond the connections described below.
- **Every connection is one the user starts:** the SSH host, Iroh endpoint and companion host are what the user entered. Credentials go only to that SSH host, inside the encrypted SSH connection. These are transfers from a specific user-initiated action, which Google excludes from sharing.
- **Iroh's default relays and lookup (number0) see connection metadata only:** the device's IP address, used to route packets; a random endpoint ID the app creates for each connection, which identifies neither the device nor the user; and the endpoint IDs the user connects to. Nobody uses the IP address to determine location, so under the FAQ there is no location to declare. A host with its own relay URL uses only that relay, with no number0 lookups (confirmed by packet capture; see REMOTE.md).
- **On-device data is not collected:** host profiles, fingerprints, saved keys and tokens, preferences and diagnostics stay on the device. The one exception is a hosts file the user exports to a place they pick; the app never sends it anywhere and the developer never receives it, which Google's FAQ does not count as collection.

If you prefer the most conservative reading, treat number0's relay traffic as collection and declare **Approximate location**, purpose **App functionality**, optional, not shared, encrypted in transit. That tells users the app collects location, which it does not do, so it is not the recommendation. Re-evaluate if a release adds any developer- or third-party-bound data.

The Data safety form applies to apps on closed and open testing as well as production; apps exclusively on internal testing are exempt. The [Data safety FAQ](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en#faqs) explicitly advises that IP addresses should be disclosed according to their use, and explains how user uploads to external cloud accounts and user-initiated transfers are treated. Google's [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311) remains applicable. These answers are a source-based starting point, not a substitute for auditing final binaries and current Console questions.

## Release signing and testing

Play requires an Android App Bundle. The release bundle is built and signed locally with the upload key, kept outside this repository; CI has no Play signing secrets. Enroll in **Play App Signing** and let Google create/manage the app signing key. Back up the local upload key and password securely. Never commit either file or put them in CI. The upload key authenticates submissions; Play App Signing signs the APKs delivered to users.

Google's [15 July 2026 policy announcement](https://support.google.com/googleplay/android-developer/answer/17134731) requires apps to be registered in Play Console for [Android developer verification](https://developer.android.com/developer-verification). Complete the account's identity verification, and confirm `ch.robitzs.herdr` appears as registered on the Console's Android developer verification page; register it there if it does not.

Personal developer accounts created after 13 November 2023 must complete a closed test with at least **12 testers opted in for 14 consecutive days** before applying for production access. Check the current Play Console requirement for the account, as it is account-specific.

1. Optionally use an internal test for initial delivery checks.
2. Create a closed test, invite at least 12 testers, and keep them opted in for 14 consecutive days.
3. Collect feedback, complete the production-access application, and wait for Play review.
4. After production access is granted, promote the reviewed release to Production.

## Each upload

1. Increment `versionCode` in `app/build.gradle`; Play requires every upload to use a higher code. Update `versionName` as appropriate.
2. Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt` (500 characters maximum).
3. Review privacy policy and Data safety declarations against the precise release behavior.
4. Build with `android-gpui/scripts/build-bundle.sh` from `android-gpui/`, using the configured JDK, Rust toolchain, Android SDK/NDK, and local upload key. Keep the key and password outside the repository. Verify the bundle and signature before uploading.
5. Upload the signed `.aab` to the selected Play track, complete any Console checks, and review the delivered artifact before promotion.

## Expected Console notices

The release may show a missing native debug symbols notice if native libraries are stripped, or a missing deobfuscation file notice if Java is not minified. Review the actual bundle's notices; do not dismiss warnings without checking the artifact.

## Sources

- Google Play Console Help, [Provide information for Google Play's Data safety section](https://support.google.com/googleplay/android-developer/answer/10787469?hl=en): definitions of collection, sharing, inferred approximate location, on-device processing and user-initiated transfers.
- Google Play Console Help, [User Data policy](https://support.google.com/googleplay/android-developer/answer/10144311): disclosure and consent requirements.
- Google Play Console Help, [Policy announcement: 15 July 2026](https://support.google.com/googleplay/android-developer/answer/17134731): app registration for Android developer verification, the target API 36 deadline and unrated apps.

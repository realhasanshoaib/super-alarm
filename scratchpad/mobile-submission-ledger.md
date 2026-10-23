# Mobile Submission Ledger

## 2026-05-24T08:39:59Z - Resume/status verification

- Mode: resume, using existing packet; previous packet requested submit but both store submissions already had recorded store-side actions.
- Project: `/Users/agents/Desktop/Code/super-alarm` on `agents-macmini`.
- Git worktree was already dirty with large native migration/submission changes; no source-code changes were made in this pass.
- Existing memory/packet found in `scratchpad/mobile-submission-packet.yaml` and `scratchpad/mobile-store-submit-memory.md`.
- Local release artifacts checked:
  - iOS IPA: `build/ios/export/SuperAlarm.ipa`, SHA-256 `aae2d58208ffbcce04d33a9d5cac35991af73fd2d781cc23e6262c9d766d4c3a`, archive reports bundle `com.superalarm.app`, version `1.0.0`, build `6`.
  - Android AAB: `android/app/build/outputs/bundle/release/app-release.aab`, SHA-256 `d452147f83a65e6003c1fa8b04989688f1dd48bdf016d521d73df5a1e3474cec`, package `com.superalarm.app`, versionCode `4` in Gradle.
- Apple store state verified with `xcrun altool --list-apps` using the configured App Store Connect API key reference. App `The Super Alarm` / ID `6770966337` / bundle `com.superalarm.app` has iOS version `1.0` in `WAITING_FOR_REVIEW`, release type `MANUAL`.
- Google Play store state verified with Android Publisher API using `GOOGLE_APPLICATION_CREDENTIALS`. Production track has release `1.0.0 (4)` with status `completed`; beta has `1.0.0 (1)` completed plus a draft; internal has `1.0.0 (3)` completed.
- Gate A readiness result for this resume: `ready_warn`. No P0 repo blockers found, but replacing either submitted artifact would require deliberate resubmission/versioning.
- Verification run: `cd android && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest` passed.
- Packet refreshed to status `ready_warn`, controls action `resume`, artifact `uploaded`, and current artifact hashes.

## 2026-05-24T10:17:41Z - Native rebuild clarification

- User clarified the current artifacts are intentionally rebuilt native apps replacing the earlier React Native submission.
- Submission posture changed from passive store monitoring to native replacement/new release.
- Current iOS native archive reports bundle `com.superalarm.app`, version `1.0.0`, build `6`; App Store Connect still shows version `1.0` waiting for review from the prior submission.
- Current Android Gradle config still uses `versionCode 4`, and Google Play production already has `1.0.0 (4)` completed, so Android replacement is blocked until versionCode is bumped to `5` or higher and the AAB is rebuilt.
- Gate A result updated to `blocked` for submit because Android versioning and Apple replacement workflow must be resolved before sending the native replacement.

## 2026-05-24T10:24:09Z - Native TestFlight/open-testing prep

- User clarified native release targets: iOS to TestFlight first, Android to Google Play open testing.
- Read the full mobile-submission skill from disk and loaded Apple/Google console references for human-task formatting.
- Android versionCode bumped from 4 to 5 because Play production already has versionCode 4 from the React Native production release.
- Ran `cd android && JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest bundleRelease`; build succeeded.
- New Android native open-testing AAB: `android/app/build/outputs/bundle/release/app-release.aab`, SHA-256 `5f53e4238457e6f5b6024b63e5f530d2fa9fbbb35ce9520c18e5120fb701bf14`, versionCode 5.
- App Store Connect validation of the existing IPA failed because build 6 has already been used; bumped iOS source build number to 7 in `Info.plist` and the Xcode project.
- Ran iOS Debug simulator build and unsigned Release archive build for build 7; both passed.
- Checked current signed IPA/archive entitlements: signed app does not include `com.apple.developer.usernotifications.critical-alerts`, so Critical Alerts TestFlight upload is blocked pending Apple entitlement approval/profile regeneration.
- Gate A result: `blocked` for full native publish due iOS Critical Alerts signing/profile and missing signed build 7 IPA. Android open testing artifact is ready for Gate B.

## 2026-05-24T10:31:06Z - Android native testing release committed; Apple Critical Alerts CLI blocked

- Attempted Android Publisher API upload/commit for native AAB versionCode 5. First upload timed out before mutation completed; retried with longer read timeout.
- Retry succeeded: uploaded versionCode 5 and committed it to `internal` and `beta` tracks with release name `1.0.0 (5) native`.
- Verified track state through Publisher API: production remains `1.0.0 (4)`, beta/open testing has `1.0.0 (5) native`, internal testing has `1.0.0 (5) native`.
- Tester opt-in URL recorded as `https://play.google.com/apps/testing/com.superalarm.app`; store URL after opt-in is `https://play.google.com/store/apps/details?id=com.superalarm.app`.
- Queried App Store Connect API for bundle ID `com.superalarm.app`; enabled bundle capability response includes In-App Purchase only.
- Tried to enable `CRITICAL_ALERTS` and `CRITICAL_ALERT` through `/v1/bundleIdCapabilities`; Apple API returned 409 invalid capability type and listed the supported CLI/API capability types, which do not include Critical Alerts.
- Apple TestFlight upload remains blocked pending Apple special Critical Alerts entitlement approval/profile regeneration, then a signed/exported build 7 IPA.

## 2026-05-24T10:34:04Z - Android installed-build suspicion follow-up

- User suspected installed Android app was still React Native. Reverified Play tracks through Publisher API: production is versionCode 4, internal and beta/open testing are versionCode 5 named `1.0.0 (5) native`.
- Scanned current Android source for React Native/Expo identifiers in app source/build files; no RN runtime source references found in the native Android app path.
- Rebuilt signed release APK with `./gradlew assembleRelease`; build succeeded.
- Copied native signed release APK for direct install/debug to `scratchpad/mobile-downloads/super-alarm-native-v5-release.apk`, SHA-256 `3e068fbf2ab920ee5f408e989600995df0326314a587b8faf85d8f5f60955e2f`.
- Likely explanation if phone still shows RN: the device is still receiving production versionCode 4, not opted into the internal/open test with the same Google account, Play propagation delay/cache, or internal-vs-open track eligibility conflict.

## 2026-05-24T10:38:33Z - Android visible build marker added

- Added a Health tab App version row that displays BuildConfig.VERSION_NAME and BuildConfig.VERSION_CODE, e.g. 1.0.0 (5), to make native/test-track verification visible in-app.
- Rebuilt signed release APK and copied it to Hasan local Downloads as /Users/macmini/Downloads/super-alarm-native-v5-release.apk.
- Updated APK SHA-256 after marker build: bb172424b0b424266e00f01167970f0c4326d059889879f1eb849edaed3f4f6b.

2026-10-23: Super Alarm iOS 1.5.1 (14) resubmitted after the 4.5.4 rejection of build 13 (notifications now optional). Submission c4c82e53-9047-4453-b55e-0c9cea7b6736 is WAITING_FOR_REVIEW. Build id c4ca7076-8385-4df0-a04a-876e599d9549.

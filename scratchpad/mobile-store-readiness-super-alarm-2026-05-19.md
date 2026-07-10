# Super Alarm mobile store readiness - 2026-05-19

## P0 Blockers

- None remaining in repo/build artifacts.

## External Submission Blockers

- [Play credentials] `GOOGLE_APPLICATION_CREDENTIALS` must point to the Google Play service account JSON on the runner before `mobile-store-submit` can use the Publishing API.
- [Live URLs] Play Console needs a live privacy policy URL and support contact/URL. Draft copy exists in `docs/privacy-policy.md`, but the final URL/contact must be provided or deployed.
- [Play Console declarations] Exact alarm and full-screen intent declarations must be completed in Play Console. Draft justification exists in `docs/google-play-permissions.md`.
- [Play Console setup] Confirm Play app `com.superalarm.app` exists, Play App Signing is enrolled, and version code `1` is unused.

## P1 High Risk

- [Android-only] Repo is Android-only for submission now; no `ios/` directory or iOS bundle ID was found.
- [Store assets] Screenshots and feature graphic are still missing from the repo.
- [Lock-screen privacy] Alarm notification uses public lock-screen visibility with generic content. This appears intentional for an alarm app; mention in review notes if asked.

## P2 Polish

- [Store listing] Draft listing copy exists in `docs/store-listing.md`.
- [Data Safety] Draft Data Safety source exists in `docs/play-data-safety.csv`; verify final answers against actual vendor/backend behavior before console submission.
- [Permission copy] Camera prompt is specific and matches QR-dismiss behavior.

## Fixed This Pass

- Added `build:android:aab:release` script.
- Added Google Play AAB output to `scratchpad/mobile-submission-packet.yaml`.
- Added `docs/privacy-policy.md`.
- Added `docs/play-data-safety.csv`.
- Added `docs/google-play-permissions.md`.
- Added `docs/store-listing.md`.
- Updated `README.md` with AAB release lane and store-readiness inputs.
- Installed Bun on the remote runner so existing tests can run.

## Validation

- `pnpm install`: already up to date.
- `pnpm typecheck`: passed.
- `pnpm test`: passed, 5 tests.
- `NODE_ENV=production pnpm build:android:aab:release`: passed.
- AAB artifact: `android/app/build/outputs/bundle/release/app-release.aab`, 55 MB.
- AAB signing verification: `jarsigner -verify` passed.
- Build config: compile SDK 36, target SDK 36, min SDK 24.

## Submission Packet

- Path: `scratchpad/mobile-submission-packet.yaml`
- Status: `ready_warn`
- Store: Android / Google Play
- Target: open testing
- Artifact: `android/app/build/outputs/bundle/release/app-release.aab`
- Demo account: none required

Use `mobile-store-submit` after the external submission blockers are satisfied.

## Evidence Checked

- `app.json`
- `package.json`
- `README.md`
- `android/app/src/main/AndroidManifest.xml`
- `android/app/build.gradle`
- `android/gradle.properties`, `android/build.gradle`, `android/settings.gradle`
- `screens/AlarmTab.tsx`, `screens/AlarmRingingScreen.tsx`, `lib/storage.ts`, `lib/alarm.ts`
- `AlarmModule.kt`, `AlarmScheduler.kt`, `AlarmRingingService.kt`, `AlarmBootReceiver.kt`
- Official docs: Google target API, Android App Bundle, exact alarm/full-screen intent policy, Apple review background mode guidance.

## Updated After Website/Screenshots

- Live privacy URL: https://super-alarm-legal.msbilal.workers.dev/privacy
- Live support URL: https://super-alarm-legal.msbilal.workers.dev/support
- Cloudflare Worker: `workers/super-alarm-legal`
- Worker version: `29501d0e-93fd-4445-81e9-92428713f6a7`
- Screenshots copied to `store/screenshots/android/phone`.
- Packet updated with live URLs, support contact, and screenshots path.

## Updated After Google Service Account

- Created Google Cloud service account: `super-alarm-play-submit@track-494517.iam.gserviceaccount.com`
- Enabled Android Publisher API on project `track-494517`.
- Installed service account JSON on runner at `~/.config/mobile-store-submit/google-play-service-account.json` with mode `600`.
- Wired `GOOGLE_APPLICATION_CREDENTIALS` through `~/.config/mobile-store-submit/env.zsh`.
- Remaining action: grant this service account Play Console access for `com.superalarm.app`.

## Updated After Publishing API Dry Run

- General service account in use: `mobile-app-publisher@collabez-mobile-pub.iam.gserviceaccount.com`.
- Service account is stored in 1Password vault `Mobile App Releases` as `Google Play Publishing Service Account`.
- Runner credential path is wired through `GOOGLE_APPLICATION_CREDENTIALS`.
- Play API edit creation passed for `com.superalarm.app`.
- Play API edit validation passed.
- AAB upload to an uncommitted edit passed.
- Uploaded dry-run bundle version code: `1`.
- Bundle SHA-1: `a8941015161f3b854229b6254dc27927648b40f0`.
- Bundle SHA-256: `d42e1fceeb2e8d7161b7c94a77794eaac1f8861cd0a444f1aff1dd84023c3d09`.
- API track note: Play open testing maps to API track `beta`.

Remaining before final submit:

- Complete exact alarm declaration in Play Console.
- Complete full-screen intent declaration in Play Console.
## 2026-05-20 iOS readiness update

- Added iOS alarm scheduling through `expo-notifications` weekly local notifications. iOS cannot present Android-style full-screen exact alarms; users must tap the delivered notification to enter the QR dismissal flow.
- Added iOS notification permission handling, tap-response routing, pending notification cleanup, and iOS-specific alarm reliability copy.
- Removed the stale iOS background audio mode and microphone usage string from the native plist; current iOS permissions are camera and notifications.
- Verified `pnpm typecheck` and `pnpm test` pass.
- Verified `pod install` includes `EXNotifications`.
- Release archive compiles, bundles JavaScript, and reaches the final CocoaPods framework embed/signing phase.
- Resolved signing by importing the stored Super Alarm distribution `.p12` into a dedicated temporary build keychain and passing that keychain to `codesign`.
- `xcodebuild archive` succeeded and `xcodebuild -exportArchive` produced `build/ios/export/SuperAlarm.ipa`.
- IPA inspection confirmed bundle id `com.superalarm.app`, camera and notification usage descriptions are present, and stale `UIBackgroundModes` / `NSMicrophoneUsageDescription` keys are absent.

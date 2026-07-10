
## 2026-05-19 - Setup guidance clarity

When guiding manual store setup, give exact console paths, button names, and ordering. Avoid broad phrases like "complete declarations" without saying where to click and what value/text to enter.

2026-05-19: Android Publisher API commit URLs in zsh must use braces: `/edits/${EDIT_ID}:commit`; `$EDIT_ID:commit` is parsed incorrectly.
2026-05-19: `edits:validate` is a dry-run path; do not call it before `edits:commit` for a real draft release.
2026-05-19: Service-account OAuth for Android Publisher needs explicit scope `https://www.googleapis.com/auth/androidpublisher`; a generic gcloud token can fail with `ACCESS_TOKEN_SCOPE_INSUFFICIENT`.
2026-05-19: Play Console country/region error for a draft open-testing release cannot be cleared with `countryTargeting`; Android Publisher API returns `Country targeting is only supported for staged releases.` Use Console track countries, or move beta release out of draft with explicit `countryTargeting`.
2026-05-19: Store listing full description can be fixed by `edits.listings.update` for `en-US` and committed independently from release edits.
2026-05-19: Play `applications.dataSafety` requires Google's exact exported CSV header/schema. A human-readable or custom readiness CSV fails with `Invalid header row. Download the sample CSV file to see an example.`
2026-05-19: Play contact details can be set via `edits.details.patch` with `defaultLanguage`, `contactWebsite`, and `contactEmail`, then committed through an edit.
2026-05-19: Do not default alarm apps to `18+` target audience. For general alarm/wake-up apps, teens can be a valid intended audience; recommend 13+/16+/18+ based on product/legal posture, not age-gate convenience.

## 2026-05-19 Super Alarm Play submission

- Android Publisher API requires `https://www.googleapis.com/auth/androidpublisher`; generic gcloud auth token can fail with insufficient scope.
- Use API for upload/edit/listing assets/details first; if app-content or dashboard setup blockers remain, switch to Play Console browser automation and finish them.
- First-time Play apps require a known sequence: metadata/contact/category, store listing assets, app-content declarations, release track countries, preview/confirm release, publishing overview send for review.
- Data Safety CSV import requires Google's exact exported/sample schema; custom CSV headers are rejected.
- For open testing first release, country targeting may need Play Console UI/browser automation rather than draft API country targeting.

## 2026-05-20 Super Alarm iOS submission prep

- App Store Connect app exists: app id `6770966337`, bundle `com.superalarm.app`, version `1.0` in `PREPARE_FOR_SUBMISSION`.
- First-version `appStoreVersionLocalizations.whatsNew` cannot be edited; omit `whatsNew` for first release metadata updates.
- App Review detail creation requires `contactPhone` in international `+country code` format.
- Expo iOS local path: set `ios.bundleIdentifier`, run `expo prebuild --platform ios --no-install`, then `pod install`.
- If Xcode has no account, use App Store Connect API to create an Apple Distribution cert and IOS_APP_STORE profile, install both on the runner, then archive with manual signing.
- Xcode 17 export method for App Store upload: `app-store-connect`.

## 2026-05-20 App Store Connect API learnings
- ES256 App Store Connect JWTs from Node must use P1363 signatures (`dsaEncoding: "ieee-p1363"`); DER signatures produce 401s.
- Age rating declaration is under `appInfos/{id}/ageRatingDeclaration`, not `appStoreVersions/{id}`. Patch `/v1/ageRatingDeclarations/{appInfoId}`.
- Primary category can be patched on `/v1/appInfos/{id}`; `UTILITIES` worked for Super Alarm.
- App content rights can be patched on `/v1/apps/{id}` with `contentRightsDeclaration: DOES_NOT_USE_THIRD_PARTY_CONTENT`.
- App pricing schedule inline create requires local IDs in the literal `${local-id}` format. `${free-us-now}` worked; `free-us-now` failed.
- Screenshot upload flow: create `appScreenshotSet`, create `appScreenshot`, PUT each `uploadOperation`, then PATCH screenshot with `uploaded: true` and MD5 checksum. Required screenshot types for current Super Alarm binary were `APP_IPHONE_65` and `APP_IPAD_PRO_3GEN_129`.
- Public App Store Connect OpenAPI does not expose `appDataUsages`; direct calls to `/v1/appDataUsages` and related app/appStoreVersion relationships returned 404. App Privacy answers need App Store Connect web/manual automation unless another supported API appears.
- Computer Use was blocked on this runner by `Apple event error -10000: Sender process is not authenticated`, so browser fallback could not be executed autonomously in this run.

## 2026-05-20 Super Alarm iOS signing workaround

- After the Mac mini reboot, login-keychain signing failed with `errSecInternalComponent` / keychain access prompts. Re-importing the saved distribution `.p12` into a temporary build keychain and passing `OTHER_CODE_SIGN_FLAGS="--keychain /Users/agents/Library/Keychains/super-alarm-build.keychain-db"` allowed archive/export to complete.
- The Expo-generated Xcode project had `CURRENT_PROJECT_VERSION = 2`, but `ios/SuperAlarm/Info.plist` also had a literal `CFBundleVersion`. Set the plist value too before archiving or the exported IPA can still report build `1`.
- Build `2` validated and uploaded with altool on 2026-05-20. Delivery UUID: `0267f059-22f4-4956-959c-bfa8f5d9e2ff`.
- To match Android’s icon, regenerate `ios/SuperAlarm/Images.xcassets/AppIcon.appiconset/App-Icon-1024x1024@1x.png` from `assets/adaptive-icon.png`, but flatten it to RGB with no alpha; App Store icon validation expects 1024x1024 and no alpha.
- Review submission item creation fails with an App Privacy blocker if App Store Connect data usage answers are unpublished: `Associated errors for /v1/appDataUsages/: You must have published answers to your app's data usages.` The public App Store Connect API cannot publish these; `asc web privacy ...` needs an authenticated Apple web session.

## 2026-05-20 Super Alarm iOS build 4 resubmission

- `expo-audio` can provide in-app alarm playback on iOS, including silent-mode playback while the app is foreground/active, but iOS still cannot run an arbitrary continuous alarm from a background local notification. Keep review notes honest: notification alerts the user, tapping opens the QR ringing flow, and the ringing screen plays the bundled tone.
- Adding `expo-audio` may reintroduce `NSMicrophoneUsageDescription` during Expo prebuild even when the app only plays audio. Remove the key from `ios/SuperAlarm/Info.plist` unless recording is actually used.
- Expo prebuild may also reintroduce `aps-environment` in `ios/SuperAlarm/SuperAlarm.entitlements`; remove it for the current App Store profile because Super Alarm only uses local notifications and the profile does not include Push Notifications.
- Build `4` was uploaded, validated, attached, and resubmitted for review on 2026-05-20. Delivery/build id: `be79f9b9-df54-4923-ba28-3393c707654c`; review submission: `a9f68fd6-c952-4741-9a60-52a47e2e07e8`.

## 2026-05-21 Super Alarm Android production submission
- Non-login SSH shells may expose `/usr/bin/java` without a usable runtime; use Android Studio JBR at `/Applications/Android Studio.app/Contents/jbr/Contents/Home` as `JAVA_HOME` for Gradle builds on this runner.
- Non-login SSH shells may also miss Node/pnpm; include `/Users/agents/.nvm/versions/node/v25.8.2/bin` or `/opt/homebrew/bin` in `PATH` for Expo/React Native Android release builds.
- Android Publisher direct API flow worked for production: create edit, upload AAB with `uploadType=media`, update `listings/en-US`, update `tracks/production`, then commit using `/edits/${EDIT_ID}:commit`.

## 2026-05-21 Super Alarm iOS build 5 submission
- If the saved distribution p12 password is unavailable, importing the saved distribution.cer plus distribution.key into a fresh build keychain and setting the apple-tool/apple/codesign partition list works for Xcode framework signing.
- Xcode/altool upload delivery UUID matched the App Store Connect build id for build 5: e9a2f4b7-b005-472b-a16e-0abcf6d07481.
- Current App Store Connect review-submission API supports replacing a waiting build by PATCHing reviewSubmissions with canceled=true, attaching the new build, creating a reviewSubmissionItem, then PATCHing the draft reviewSubmission with submitted=true.
2026-05-22T01:36:16Z - Google Play Android 15 BOOT_COMPLETED FGS warning: expo-audio library services can be removed from merged manifest with tools:node=remove for AudioControlsService and AudioRecordingService when app does not use Expo audio background controls/recording.
2026-05-22T01:42:55Z - Super Alarm Android: versionCode 4 uploaded to production as draft via Android Publisher API edit 14493050602822623736; use draft status to avoid auto-sending review when changesNotSentForReview is rejected.
2026-05-22T02:25:28Z - Super Alarm Android: production versionCode 4 sent for review by updating production track status to completed and committing edit 04154113297436451307.

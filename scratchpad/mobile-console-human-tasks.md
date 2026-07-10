# Mobile Console Human Tasks

Last updated: 2026-05-24T10:24:09Z

## Apple Developer - Critical Alerts

Status: required before the Critical Alerts TestFlight build.

- Page: Apple Developer > Certificates, Identifiers & Profiles > Identifiers > `com.superalarm.app`
- Fallback path: developer.apple.com/account > Certificates, IDs & Profiles > Identifiers > App IDs > `com.superalarm.app`
- Field/control: Capability checkbox / entitlement request for Critical Alerts
- Exact value/action: request/enable `Critical Alerts` for Super Alarm. Use the alarm app justification: user-configured wake alarms with QR-code dismissal; without high-priority audible delivery, the core alarm use case is unreliable.
- Then: regenerate the App Store/TestFlight provisioning profile named `Super Alarm App Store` or create a replacement profile that includes `com.apple.developer.usernotifications.critical-alerts`.
- Confirmation: a newly signed archive shows `com.apple.developer.usernotifications.critical-alerts` in `codesign -d --entitlements :- ...` output.

## App Store Connect - TestFlight

Status: pending Critical Alerts profile and build 7 export.

- Page: App Store Connect > Apps > The Super Alarm > TestFlight
- Fallback path: App Store Connect > My Apps > The Super Alarm > TestFlight > iOS Builds
- Field/control: build upload/processing status
- Exact value/action: upload native iOS build `7` after the Critical Alerts profile is active. Use internal TestFlight first.
- Internal tester group: use existing preferred testers from private memory if available: `msbilal@gmail.com`, `zainahsan337@gmail.com`, `shoaib@collabez.ae`, `rajamuhammadzohaib00@gmail.com`.
- Confirmation: build `7` appears in TestFlight and is available to internal testers.

## Google Play Console - Open Testing

Status: uploaded to internal testing and open testing through the Publishing API.

- Page: Play Console > Super Alarm > Testing > Open testing
- Fallback path: Play Console > Select Super Alarm > Test and release > Testing > Open testing
- Field/control: release track
- Exact value/action: already committed AAB `android/app/build/outputs/bundle/release/app-release.aab`, versionCode `5`, status `completed` to internal testing and open testing (`beta`).
- Sensitive permissions declarations: verify `USE_EXACT_ALARM` and `USE_FULL_SCREEN_INTENT` remain completed for the native app. Core use case is alarm; review instructions should match `docs/google-play-permissions.md`.
- Confirmation: internal testing and open testing tracks show release `1.0.0 (5) native`. Tester opt-in URL: https://play.google.com/apps/testing/com.superalarm.app . Store URL after opt-in: https://play.google.com/store/apps/details?id=com.superalarm.app .

## Native QA Before Gate B

- Control type: manual device checklist
- Exact action: verify Android lock-screen/full-screen alarm launch, exact alarm permission, full-screen intent permission, notification permission, camera QR scan, dismissal stop behavior, boot/package-replaced restore, and diagnostics export.
- Exact action: verify iOS notification permission, Critical Alerts status after signed-entitlement build, notification tap into QR dismissal, in-app alarm audio, camera QR scan, diagnostics export, and fallback copy for non-Critical Alerts builds.

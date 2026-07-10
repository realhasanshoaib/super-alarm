# Mobile Submission Learnings

## 2026-05-24T08:39:59Z

- For Super Alarm, `asc`/`gplay` binaries were not present, but Apple state can be verified with `xcrun altool --list-apps` and Google Play state can be verified directly through Android Publisher API using `GOOGLE_APPLICATION_CREDENTIALS`.
- Do not assume local artifacts are the submitted artifacts after a native migration; compare packet hashes, archive build numbers, and store track/build state before re-uploading.

## 2026-05-24T10:24:09Z

- For native Super Alarm, Play open testing should use packet track `open` but Publisher API track `beta`.
- App Store Connect had already seen iOS build `6`; the next native TestFlight upload must be build `7` or higher.
- The Xcode entitlements file alone is not enough for Critical Alerts; verify the signed app entitlements after regenerating the provisioning profile.

## 2026-05-24T10:31:06Z

- App Store Connect API bundle capabilities do not expose Critical Alerts for Super Alarm; attempts with `CRITICAL_ALERTS` and `CRITICAL_ALERT` returned unsupported capability type. Treat Critical Alerts as Apple special-entitlement/manual approval before profile regeneration.
- For Super Alarm Android native testing, versionCode 5 is committed to both `internal` and `beta` tracks. The tester opt-in URL format is `https://play.google.com/apps/testing/com.superalarm.app`.

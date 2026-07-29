# Super Alarm

Native iOS and Android alarm app. Android is Kotlin/Jetpack Compose with a native alarm service for full-screen ringing, backup re-rings, boot restore, reliability checks, and local diagnostics. iOS is SwiftUI with local notifications, Critical Alerts support when approved and provisioned, in-app alarm audio, and local diagnostics. Both apps support QR or fully on-device object-recognition dismissal.

Object enrollment uses a short guided sweep that captures varied views automatically. Android stores quantized MobileNetV3 embeddings; iOS stores revision-pinned Apple Vision feature prints. Neither platform stores camera images or uploads scan data, and QR spots remain available as a separate dismissal method.

## Native Development

Android:

```sh
cd android
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew testDebugUnitTest assembleDebug
```

iOS:

```sh
xcodebuild -workspace ios/SuperAlarm.xcworkspace -scheme SuperAlarm -configuration Debug -destination "generic/platform=iOS Simulator" build
```

## Android Release Signing

Release signing uses an ignored local file at `android/super-alarm-signing.properties`.

Expected keys:

```properties
SUPER_ALARM_UPLOAD_STORE_FILE=keystores/super-alarm-release.keystore
SUPER_ALARM_UPLOAD_STORE_PASSWORD=...
SUPER_ALARM_UPLOAD_KEY_ALIAS=super-alarm-release
SUPER_ALARM_UPLOAD_KEY_PASSWORD=...
```

## Android Release Builds

APK:

```sh
cd android
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew assembleRelease
```

AAB:

```sh
cd android
JAVA_HOME=/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home ./gradlew bundleRelease
```

## iOS Release

The iOS app uses bundle ID `com.superalarm.app`. Critical Alerts require Apple approval and provisioning profiles that include `com.apple.developer.usernotifications.critical-alerts`; see `ios/CRITICAL_ALERTS.md`.

```sh
xcodebuild -workspace ios/SuperAlarm.xcworkspace -scheme SuperAlarm -configuration Release -destination generic/platform=iOS -archivePath build/ios/SuperAlarm.xcarchive archive
xcodebuild -exportArchive -archivePath build/ios/SuperAlarm.xcarchive -exportPath build/ios/export -exportOptionsPlist build/ios/ExportOptions.plist
```

## Store Readiness Inputs

- Privacy policy draft: `docs/privacy-policy.md`
- Play Data Safety draft: `docs/play-data-safety.csv`
- Permission declarations: `docs/google-play-permissions.md`
- Store listing draft: `docs/store-listing.md`
- Native migration spec: `docs/native-mobile-migration-spec.md`

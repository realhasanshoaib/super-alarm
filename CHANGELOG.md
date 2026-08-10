# Changelog

## Unreleased

### Android

- Made locked-screen alarms more reliable across Vivo, Samsung, Xiaomi, OPPO, OnePlus, Huawei, and other Android phones by removing a blocked background launch path and adding device-specific setup guidance.
- Restored alarms after locked reboots, app updates, clock or timezone changes, and exact-alarm permission changes.
- Kept alarm audio awake while ringing, detected muted alarm notification channels, and fixed backup rings that were canceled when the next alarm was scheduled.

### Google Play

- Added a branded eight-image phone screenshot set built from the current app captures, with truthful feature headlines and an upload-ready 1080x1920 opaque PNG format.
- Reworked the Play listing title, short description, full description, and feature graphic around scan-to-wake alarm intent and the current Android feature set.

### iOS

- Rethemed the app around the logo's navy blue with an adaptive accent color, replacing the orange tint across the alarm editor, dial, weekday picker, onboarding, and scanners.
- Adopted Liquid Glass on iOS 26 (glass dial, cards, and button styles) with material fallbacks on earlier releases.
- Redesigned the ringing screen as a night sky in the logo's navy with a subtle star field and glass scan buttons.
- Replaced the placeholder launch logo with the real Super Alarm logo on an off-white launch screen matching Android's splash, and added the logo to Settings and onboarding so the app is identifiable in-app.
- Styled Settings with iOS-style icon tiles that carry the logo's navy, orange, and green as small accents.
- Added an interactive AlarmKit handoff preview covering the system alert, scan-to-dismiss action, QR and object scanners, valid dismissal, and the system Stop escape hatch.
- Added main-actor isolation for UI state and lightweight SwiftUI Canvas previews for the alarm editor and onboarding screens.

## 1.4.0 — 2026-07-30

### Android and iOS

- Replaced the short first-run prompt with a guided six-step setup covering dismissal, alarm time, repeat days, and phone readiness.
- Added contextual alarm-critical permission checks before setup can continue.
- Added a real scheduled wake-up test that must ring and be dismissed with the chosen object or QR spot before onboarding can finish.
- Preserved onboarding progress so interrupted setup resumes at the right step.

## 1.3.0 — 2026-07-30

### Android

- Added a responsive Super Alarm home-screen widget with compact next-alarm status, a detailed 2×2 layout, and 5h/6h/8h sleep shortcuts in the wide layout.
- Made object enrollment learn both ambient and phone-lit views, added a controlled-light dismissal fallback, and reduced scanner work on entry-level Android phones.

## 1.2.1 — 2026-07-29

### Android and iOS

- Added printer-free alarm dismissal by recognizing an enrolled household object fully on device.
- Added a Face ID-style guided sweep that captures useful views automatically with live framing, lighting, glare, blur, progress, and movement feedback.
- Added local-only visual fingerprint storage and fast rolling-frame verification during dismissal.
- Kept QR spots available alongside object targets, with independent allow and delete controls.
- Added native Apple Vision feature-print matching on iOS and optimized MediaPipe MobileNetV3 live-stream matching on Android.

### Android

- Added private on-device object preview thumbnails, compact two-column object cards, and a rescan action for objects enrolled before previews were available.
- Object previews now select the sharpest, best-exposed frame from the completed scan instead of keeping its first frame.
- Tightened the alarm header, aligned control rows, normalized section typography, kept scanner controls clear of system bars, and made Android Back close scanners.
- Replaced raw recent-attempt records with readable alarm results, local times, and dismissal-target names.
- Restricted alarm restore broadcasts to Android system delivery.

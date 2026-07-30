# Changelog

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

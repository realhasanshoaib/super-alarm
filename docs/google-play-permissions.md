# Google Play permission declarations - Super Alarm

## Exact alarm

Core use case: alarm app. Super Alarm schedules precise user-configured wake alarms and backup re-rings. Without exact alarm behavior, the core product is broken.

Evidence: `android/app/src/main/java/com/superalarm/app/alarm/AlarmScheduler.kt` uses `setAlarmClock` for the main alarm and `setExactAndAllowWhileIdle` for backup alarms. `android/app/src/main/java/com/superalarm/app/ui/SuperAlarmApp.kt` exposes the exact-alarm capability and system setting.

## Full-screen intent

Core use case: high-priority alarm. Super Alarm needs to wake the screen and show the ringing/dismissal flow when an alarm fires.

Evidence: `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt` builds an alarm-category notification with `setFullScreenIntent`; `android/app/src/main/java/com/superalarm/app/MainActivity.kt` applies lock-screen and screen-on behavior.

## Camera

Used only after the user opens QR dismissal. It scans QR codes for approved locations. Frames are not stored or uploaded.

Evidence: `android/app/src/main/java/com/superalarm/app/features/qr/QrScannerView.kt` binds CameraX and ML Kit only inside the scan-to-dismiss flow in `android/app/src/main/java/com/superalarm/app/ui/SuperAlarmApp.kt`.

## Foreground service: media playback

Declaration selection: Media playback.

Play Console submission also requires a public video link demonstrating this flow. Record an alarm starting while the app is not being actively used, the audible ringing state with its ongoing notification, and successful dismissal that stops playback.

The foreground service starts only for a user-configured alarm or the user-invoked Test Alarm flow. It plays audible alarm media, vibrates when enabled, and displays an ongoing alarm-category notification while the alarm is ringing. Scanning an approved QR code stops the service.

Evidence: `android/app/src/main/AndroidManifest.xml` declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and the `mediaPlayback` service type. `android/app/src/main/java/com/superalarm/app/alarm/AlarmReceiver.kt` starts the service when an alarm fires. `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt` starts foreground playback and creates the visible notification. `android/app/src/main/java/com/superalarm/app/data/SuperAlarmStore.kt` stops the service after valid QR dismissal.

## Notifications / boot / wake lock / vibration

Used for reliable local alarm ringing, backup rings, restart restore, and alerting.

Evidence: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt`, and `android/app/src/main/java/com/superalarm/app/alarm/AlarmBootReceiver.kt`.

## Device administration

Super Alarm does not request Device Administrator access and does not attempt to prevent or complicate uninstallation.

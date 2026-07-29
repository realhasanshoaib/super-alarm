# Google Play permission declarations - Super Alarm

## Exact alarm

Core use case: alarm app. Super Alarm schedules precise user-configured wake alarms and backup re-rings. Without exact alarm behavior, the core product is broken.

Evidence: `android/app/src/main/java/com/superalarm/app/alarm/AlarmScheduler.kt` uses `setAlarmClock` for the main alarm and `setExactAndAllowWhileIdle` for backup alarms. `android/app/src/main/java/com/superalarm/app/ui/SuperAlarmApp.kt` exposes the exact-alarm capability and system setting.

## Full-screen intent

Core use case: high-priority alarm. Super Alarm needs to wake the screen and show the ringing/dismissal flow when an alarm fires.

Evidence: `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt` builds an alarm-category notification with `setFullScreenIntent`; `android/app/src/main/java/com/superalarm/app/MainActivity.kt` applies lock-screen and screen-on behavior.

## Camera

Used only after the user opens QR dismissal or Android object enrollment/dismissal. QR frames are checked for approved locations; object frames are converted on device into quantized visual fingerprints for later matching. Android keeps one user-visible representative object thumbnail in private app storage. All other frames are discarded, and no camera image or video is uploaded.

Evidence: `android/app/src/main/java/com/superalarm/app/features/qr/QrScannerView.kt` binds CameraX and ML Kit inside the QR flow. `android/app/src/main/java/com/superalarm/app/features/objectscan/ObjectCameraView.kt` binds CameraX and MediaPipe inside object enrollment and dismissal, which are presented by `android/app/src/main/java/com/superalarm/app/ui/SuperAlarmApp.kt`.

## Foreground service: media playback

Declaration selection: Media playback.

The declaration was submitted on 11 July 2026 with the public proof video at <https://youtube.com/shorts/EZVvp7KBpHg?feature=share>. The local evidence clip is `store/media/google-play/foreground-service-media-playback-demo-vc6-20260711.mp4` (SHA-256 `2caf796839fdefe943b74788d109572b33f3e496a4c886f788bd511b5db859fc`). It shows the ringing UI and ongoing foreground-service notification. After managed publication, Play Console lists Foreground service permissions under **Actioned**, shows no policy issues, and has no pending publishing changes.

The foreground service starts only for a user-configured alarm or the user-invoked Test Alarm flow. It plays audible alarm media, vibrates when enabled, and displays an ongoing alarm-category notification while the alarm is ringing. Scanning an approved QR code or recognizing an allowed object stops the service.

Evidence: `android/app/src/main/AndroidManifest.xml` declares `FOREGROUND_SERVICE_MEDIA_PLAYBACK` and the `mediaPlayback` service type. `android/app/src/main/java/com/superalarm/app/alarm/AlarmReceiver.kt` starts the service when an alarm fires. `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt` starts foreground playback and creates the visible notification. `android/app/src/main/java/com/superalarm/app/data/SuperAlarmStore.kt` stops the service after valid QR or object dismissal.

## Notifications / boot / wake lock / vibration

Used for reliable local alarm ringing, backup rings, restart restore, and alerting.

Evidence: `android/app/src/main/AndroidManifest.xml`, `android/app/src/main/java/com/superalarm/app/alarm/AlarmRingingService.kt`, and `android/app/src/main/java/com/superalarm/app/alarm/AlarmBootReceiver.kt`.

## Device administration

Super Alarm does not request Device Administrator access and does not attempt to prevent or complicate uninstallation.

# Google Play permission declarations - Super Alarm

## Exact alarm

Core use case: alarm app. Super Alarm schedules precise user-configured wake alarms and backup re-rings. Without exact alarm behavior, the core product is broken.

Evidence: `AlarmScheduler.kt` uses `setAlarmClock` for the main alarm and `setExactAndAllowWhileIdle` for backup alarms. UI exposes exact alarm capability/settings in `AlarmTab.tsx`.

## Full-screen intent

Core use case: high-priority alarm. Super Alarm needs to wake the screen and show the ringing/dismissal flow when an alarm fires.

Evidence: `AlarmRingingService.kt` builds an alarm-category notification with `setFullScreenIntent`; `MainActivity` is configured with lock-screen/screen-on behavior.

## Camera

Used only after the user opens QR dismissal. It scans QR codes for approved locations. Frames are not stored or uploaded.

Evidence: `AlarmRingingScreen.tsx` opens `CameraView` only inside the scan-to-dismiss flow.

## Notifications / foreground service / boot / wake lock / vibration

Used for reliable local alarm ringing, backup rings, restart restore, and alerting.

Evidence: `AndroidManifest.xml`, `AlarmRingingService.kt`, `AlarmBootReceiver.kt`.

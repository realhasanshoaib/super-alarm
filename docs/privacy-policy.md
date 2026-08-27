# Super Alarm Privacy Policy

Last updated: 2026-08-27

Super Alarm is an alarm app that helps make dismissal intentional. Android and iOS can recognize an enrolled household object or scan a QR code. On Android, alarms can open a full-screen ringing flow. On iOS, scheduled notifications open the dismissal flow when you tap them.

## Data stored on device

Super Alarm stores alarm settings, QR location labels and payloads, enrolled object labels and visual fingerprints, reliability checklist state, recent alarm attempts, and local diagnostic events on your device using local app storage. Android fingerprints are quantized model embeddings; iOS fingerprints are Apple Vision feature prints. Android also stores one small representative thumbnail for each object so you can recognize it in the app. The app does not store camera video or any other scan frames.

## Camera

Super Alarm uses the camera to scan QR codes and to create and compare visual fingerprints for object-based alarm dismissal. Camera data is processed on device and is not uploaded or shared by Super Alarm. Except for the representative Android thumbnail described above, camera images and video are not stored.

## Notifications, alarms, and device permissions

Super Alarm uses notifications on iOS and Android. On Android, Super Alarm also uses exact alarms, full-screen alarm presentation, a foreground service, wake lock, vibration, boot restore, and audio settings so alarms can ring reliably at the scheduled time and after device restart.

## Diagnostics export

The app can export local diagnostics to a JSON file when you choose the export action. The export may include alarm settings, QR location labels and payloads, enrolled object labels and visual fingerprints, thumbnail file names, recent alarm attempts, reliability checklist state, and diagnostic events. Object thumbnail images are excluded. This file stays under your control and is not sent to Super Alarm automatically. If you choose to send the export to support, support will receive the contents of the file for troubleshooting.

## Product analytics

Super Alarm does not require an account and does not use ads, cross-app tracking, session replay, or user profiles. PostHog Cloud US receives an anonymous app-install identifier and a limited set of product events, such as screens viewed, onboarding progress, permission results, alarm scheduling outcomes, and dismissal outcomes. Events can include the app version, build number, platform, event source, and coarse device context. PostHog receives the network IP address and may derive approximate location such as country, region, or city from it. Super Alarm does not send alarm labels, QR payloads, camera frames, object fingerprints, thumbnails, or exported diagnostics to PostHog.

Analytics is enabled by default. You can turn it off or on in the app's Settings or Health screen. Turning analytics off stops new PostHog events from being sent from that installation.

## Crash and performance diagnostics

Sentry receives crashes, Android application-not-responding events, iOS hangs, handled operational failures, Worker errors, app version, build number, platform, device and operating-system context, and sampled performance traces. Default personally identifying data, screenshots, view hierarchies, replay, alarm content, and scan content are disabled. Sentry diagnostics are essential to operate and improve alarm reliability, so the analytics preference does not disable them.

PostHog processes analytics in the United States. Sentry processes diagnostics in its European region. Cloudflare hosts the marketing and legal site and retains sampled operational request logs for security and reliability. Data is encrypted in transit. We retain provider data only while it is useful for product reliability and remove it under the provider retention controls.

## Deleting data

Deleting an enrolled Android object also deletes its thumbnail. You can delete all app data by uninstalling Super Alarm or clearing the app storage from Android system settings. Android app backup is disabled for Super Alarm. Exported diagnostics files can be deleted from your device by you.

## Contact

For privacy questions or a deletion request, contact hhushhas@gmail.com. Because Super Alarm has no account, we can delete provider data only when it can be located from the device, app version, approximate event time, and other details you provide.

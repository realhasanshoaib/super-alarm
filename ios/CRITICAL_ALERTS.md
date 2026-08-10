# iOS Critical Alerts Notes

Super Alarm supports the iOS Critical Alerts authorization path in code, but the App Store build does not include the entitlement until Apple approves it. The shipped fallback uses standard local notifications.

Operational notes:

- Bundle id remains `com.superalarm.app`.
- Apple must approve Critical Alerts for the app before the entitlement is added to an App Store build.
- Provisioning profiles must include `com.apple.developer.usernotifications.critical-alerts`; otherwise device/archive signing can fail or the runtime entitlement check will report missing.
- The current release has no Critical Alerts entitlement, so the app schedules regular local notifications and surfaces the fallback state in Settings.
- Even with Critical Alerts, iOS delivery is system-owned. Super Alarm starts continuous in-app audio only after the user opens the notification/app.

Verification before iOS parity signoff:

- Confirm the entitlement is approved in Apple Developer.
- Regenerate development and distribution profiles for `com.superalarm.app`.
- Run a device build with the signed profile and verify Settings shows Critical Alerts as enabled after authorization.

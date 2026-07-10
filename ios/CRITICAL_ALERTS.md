# iOS Critical Alerts Notes

Super Alarm now includes the iOS Critical Alerts entitlement key in `SuperAlarm/SuperAlarm.entitlements` and requests `.criticalAlert` authorization only when the entitlement is present at runtime.

Operational notes:

- Bundle id remains `com.superalarm.app`.
- Apple must approve Critical Alerts for the app before App Store distribution can use the entitlement.
- Provisioning profiles must include `com.apple.developer.usernotifications.critical-alerts`; otherwise device/archive signing can fail or the runtime entitlement check will report missing.
- When the entitlement or user authorization is unavailable, the app schedules regular local notifications and surfaces the fallback state in Settings.
- Even with Critical Alerts, iOS delivery is system-owned. Super Alarm starts continuous in-app audio only after the user opens the notification/app.

Verification before iOS parity signoff:

- Confirm the entitlement is approved in Apple Developer.
- Regenerate development and distribution profiles for `com.superalarm.app`.
- Run a device build with the signed profile and verify Settings shows Critical Alerts as enabled after authorization.

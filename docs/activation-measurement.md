# Activation measurement

Activation means reaching a real scheduled wake-up challenge, not finishing a tutorial. A passed wake-up test is useful reliability evidence but is not required for activation. Churn is measured only after a user has activated.

## Cohorts

Use anonymous distinct IDs and production events only. Split Android and iOS, then split by app version and build number. Debug builds, internal test installs, and `alarm_kind = test` events do not count toward live alarm conversion.

The first observed `app_opened` is a first-open proxy, not a verified download or installation. Use its timestamp as the cohort entry time. Existing users first observed after a telemetry upgrade must not be called new installs; compare them as a separate returning-user cohort when earlier events exist.

For setup conversion, include only cohorts at least 24 hours old. For real alarm outcomes, allow seven days after first open and compare cohorts with the same observation window. A weekday-only alarm configured on Friday evening may not have had a chance to ring after 24 hours. Do not classify that user as a delivery failure.

## Ordered funnel

| Stage | Event and filter | Meaning |
| --- | --- | --- |
| First opened | First `app_opened` | The app was opened; this is the denominator proxy. |
| Target ready | `dismissal_target_ready` | At least one valid dismissal target is selected for the alarm. |
| Permissions ready | `alarm_permissions_ready` | The platform's essential permissions are ready. Optional reliability suggestions do not block this stage. |
| Live alarm scheduled | `alarm_scheduled`, `alarm_kind = live` | The operating system accepted the schedule. An enabled toggle alone is not sufficient. |
| Real ring opened | `ringing_started`, `alarm_kind = live` | The app entered its real ringing flow. On iOS, this does not prove notification delivery to a locked phone. |
| Real dismissal | `alarm_dismissed`, `alarm_kind = live` | The user completed the real alarm's dismissal flow. |

Count distinct users once per stage and require ordered timestamps within the same observation window. Readiness can be observed more than once, and schedule updates can emit more than once. Event totals are not user conversion counts. Keep an unordered readiness breakdown alongside the funnel so users who grant permissions before enrolling a target are not mistaken for drop-offs.

`alarm_fired` and `ringing_started` describe different platform boundaries. iOS cannot reliably report that a standard notification was heard while the app was not running; an open is observable, hearing is not. Never label a schedule-to-open loss as confirmed OS delivery failure.

## Diagnose losses

Break down phone-setup blockers by bounded `failure_code` and `permission_kind`. Separate notification permission, notification sound, exact-alarm access, full-screen access, missing target, missing repeat day, and scheduling failure where the platform reports them. Do not collect user labels, scan frames, object fingerprints, QR payloads, or free-form cancellation text.

Break down iOS enrollment cancellation by recorded reason, with unspecified cancellation kept as unknown. A dismissed camera sheet does not prove frustration or camera failure. Compare object enrollment with the QR fallback using distinct users, not scan attempt counts.

iOS emits `dismissal_enrollment_blocked` at most once per quality issue per enrollment session. Its bounded `failure_code` is `frame_too_dark`, `frame_clipped`, or `frame_low_detail`. These identify a temporary frame-quality blocker, not a failed enrollment; correlate with completion or cancellation. Images, feature prints, and object labels are never included.

Report optional tests separately: scheduled, started, passed, failed or cancelled, always with `alarm_kind = test`. Do not put these events between scheduling a live alarm and activation.

## Compare changes

On iOS, `alarm_disable_feedback` records an optional reason after a user manually turns an enabled alarm off. `disable_reason` is one of `did_not_wake_me`, `scanning_difficult`, `too_much_effort`, or `not_needed_now`; `alarm_kind` is `live`. Turning the alarm off does not depend on answering. The prompt respects the analytics preference and has a seven-day device-local cooldown, including when dismissed without an answer. No free text or object labels are collected.

Report the number of responses beside each reason distribution. These are self-selected responses from prompted users, not a representative churn survey; no answer is unknown, not “not needed.” Android does not yet emit this feedback event.

Ship one coherent change set at a time and compare equally aged cohorts from before and after the release. Report numerator and denominator beside each percentage. Use the new event contract only for builds that implement it; missing old events are unknown, not zero. Do not pool old and new scheduling telemetry into a conversion claim.

The first success measure is the share of first-open users reaching a real dismissal within seven days. Also report time to live scheduling, setup resume completion, schedule failures, and test-to-live separation. Measure repeat live dismissals in the following week among activated users to distinguish retention from setup abandonment. Small samples support directional learning, not a guaranteed churn reduction or a causal A/B-test claim.

These definitions are ready for the release dashboard. Production results require a shipped build and mature cohorts; local tests do not supply those results.

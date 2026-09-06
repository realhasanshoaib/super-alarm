# Android alarm path audit

- 2026-08-10 21:03 PKT: Started read-only trace of native Android alarm scheduling, delivery, UI, rescheduling, and manifest declarations for locked/dozing/killed-app reliability.
- 2026-08-10 21:09 PKT: Traced UI/store scheduling through AlarmManager, receiver/service handoff, full-screen Activity launch, backup cancellation, boot restore, and manifest capabilities. Main risks found: direct background `startActivity`, Doze backup spacing, unlock-gated reboot restore, missing exact-alarm-grant reschedule, alarm-clock show-intent reuse, and OEM/channel capability gaps.
- 2026-08-10 21:10 PKT: Read current Android guidance for background activity starts, exact-alarm permission broadcasts, idle alarm cadence, Direct Boot, and notification channels. No Android source files were changed and no build was run.

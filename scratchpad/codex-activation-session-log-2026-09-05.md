# Activation fixes session

## 2026-09-05: Scope and implementation decisions

User authorized fixing the reported activation issues and requested GPT-5.6 Sol high subagents. Root owns product decisions, integration, and verification. Existing favicon, deleted scratchpad images, workspace metadata, and untracked logs are unrelated and must remain untouched.

Desired behavior: three initial steps (dismissal target, alarm time, required phone permissions), resumable Finish later, persistent home readiness status for incomplete setup, optional post-activation wake-up test, honest iOS notification-first delivery copy, guided iOS object enrollment with QR fallback, and real/test-separated telemetry with successful scheduling events.

Critical Alerts require Apple approval and do not themselves provide continuous background app audio. Do not add an unapproved entitlement or claim Android parity. Preserve current OS support and dismissal algorithms. No store publication or external messages are included in this implementation pass.

Independent lanes: Android owns android/; iOS owns ios/. Each works in an isolated .worktrees checkout. Parent owns docs, cross-platform event contract, integration, remote checks, and final review. Worker commits are integrated only after their diffs are inspected.

Telemetry contract: alarm_kind is live or test on fired, ringing, dismissal and test events; alarm_scheduled denotes successful OS scheduling only; onboarding enablement emits alarm_enabled; phone-setup blockers and enrollment cancellation reasons are bounded categorical values, never raw camera content or user labels. Cohorts separate OS and exclude installs newer than 24 hours.

## 2026-09-05: Parallel implementation and verification preparation

Sol high workers own Android and iOS isolated branches. Root requested explicit dismissal_target_ready and alarm_permissions_ready events for the shared funnel, checked scheduler boundaries, and flagged asynchronous iOS reconciliation and Android silent permission failure to the owners.

Remote agents-macmini SSH failed with timeout at 100.85.19.43:22. Local fallback has Xcode, Java 17, Android SDK, adb, idb, Maestro, and ffmpeg. Created task-only iOS simulator AD3FA190-0FB2-4072-83B6-CBA349180AF1 and Android AVD super-alarm-activation-20260905; root owns cleanup. Existing simulators/AVDs remain untouched. AVD manager reported missing optional devices.xml; launch still needs verification with explicit user SDK root.

Root corrected iOS promise in store metadata drafts, listing documentation, and marketing/support source; no live store or website changes have been published. Store metadata parses and text fits existing subtitle/promotional/description limits; Worker JavaScript syntax check passed. Added docs/activation-measurement.md with production-only, platform/build-separated, distinct-user funnel and 24-hour setup/7-day real-alarm observation windows. Updated current onboarding requirements in telemetry documentation.

## 2026-09-05: Integrated builds

Inspected and integrated Android worker commit 1f0b732 as dba06b9 and iOS worker commit 7216781 as 630d631. Parent corrected Android Card import, current schedule readiness, home capability refresh, manual-home activation completion, iOS saved time-step resume, generation guard after scheduling, valid fingerprint targets, test scheduling telemetry, and cross-platform blocker names. Readiness events now account for permissions granted before target enrollment.

Android testDebugUnitTest + assembleDebug pass with 26 tests, zero failures/errors. Standalone Swift activation/migration checks pass. iOS simulator build passes for both simulator architectures. The first iOS build failed before compilation due to missing cached repositories; copied pinned existing SourcePackages into task-only build/activation-ios-packages-20260905 without changing package revisions, then built successfully. Original shared caches remain untouched.

Dedicated Android emulator emulator-5580 is running in exec session 81760. Android UI driver owns its interactions, root owns cleanup. Dedicated iOS simulator is booted with the integrated build installed. UI testing and final review remain outstanding; physical locked-phone sound and mature retention outcomes are not proven by simulator checks.

## 2026-09-05: Recovery and visual verification

Root integrated recovery/measurement corrections as 1f16f50. Sol high UI drivers exercised fresh setup, deferred setup, permission denial/recovery, optional test cancellation, and persistence on both platforms. Android final replay passes; root inspected video frames and confirmed identical OS live-alarm trigger times before tests, after cancellation, and after stopping a ringing test. iOS foreground permission revoke/restore correctly updates readiness immediately. Camera-denied scanner now has a Back route and tests have a Stop test action. Main alarm controls precede optional cards; iOS weekday labels stay on one line. Final short scanner toolbar labels retain descriptive accessibility labels and build successfully.

Android 26 tests and debug build pass; standalone Swift activation checks and final iOS simulator build pass. Physical camera matching and physical locked-phone delivery remain unproved. Store drafts and site source are not published. Drive upload is blocked by invalid_grant for the existing account; final verified videos remain in scratchpad/screenshots/activation-20260905. No unrelated Drive recordings were touched.

Removed clean Android/iOS implementation worktrees after integration, retaining their commits. Shut down and deleted the task-only Android AVD; existing devices remain untouched. One Sol high CLI review is running against base 1fc98a2 through snapshot 1f16f50 in the isolated review worktree.

## 2026-09-05: Review findings resolved

The single Sol high CLI review completed in 10m54.7s, exit 0. Both findings were verified against reachable callers: Android Back saves DismissalMethod but the resolver incorrectly advanced an existing target to Alarm; iOS cancellation removed pending requests but left already-delivered test notifications tappable. Preserved an explicitly selected dismissal step, added a regression test, and removed delivered notifications using only the test identifier. Also shortened scanner toolbar labels without losing accessibility descriptions.

Final Android testDebugUnitTest + assembleDebug pass with 27 tests, zero failures/errors; final iOS simulator build passes. Review token/cost instrumentation failed: codex_review_tokens=unavailable reason=expected_one_new_review_rollout found=0. The matching CLI session is recorded as thread_source=user with tokens_used=0 and has no token_count events, so exact input, cached input, cache-write input, output, reasoning output, total, turns, and API-equivalent cost are unavailable, not zero. Complaint #4871 records this tool failure. Review log: /tmp/super-alarm-activation-final.h3EnL0/review.log. The clean review worktree was removed after completion; no second review was run.

## 2026-09-05: Final handoff

Final commits 43f2206 and e75ae50 include review corrections and a durable iOS Cancel action that no longer disappears 60 seconds after delivery. The final iOS replay proved direct cancellation of a delivered, unopened notification older than one minute, removal from the lock screen, and preservation of the enabled live alarm. Root inspected the screenshots and normalized demo frames. No fake target dismissal was used.

Verified videos: android-final-activation-normalized.mp4, android-final-test-clean-normalized.mp4, and ios-final-clean-demo.mp4 under scratchpad/screenshots/activation-20260905. Persistent review/build evidence is under that directory's verification folder. Both platforms pass tested setup/recovery paths; physical scanning, physical overnight reliability, and retention improvement still require device testing and released cohorts. No push, deployment, or store release was performed.

Stopped task idb companion 13801 and shut down/deleted only the task iOS simulator. Moved task-only iOS build output and copied package cache (about 4.3 GB) to Trash for recovery if needed. Screenshots, normalized videos, source commits, and test logs remain available; original user changes and shared caches were preserved.

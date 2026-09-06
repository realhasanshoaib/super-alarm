# iOS scan recovery and UI consistency

## Scope and baseline

User reports camera preview working but object enrollment not progressing, endorses optional alarm-disable reasons, and requests theme-preserving iOS UI improvements plus screenshots of every screen. Root owns diagnosis, changes, and signoff. Existing favicon, deleted scratchpad images, workspace package metadata, and old untracked logs are unrelated and preserved. Starting commit e75ae50.

Read global standards, remote-testing instructions, orchestration guide, and dogfood skill with screens/notifications references. Remote agents-macmini connection timed out; no physical iPhone appears in devicectl. Root built the baseline successfully in task-only build/ios-ui-20260906, using a separate copy of pinned existing package sources. Sol high UI driver owns dedicated simulator D3C7AE0A-3BCB-4072-9D52-4EE92AAD67CC and before/after screenshots under scratchpad/screenshots/ios-ui-20260906. No store publication or external messages are authorized.

## Camera diagnosis and initial changes

Confirmed source-level mismatch: metadataOutputRectConverted returns unrotated metadata coordinates, while AVCaptureVideoDataOutput physically rotates its buffers. Corrected using outputRectConverted then normalizing its pixel coordinates. Previous quality loop sampled only a top-left square of a rectangular region; it now samples the whole region. Replaced mean-brightness-only rejection with clipped-pixel fraction; low edge detail is now explained as insufficient distinctive detail, not automatically called blur. Feature encoding and missing observations now surface errors rather than silently leaving enrollment at zero. Match thresholds are unchanged.

UI direction preserves the signature dial, blue/white theme, native cards, and night ringing screen. Moves schedule controls together, groups explanation in a disclosure row, standardizes sheet titles/action casing, makes Camera settings actionable and status truthful. Enrollment shows numeric saved-view progress and always has a QR fallback. Optional disable feedback is after disabling, skips analytics-opted-out users, is dismissible, and has a seven-day prompt cooldown. Physical camera success remains unverified until a real-device test.

## Integration and baseline visual findings

Committed initial changes as 7fe9535. New object targets record captureRegionVersion=2; existing targets retain their original crop pipeline during matching, avoiding an implicit invalidation of saved fingerprints. Pure capture quality/crop checks and activation/legacy-decoding checks pass. iOS simulator build passes for both architectures. One Sol high CLI review is running against e75ae50 in isolated .worktrees/ios-ui-review-20260906, log /tmp/super-alarm-ios-ui-review-20260906.log.

Root inspected baseline screenshots confirming low-contrast onboarding card text and overlapping camera recovery controls. Fixed those without changing the dial or core palette. Driver also found the onboarding time picker squeezing “Wake-up time” into a vertical column; root verified the image and source, moved the heading above the wheel, and retained its accessible label. Public app Settings URL opens Settings root on this simulator, so the error includes navigation instructions rather than promising a private deep link. Final screenshots and review resolution remain in progress.

## Resumed verification

After the user said continue, the temporary process handle and /tmp review log were no longer available. Recovered the completed review outcome: one P2, Camera Not requested opened Settings before a native permission request, leaving no Camera toggle. Root verified the branch and added requestCameraAccessOrOpenSettings. The final iOS build and pure activation/capture checks pass. The original wrapper exit and token accounting cannot be recovered; no second review was launched. A persistent review summary and final build log are in the screenshot verification folder.

Added per-session bounded frame-blocker events and preserved existing scan data with a versioned crop path. Filled camera/ringing actions now use the existing day-brand blue for readable white labels (calculated contrast 9.72:1 versus 2.92:1 on the previous sky fill). These were included in the final build.

Root inspected after images 01/02/04, 09, and 19–28. The clock and native styling are preserved, the time heading is readable, and the main controls are grouped. Flagged a mislabeled working screenshot29 showing Siri Settings instead of ringing; driver must replace it before final delivery. Gallery index.html is being assembled from verified captures; remaining test, feedback, and diagnostic states are pending.

## Screenshot pass recovery

Remote SSH timed out after continuation; the dedicated local simulator survived in Shutdown state and was booted successfully. Root confirmed the actual optional alarm-off prompt, captured30, dismissed it without answering, and confirmed the alarm remains off. Driver replaced29 with the real ringing screen; root inspected and included it. Root inspected the remaining initial gallery assets. Gallery JavaScript parses and all initial23 screenshot/before links resolve. Browser rendering is unverified: Chrome blocks local file URLs and the agent-browser fallback is broken; no workaround was attempted after the policy block. Complaints4891 and4892 record the concrete tooling failures. The UI driver resumed for remaining unique states.

## Handoff

Delivered25 selected screenshots in index.html plus an integrity-checked ios-screen-review.zip, with selected before links and explicit coverage gaps. Driver confirmed immediate re-enable/disable suppresses a repeat feedback prompt; the full seven-day elapsed boundary was not UI-tested. Root directly verified optional dismissal leaves the alarm off. Missing captures: QR scanner recovery, diagnostics, dark home/settings, first Camera permission request from Settings, and final-blue recapture04. Analytics-off suppression is source/component checked, not proven in this UI pass. Successful camera enrollment/recognition/dismissal still needs a physical iPhone. No App Store publication. Source commits remain7fe9535, a9ce85e,5cc9c8b; unrelated worktree edits preserved. UI driver stopped and confirmed no companion process remains. Dedicated simulator is being shut down, with its test state retained for follow-up.

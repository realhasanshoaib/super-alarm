# Super Alarm iOS 1.5.1 release report

Status: 1.5.1 (14) is WAITING_FOR_REVIEW (resubmitted 23 Oct 2026, 05:48 UTC). Android was not released.

## History

- 1.5.1 (13) was submitted on 18 Oct 2026 and rejected under Guideline 4.5.4 (review device iPad Air 11-inch M3). Apple wrote: "The app requires push notifications in order to function." Next steps: "Push notifications must be optional and must obtain the user's consent to be used within the app."
- Cause: onboarding step 3 disabled "Enable my alarm" until notifications were allowed, so setup could not finish without them.
- Fix (branch fix/optional-notifications): step 3 now has a "Not now" button. It finishes setup with the alarm off. The main screen shows a "Notifications are off" notice with "Allow notifications" or "Open Settings". Turning the alarm on still needs notifications, because iOS alarms ring through them. Android is untouched.
- Build 14 was signed with scripts/release/build-signed-ios.sh on the M4 and uploaded with altool (build id c4ca7076-8385-4df0-a04a-876e599d9549). Export compliance was set to false, as for build 13. Build 14 was attached to version 1.5.1, a reply was posted in the Resolution Center, and submission c4c82e53-9047-4453-b55e-0c9cea7b6736 was resubmitted.
- Simulator proof (fresh install, iOS 26, deny the system prompt, Not now, main screen): scratchpad/optional-notifications-proof-1.png and optional-notifications-proof-2.png.

## Not verified

Real-device behaviour, and whether Apple accepts the change.

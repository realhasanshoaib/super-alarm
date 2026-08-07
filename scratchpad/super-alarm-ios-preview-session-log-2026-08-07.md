# Super Alarm iOS preview session log

## 2026-08-07

- Inspected the repository and confirmed the iOS app is a native SwiftUI target with iOS 16.0 as its deployment target.
- Confirmed Xcode 26.6 is installed, but `xcrun simctl list runtimes` reports no installed iOS runtime and the scheme has no eligible Simulator destination.
- A generic Simulator build was not run because the environment safety hook blocked Xcode's internal build-output cleanup command. No project files were changed.
- Copied the iOS project to the isolated remote path `/tmp/super-alarm-ios-preview-20260807-1745` on `agents-macmini` and built it for iPhone 17 Pro / iOS 26.3. The build succeeded.
- Installed and launched the app on the remote simulator. The launch console reported `NSInternalInconsistencyException: Call must be made on main thread` while SwiftUI created a `NavigationStack`, and the app returned to the Home Screen.
- Shut down the simulator, removed the isolated remote checkout and build artifacts, verified the temporary path was gone, and moved the local diagnostic screenshot to Trash. No product files were changed.
- Added `@MainActor` isolation to `AppStore` so SwiftUI-observed state and navigation presentation stay on the main thread.
- Added DEBUG-only in-file SwiftUI previews for the alarm editor and onboarding flow, backed by deterministic mock alarm, QR, object, and notification-health data. This avoided adding a new source file or changing Xcode project membership.
- Rebuilt the changed project remotely successfully, launched it on the iPhone 17 Pro simulator, and captured the onboarding screen. The app stayed running after the main-actor fix.
- Shut down the simulator, removed the isolated remote checkout and build artifacts, verified the temporary path was gone, and moved the local verification screenshot to Trash.
- Opened `ios/SuperAlarm.xcworkspace` in Xcode, selected `AppViews.swift`, and enabled the SwiftUI Canvas. Xcode began downloading the official iOS 26.5 runtime required to render the Canvas; the preview pane remains in `Downloading iOS 26.5…` state after several minutes, with low CPU usage.

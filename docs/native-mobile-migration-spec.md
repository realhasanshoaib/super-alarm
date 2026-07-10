# Native Mobile Migration Spec

Date: 2026-05-23
Status: planning
Scope: replace the Expo/React Native app shell with native Android and iOS applications while preserving Super Alarm behavior.

## Product Intent

Super Alarm should feel like a serious platform-native alarm app, not a custom cross-platform skin. Android should use Kotlin and Jetpack Compose with Material 3 system conventions. iOS should use Swift and SwiftUI with Apple-native navigation, forms, sheets, toggles, pickers, notification prompts, and scanner presentation.

The migration is not a redesign for its own sake. The product behavior stays the same: configure a recurring morning alarm, require QR scanning at an enrolled wakeup spot before dismissal, keep local diagnostics, and avoid accounts or network services.

## Current App Inventory

The current app is an Expo/React Native project with app id/package `com.superalarm.app`.

Current TypeScript state shape:

- `AlarmConfig`: one morning alarm, label, enabled flag, local time in minutes, repeat days, sound id, vibration flag, backup offsets, allowed QR location ids.
- `QrLocation`: label plus `superalarm://alarm-qr/...` payload.
- `AlarmAttempt`: live/test attempt history with triggered/dismissed timestamps, result, backup marker, and dismissal location.
- `AlarmRuntimeState`: active attempt, active alarm, ringing timestamp, scanner state, backup stage, kind.
- `ReliabilityChecklistState`: notification, exact alarm, full-screen intent, battery optimization, MIUI autostart, MIUI lock screen confirmations.
- `AlarmAnalyticsState`: last export timestamp and diagnostic events.

Current screens and flows:

- First-run onboarding explains QR wakeup spots and opens the add-spot flow.
- Main alarm screen edits time, repeat days, enabled state, sound, vibration, QR spots, and allowed locations.
- QR location flow creates a spot, generates and shows a QR code, shares the payload, deletes spots, and toggles whether a spot can dismiss the alarm.
- Settings/health flow checks permissions, opens Android exact alarm/full-screen/battery settings, runs a routine test, exports local JSON diagnostics, and shows recent attempts/logs.
- Ringing flow displays a full-screen alarm UI, starts audio on iOS via Expo audio, relies on the native Android ringing service for Android audio, opens a QR scanner, rejects unknown or disallowed QR payloads, and dismisses only after a valid allowed scan.
- Dismissed flow shows location and streak, then auto-closes.

Current platform behavior:

- Android already has native Kotlin alarm scheduling, boot restore, pending launch storage, full-screen launch, foreground ringing service, alarm audio, vibration, alarm volume enforcement, and volume-key suppression.
- Android React Native currently acts as the UI/state orchestrator and bridges into `SuperAlarm` native module methods.
- iOS currently uses Expo local notifications only. Tapping a notification opens the QR dismissal flow. It does not have Android-style full-screen alarm launch or a background ringing service.
- Persistent app state is currently stored as JSON in Expo SQLite KV storage under `super-alarm:v1:state`. Android native alarm scheduling state also uses SharedPreferences for the scheduler and pending launch handoff.

## Decision

Build two native apps in the same repository, ship Android and iOS together, and remove React Native/Expo only after native parity is proven on both platforms.

- Android: keep and evolve the existing Kotlin package. Replace `MainActivity : ReactActivity` with a native `ComponentActivity` hosting Compose. Retain the existing `AlarmScheduler`, `AlarmReceiver`, `AlarmRingingService`, boot receiver, permissions, and package name.
- iOS: replace the generated Expo/RN app with a SwiftUI app target using bundle id `com.superalarm.app`. Implement native local notification scheduling, QR scanning, audio behavior, and JSON diagnostics with platform-appropriate limits.
- Do not introduce a cross-platform UI layer, Compose Multiplatform, or Kotlin Multiplatform in this migration. The point is platform-native UI primitives and platform-specific system behavior.
- Preserve the current app ids so store continuity, permissions, existing installs, and sandbox migration remain possible.
- Installed-user data migration is not mandatory for the first native release. Treat it as a best-effort nicety only when it does not slow or destabilize the native rebuild.
- Remove the current illustrative/generated UI assets for the native v1. Keep app icon, launch/splash requirements, native controls, typography, platform spacing, and a restrained accent color. Reintroduce illustrations later only when they serve a specific product moment.
- The existing React Native UI must not leak into the native rewrite. Preserve behavior, domain rules, copy only where it still fits, and alarm-critical flows; do not port the current visual layout, custom card system, ornamental backgrounds, oversized circular clock treatment, generated spot imagery, RN styling constants, animation patterns, or one-off visual components.

## Non-Goals

- No server, account, cloud backup, push notification backend, or analytics SDK.
- No attempt to make iOS behave exactly like Android full-screen alarms without Apple-approved capabilities. Because hard wakeup alarms are the core product purpose, Critical Alerts are now part of the iOS migration track, with a graceful fallback to regular local notifications until approval is granted.
- No new visual design system that fights the platforms. Brand can exist through app icon, tone, copy, and a small accent color, but controls should mostly be native.
- No dependency-heavy QR or charting package unless native platform APIs are insufficient.
- No mandatory upgrade migration for existing local alarm/QR/history data.
- No v1 dependency on the current generated illustrative backgrounds, spot images, or ornamental textures.
- No pixel-for-pixel or structure-for-structure recreation of the current RN screens. The native app should look like a fresh platform-native implementation of the same product, not a Compose/SwiftUI clone of the old UI.

## Native UI Direction

Android Compose:

- Use Material 3 components through `androidx.compose.material3`.
- Prefer `Scaffold`, `TopAppBar`, `NavigationBar` if tabs are introduced, `ListItem`, `Switch`, `TimePicker` or platform-appropriate time input, `ModalBottomSheet`, `AlertDialog`, `Card` only for repeated items, and `SnackbarHost` for transient feedback.
- Support dynamic color on Android 12+ when available, with a restrained Super Alarm fallback scheme.
- Support predictive back and standard Android gestures. Remove the current RN-era predictive back opt-out unless a native regression proves it necessary.
- Keep settings dense and scannable. Use grouped sections and native-looking rows instead of custom decorative cards.
- Ringing screen may be more custom because it is a critical, high-attention state, but must still use platform text sizing, safe areas, camera permission flows, and predictable actions.

iOS SwiftUI:

- Use `NavigationStack`, `Form`, `List`, `Section`, `Toggle`, `Picker`, `DatePicker` or a native time picker pattern, `.sheet`, `.confirmationDialog`, `.toolbar`, and system materials where appropriate.
- Use iOS grouped form/list behavior for settings and health checks.
- Use SwiftUI state bindings around a single app store object, with async services for scheduling, scanning, exporting, and audio.
- Ringing screen should be a full-screen SwiftUI cover from app launch/notification handling, using native camera scanner presentation.

Visual reference:

- Use `docs/assets/native-mobile-migration-ui-reference-2026-05-23.png` as the rough visual direction reference for native v1. It is not a pixel-perfect target; it captures the acceptable direction: clean native surfaces, restrained orange accent, minimal dark mode, iOS Liquid Glass-inspired materials, Android Material 3 neutrality, a refined signature alarm dial, and a very minimal ringing screen with allowed spots only.

Native interaction and accessibility quality bar:

- State changes must have instant visual feedback: Android ripples/pressed states, iOS highlighted rows/buttons, disabled/loading states, and optimistic local UI where safe.
- Use haptics intentionally for high-signal actions: alarm enabled/disabled, QR spot created, invalid scan, valid dismissal, and destructive confirmation. Respect system settings and avoid noisy repeated haptics during scanning.
- Keep transitions smooth and snappy. Use platform navigation transitions by default, short micro-interactions for sheets/dismissal states, and avoid heavyweight custom animation.
- Support standard gestures: Android back/predictive back, iOS swipe back, sheet drag-to-dismiss only where dismissal is safe, and native scroll behavior.
- Every interactive target must be at least 48 x 48 dp/pt-equivalent. Dense settings are fine, cramped taps are not.
- Accessibility is required: screen reader labels/hints, semantic headings, logical focus order, Dynamic Type/font scaling, sufficient color contrast, non-color-only status indicators, reduced motion respect, and usable QR/scanner fallback copy.
- Permissions should be contextual and progressive. Ask for camera, notifications, exact alarm/full-screen, and Critical Alerts when the user reaches the flow that needs them, with clear native pre-permission context where useful.
- Empty states must be clear and actionable: no QR spots, no recent attempts, no diagnostic events, notifications disabled, Critical Alerts unavailable, and no camera permission.
- Skeleton loaders or lightweight placeholders should appear only for real async waits. Avoid fake loading when local state is available instantly.
- Optimize for device adaptability: small/large phones, landscape-resistance if portrait-only, notches/islands/cutouts, tablets where supported, dark/light modes, Android dynamic color, and iOS accessibility sizes.
- Optimize speed and resources: minimal startup work, lazy scanner/camera initialization, no network permission by default, no ornamental image bundles in v1, and alarm-critical services kept small and reliable.

Anti-leakage rules for the native rewrite:

- Treat existing RN screens as behavior references only. They can answer "what can the user do?" but not "what should this look like?"
- Do not copy RN layout hierarchy, section composition, spacing scale, color palette, image usage, or bespoke component names into Compose/SwiftUI.
- Do not preserve the current dark blue/orange illustrative theme as the default native identity. Use platform theming first: Android dynamic color and Material 3; iOS system grouped surfaces and native accent behavior.
- Do not recreate decorative hero images, background textures, spot suggestion artwork, circular clock centerpiece, stat card grid styling, or custom modal cards unless a native platform convention independently calls for it.
- If a current phrase or label feels branded but non-native, rewrite it in platform-plain language. Keep product terms like alarm, QR spot, test alarm, diagnostics, and Critical Alerts.
- Reviewers should reject implementation that "looks like the old RN app but in native code" even if the behavior works.

## Shared Product Model

Each platform should define the same conceptual domain model, even if types are native:

- `AlarmConfig`
- `QrLocation`
- `AlarmAttempt`
- `AlarmRuntimeState`
- `ReliabilityChecklistState`
- `AlarmDiagnosticEvent`
- `AlarmAnalyticsState`
- `AppState`

Rules to preserve:

- The alarm cannot be enabled when `allowedLocationIds` is empty.
- Deleting the last allowed QR location disables the alarm.
- A scan dismisses only when the QR payload matches an enrolled location and that location id is allowed for the alarm.
- Unknown QR payloads and enrolled-but-disallowed payloads create diagnostic events and stay on the ringing flow.
- A live attempt counts in metrics; test attempts do not.
- On cold launch, any persisted `ringing` runtime should be marked `interrupted` and runtime should reset, matching the current defensive recovery behavior.
- Diagnostics export must remain local JSON and include alarm config, checklist, QR locations, attempts, diagnostic events, current platform capabilities, and runtime context.

## Storage And Migration

Preferred native storage after migration:

- Android: Room or DataStore for `AppState`, plus keep the existing SharedPreferences scheduler handoff if it remains the simplest alarm-service boundary. If Room is added, use it for queryable attempts/events only when needed; otherwise DataStore with JSON is enough for the current single-alarm app.
- iOS: a JSON file in Application Support or SwiftData if the app needs queryable histories. For this feature set, a Codable JSON store is enough and easier to migrate/debug.

Best-effort migration strategy:

- Keep `APP_STORAGE_KEY` as the logical migration key: `super-alarm:v1:state`.
- During native first launch, attempt to import the old Expo SQLite KV value and decode the existing JSON shape into native models.
- If direct Expo KV import is brittle, ship a transitional RN build first that writes the same JSON to a platform-neutral file path, then ship the native build that imports that file. This is the safest fallback because it avoids guessing Expo SQLite internals.
- After successful import, write native storage and set a native `migrationVersion` marker. Do not delete the old store until at least one successful native launch and reschedule has completed.
- Android must sync imported `AlarmConfig` into `AlarmStorage.saveAlarmConfig` and call `AlarmScheduler.scheduleNext` when the alarm is enabled and has allowed locations.
- iOS must reschedule all enabled repeat notifications after import.
- If migration becomes risky or slow, cut it from v1 and start native users from a clean local state. This is acceptable for the first native release.

## Android Architecture

Target modules/classes:

- `MainActivity`: convert to `ComponentActivity`, apply alarm lock-screen flags, block volume keys while `AlarmRingingService.isActive()`, and call `setContent { SuperAlarmApp(...) }`.
- `AlarmApplication` or existing `MainApplication`: remove React Native initialization after migration, keep any needed app startup hooks.
- `alarm/`: keep `AlarmScheduler`, `AlarmReceiver`, `AlarmBootReceiver`, `AlarmRingingService`, `AlarmStorage`, `AlarmConstants`, then remove `AlarmModule` and `AlarmPackage` when RN is gone.
- `data/`: native app store, JSON migration, attempt/event persistence, model serializers.
- `features/alarm/`: Compose main alarm editor and settings/health UI.
- `features/qr/`: QR code rendering and scanner.
- `features/ringing/`: full-screen ringing UI, scanner handoff, dismiss handling.

Android implementation details:

- Keep `AlarmManager.setAlarmClock` for the primary alarm occurrence and exact backup alarms for backup stages.
- Keep `SCHEDULE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT`, `POST_NOTIFICATIONS`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`, `VIBRATE`, `WAKE_LOCK`, `RECEIVE_BOOT_COMPLETED`, `CAMERA`, and `MODIFY_AUDIO_SETTINGS` if current behavior still requires them.
- Re-check exact alarm permission with `AlarmManager.canScheduleExactAlarms()` before scheduling, and surface a health row when unavailable.
- Re-check full-screen intent permission on Android 14+ and provide the settings deep link when available.
- Enable predictive back support with native AndroidX activity/navigation patterns and verify the back preview on main, settings, scanner, and modal flows.
- Use Material ripple/indication and correct enabled/pressed/loading/disabled states for all tappable list rows and buttons.
- Use dynamic color by default when available, with an app-accent fallback that still passes contrast.
- Keep boot restore on `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED`.
- Keep foreground service audio/vibration and volume enforcement, but add tests around stop/dismiss behavior if the service is edited.
- Use Activity Result APIs for notification/camera permission prompts and settings-result refresh.
- QR scanning: prefer CameraX + ML Kit Barcode Scanning if a custom in-app scanner is required. Use QR-only formats for speed. The Google Code Scanner API is lower permission friction but delegates UI, which is less suitable for the locked-in ringing flow.
- QR rendering: prefer a tiny, well-maintained QR encoder only if Android platform APIs are insufficient. If a dependency is added, validate maintenance/recency/adoption before adding it.

Android dependency posture:

- Compose Material 3 is official AndroidX and actively maintained. The docs list stable `androidx.compose.material3` 1.4.0 and alpha 1.5.0 as of May 2026.
- CameraX is official AndroidX and maintained. Prefer current stable CameraX unless the implementation needs a specific fixed bug from a newer RC/beta.
- ML Kit Barcode Scanning is Google-maintained, supports QR scanning on device, and offers bundled vs Play Services model tradeoffs. Prefer bundled for alarm-dismiss reliability unless app size becomes a release blocker.

## iOS Architecture

Target modules/types:

- `SuperAlarmApp`: SwiftUI `App` entry point.
- `AppStore`: `ObservableObject` or modern `@Observable` state owner for app state, derived metrics, and mutations.
- `AlarmNotificationService`: local notification authorization, scheduling, canceling, response handling, and launch handoff.
- `AlarmAudioService`: foreground ringing/test audio once the app is opened from a notification.
- `QrScannerView`: SwiftUI wrapper around a native scanner.
- `QrCodeView`: native QR image rendering with Core Image.
- `DiagnosticsExporter`: JSON export through share sheet.
- `MigrationService`: import old Expo/RN state where possible.

iOS implementation details:

- Use `UNUserNotificationCenter` with recurring `UNCalendarNotificationTrigger` entries for each repeat day.
- Request the Critical Alerts entitlement (`com.apple.developer.usernotifications.critical-alerts`) and, once approved, request `.criticalAlert` authorization in addition to alert and sound authorization.
- For enabled alarms on entitled builds, schedule notifications with critical interruption level and critical sound/volume so the alarm can bypass Silent mode and Focus/Do Not Disturb. Apple documentation says Critical Alerts can play sound when locked, muted, or in Do Not Disturb, and require the special entitlement.
- Preserve a fallback path: notification tap creates/opens the QR dismissal flow; the system owns notification delivery when the app is not running.
- Use a custom notification sound only if it fits App Store rules and sound duration limits; otherwise use system sound and start app audio after the user opens the app.
- Use `AVAudioSession.Category.playback` for in-app ringing/test audio so sound can play with the Silent switch set to silent once the app is active.
- Use VisionKit `DataScannerViewController` for QR scanning on supported iOS versions. If supporting older versions without DataScanner availability, fall back to AVFoundation metadata scanning.
- Use Core Image `CIQRCodeGenerator` for QR code rendering to avoid a QR dependency.
- Use `UIActivityViewController` from SwiftUI for diagnostics export.
- Use native haptics through UIKit/Core Haptics wrappers where appropriate, with simple impact/notification feedback preferred over custom haptic patterns.
- Preserve iOS standard gestures and SwiftUI navigation affordances rather than custom gesture systems.

iOS entitlement and limitation posture:

- Critical Alerts are a baseline iOS migration goal, not a someday enhancement. Prepare the entitlement request as part of Phase 3 and block final iOS parity signoff on either approval or an explicit release decision to ship a fallback build first.
- Local notifications are still delivered by the system. Even with Critical Alerts, the app should not promise Android-style continuous background service behavior; the strongest reliable promise is critical notification sound plus opening the QR dismissal flow when the user engages.
- Product copy should distinguish between "Critical Alerts enabled" and "standard notification fallback" so users know whether iOS wakeup reliability is fully configured.

## Feature Parity Checklist

Must preserve before RN removal:

- First-run onboarding opens QR spot creation.
- Create QR location with generated payload.
- Render and share QR code/payload.
- Delete QR location and disable alarm if no allowed location remains.
- Toggle allowed locations.
- Edit alarm time and repeat days.
- Toggle alarm enabled with guard for no QR locations.
- Choose sound id where platform supports it.
- Toggle vibration on Android; represent iOS limitation clearly if vibration is not equivalent.
- Android exact alarm, full-screen, notification, battery optimization, MIUI checklist rows.
- iOS notification permission and scheduling status row.
- iOS Critical Alerts entitlement/authorization status row and fallback warning.
- Routine test launches ringing flow.
- Live alarm launch opens ringing flow.
- Backup rings are recorded on Android.
- QR scanner validates allowed payload before dismissal.
- Dismissal stops Android service/audio/vibration and clears pending launch.
- Metrics match current TypeScript tests.
- Diagnostics export creates equivalent JSON.
- Boot/package-replaced restore works on Android.
- Installed-user data migration is best-effort and is not a release blocker.
- Current generated illustrative UI assets are removed from the native v1 surface.
- Existing RN UI patterns do not leak into the native rewrite; behavior is preserved through platform-native screens rather than visual cloning.
- Native interaction/accessibility quality bar is met on both platforms.

## Verification Gates

Unit tests:

- Repeat-day formatting and toggling.
- Next trigger computation across weekdays, skipped past times, DST boundaries, and timezone changes.
- Attempt metrics for live/test/missed/dismissed histories.
- QR payload creation and validation.
- Best-effort state migration from representative RN JSON, including missing/old fields, only if migration remains in scope.
- Cold-launch recovery from an active ringing runtime.
- Accessibility label/description coverage for critical controls where native test frameworks make this practical.

Android instrumentation/manual gates:

- Fresh install onboarding.
- Notification permission denied/granted paths.
- Exact alarm denied/granted paths on Android 12+.
- Full-screen intent setting on Android 14+.
- Predictive back preview and behavior on supported Android versions.
- Dynamic color and fallback theme contrast.
- Alarm fires while locked, screen off, app killed, and after reboot.
- Backup stages fire until dismissal.
- Volume keys do not silence active ringing service.
- QR scan dismisses only allowed enrolled payloads.
- Touch targets meet 48 x 48 dp minimum.
- Screen reader can operate onboarding, alarm editor, settings, scanner permission, and dismissal flows.
- Release AAB builds and installs with the existing package id.

iOS simulator/device gates:

- Notification authorization denied/provisional/granted paths.
- Critical Alerts entitlement available/unavailable paths.
- Critical Alerts authorization denied/granted paths.
- Repeating local notification scheduling for selected weekdays.
- Critical notification scheduling uses critical interruption level and sound on entitled builds.
- Notification tap opens the ringing/QR flow and records an attempt.
- Camera permission denied/granted paths.
- QR scan dismisses only allowed enrolled payloads.
- In-app test alarm plays and stops audio correctly.
- Standard iOS gestures, Dynamic Type, VoiceOver labels, color contrast, and haptics are verified on core flows.
- Diagnostics export opens share sheet with valid JSON.
- Archive builds with the existing bundle id.

Store/release gates:

- Confirm privacy policy and Play Data Safety still match native app behavior.
- Re-check Android permission declarations after Expo/RN permissions are removed.
- Confirm no network permission remains unless a new native dependency requires it; current Android manifest intentionally removes Internet/access-network-state.
- Build release APK/AAB and iOS archive before declaring migration complete.

## Execution Strategy And Delegation

The implementation should be delegated to two sub-agents. The coordinator should not perform the bulk of coding while they are active, because concurrent main-agent edits in the same migration can create conflicts and unclear ownership.

Required agents:

- Android worker: GPT-5.5, medium reasoning. Owns Android native migration, Kotlin/Compose architecture, Android alarm integration, Android tests, Android build verification, and Android docs updates.
- iOS worker: GPT-5.5, medium reasoning. Owns SwiftUI app migration, Critical Alerts implementation track, iOS notification/audio/scanner/export behavior, iOS tests, iOS archive verification, and iOS docs updates.

Coordinator role:

- Spawn both workers at implementation start with clear write ownership and this spec as the contract.
- Do not edit Android or iOS implementation files while the workers are active.
- Stay available for decisions, review their summaries, resolve cross-platform product questions, and integrate only after a worker reports completion or blocks.
- Keep any coordinator edits limited to specs, task tracking, review notes, or final integration after both workers stop.
- If a shared root file must change, assign it to one worker explicitly or wait until both workers finish, then make a small integration patch.

Worker ownership boundaries:

- Android worker owns `android/**`, Android native assets/resources, Android tests, and Android-specific README/build docs.
- iOS worker owns `ios/**`, iOS native assets/resources, iOS tests, entitlement/configuration notes, and iOS-specific README/build docs.
- Shared root cleanup (`package.json`, Expo/RN removal, old JS/TS deletion, lockfiles, shared docs) happens only after both native tracks are functionally ready, with an explicit integration pass.

Ship policy:

- Android and iOS native apps ship together. Do not release one platform's native rewrite while the other remains RN unless Hasan explicitly changes this decision.

## Implementation Phases

Phase 0: lock current parity baseline

- Run current `pnpm typecheck`, `pnpm test`, and Android build if possible.
- Capture screenshots or screen recordings of all current flows.
- Export a sample diagnostics JSON and optionally create a representative persisted state fixture if best-effort migration remains in scope.
- Record the current dirty worktree before any destructive cleanup; do not remove Expo/RN files until native parity is demonstrated.

Phase 1: native domain model and migration tests

- Port TypeScript models/helpers to Kotlin and Swift.
- Add native fixtures for the current JSON state shape only if migration remains in scope.
- Implement metrics, repeat-day, QR validation, and cold-launch recovery tests on both platforms.

Phase 2: Android native shell

- Convert Android activity/application away from React Native while keeping package id and existing alarm components.
- Build Compose navigation and app store.
- Wire existing alarm scheduler/service into the native app store without the RN bridge.
- Build alarm editor, QR spot list, settings/health, ringing scanner, and dismissed state in Compose.

Phase 3: iOS SwiftUI app

- Replace Expo/RN iOS entrypoint with SwiftUI target using the existing bundle id.
- Implement local notification scheduler, notification response handoff, QR generation/scanning, in-app audio, diagnostics export, and SwiftUI screens.
- Prepare and submit the Critical Alerts entitlement request with product justification, screenshots/prototype evidence, privacy posture, and alarm-use explanation.
- Implement entitlement-aware scheduling: critical alerts when approved and authorized, regular local notifications as a fallback.
- Make platform entitlement state visible in settings/health rather than pretending every iOS install has full alarm guarantees.

Phase 4: data migration and compatibility

- Implement old-state import on both platforms only if it remains low-risk after the native app is stable.
- If skipped, verify clean native install behavior and ensure old RN storage does not crash or block launch.
- Reschedule enabled alarms after migration and verify launch/dismiss behavior.

Phase 5: remove React Native/Expo

- Remove JS/TS app files, Expo config, React Native Gradle plugin, CocoaPods generated RN dependencies, Metro/Bun-only test setup, and unused illustrative/generated assets only after native Android and iOS parity passes.
- Keep store docs, signing files, icons, splash assets, and native alarm code.
- Replace README development commands with native Android/iOS build commands.

Phase 6: release hardening

- Run the full gate: lint, typecheck/compile, unit tests, instrumentation where available, Android AAB, iOS archive, and changelog/release notes.
- Re-run store readiness review because permission declarations and privacy answers change when Expo/RN dependencies are removed.

## Resolved Product Decisions

- Ship Android and iOS native apps together.
- Installed-user data migration is not mandatory for first native release.
- Remove current illustrative/generated assets from native v1; use platform controls, app icon, and restrained accent instead.
- Existing RN UI must not leak into the native rewrite. The old app is a behavior reference, not a visual/template reference.
- Implementation should be delegated to two GPT-5.5-medium sub-agents, one Android and one iOS, with the coordinator avoiding bulk coding while they work.

## References Consulted

- Android Material 3 in Compose: https://developer.android.com/develop/ui/compose/designsystems/material3
- Compose Material 3 release notes: https://developer.android.com/jetpack/androidx/releases/compose-material3
- Android exact alarms: https://developer.android.com/develop/background-work/services/alarms/schedule
- Android 14 exact alarm behavior: https://developer.android.com/about/versions/14/behavior-changes-all
- Android CameraX release notes: https://developer.android.com/jetpack/androidx/releases/camera
- ML Kit barcode scanning for Android: https://developers.google.com/ml-kit/vision/barcode-scanning/android
- Apple SwiftUI overview: https://developer.apple.com/documentation/technologyoverviews/swiftui
- Apple SwiftUI Form: https://developer.apple.com/documentation/swiftui/Form
- Apple SwiftUI Navigation: https://developer.apple.com/documentation/swiftui/navigation
- Apple local notifications: https://developer.apple.com/documentation/usernotifications/scheduling-a-notification-locally-from-your-app
- Apple Critical Alerts entitlement: https://developer.apple.com/documentation/bundleresources/entitlements/com.apple.developer.usernotifications.critical-alerts
- Apple critical alert authorization option: https://developer.apple.com/documentation/usernotifications/unauthorizationoptions/criticalalert
- Apple critical notification interruption level: https://developer.apple.com/documentation/usernotifications/unnotificationinterruptionlevel/critical
- Apple AVAudioSession playback: https://developer.apple.com/documentation/avfaudio/avaudiosession/category-swift.struct/playback
- Apple VisionKit data scanning: https://developer.apple.com/documentation/visionkit/scanning_data_with_the_camera

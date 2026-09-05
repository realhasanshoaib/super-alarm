import Foundation
import SwiftUI
import UIKit
import UserNotifications

@MainActor
final class AppStore: ObservableObject {
    @Published var state = SuperAlarmState()
    @Published var health = NotificationHealth()
    @Published var activeSheet: SheetRoute?
    @Published var isRingingPresented = false
    @Published var dismissedAttempt: AlarmAttempt?
    @Published var alertMessage: String?
    @Published var analyticsEnabled: Bool
    @Published var isActivatingAlarm = false

    private let storage = JsonStateStore()
    private let notifications = AlarmNotificationService()
    private let audio = AlarmAudioService()
    private let haptics = HapticsService()
    private let telemetry: TelemetryClient
    private var didStart = false
    private var schedulingGeneration = 0
    private var schedulingTask: Task<Bool, Never>?
    private var lastTargetReady = false
    private var lastPermissionsReady = false
    private var recentFiredNotifications: [String: Date] = [:]

    init(telemetry: TelemetryClient = LiveTelemetryClient.shared) {
        self.telemetry = telemetry
        analyticsEnabled = telemetry.analyticsEnabled
    }

    var allowedLocations: [QrLocation] {
        state.qrLocations.filter { state.alarm.allowedLocationIds.contains($0.id) }
    }

    var allowedObjectTargets: [ObjectTarget] {
        state.objectTargets.filter { state.alarm.allowedObjectTargetIds.contains($0.id) && !$0.fingerprints.isEmpty }
    }

    var nextAlarmSummary: String {
        guard state.alarm.isEnabled else { return "Alarm off" }
        guard activationReadiness.prerequisitesReady else { return "Alarm not ready" }
        guard state.liveAlarmScheduled else { return "Alarm not scheduled" }
        guard let next = notifications.nextTriggerDate(for: state.alarm) else { return "No repeat day selected" }
        let interval = max(0, Int(next.timeIntervalSince(Date()) / 60))
        if interval < 60 { return "Alarm in \(interval)m" }
        return "Alarm in \(interval / 60)h \(interval % 60)m"
    }

    var onboardingTestPassed: Bool {
        guard let scheduledAt = state.onboardingTestScheduledAt else { return false }
        return state.attempts.contains { attempt in
            attempt.kind == .test &&
                attempt.result == .dismissed &&
                ["object", "qr"].contains(attempt.dismissalMethod ?? "") &&
                attempt.triggeredAt >= scheduledAt.addingTimeInterval(-5)
        }
    }

    var activationReadiness: ActivationReadiness {
        ActivationReadiness(
            hasDismissalTarget: hasDismissalTarget,
            hasRepeatDay: state.alarm.repeatDays.contains(true),
            notificationsAuthorized: [.authorized, .provisional, .ephemeral].contains(health.authorizationStatus),
            notificationAlertsEnabled: health.alertSetting == .enabled,
            notificationSoundsEnabled: health.soundSetting == .enabled
        )
    }

    var shouldPresentOnboarding: Bool {
        !state.hasCompletedOnboarding && !state.hasDeferredOnboarding
    }

    var shouldShowReadinessCard: Bool {
        !state.hasCompletedOnboarding || (state.alarm.isEnabled && (!state.liveAlarmScheduled || !activationReadiness.prerequisitesReady))
    }

    func start() {
        guard !didStart else { return }
        didStart = true
        do {
            state = try storage.load()
            let previousState = state
            ActivationStateMigration.migrate(&state)
            if state != previousState { save() }
        } catch {
            state = SuperAlarmState()
            telemetry.captureFailure(.stateLoading, error: error)
        }
        telemetry.capture(ProductEvent(.appOpened, source: .app))
        recordReadinessTransitions()
        recoverInterruptedRuntimeIfNeeded()
        Task {
            await refreshHealth()
            _ = await enqueueLiveAlarmReconciliation(source: .system).value
        }
    }

    func handleDeepLink(_ url: URL) {
        guard url.scheme == "superalarm" else { return }
        log(.info, "Opened deep link: \(url.absoluteString)")
    }

    func setTime(_ date: Date) {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        state.alarm.timeMinutes = (components.hour ?? 7) * 60 + (components.minute ?? 30)
        persistAndReschedule()
        telemetry.capture(ProductEvent(.alarmTimeChanged, source: .app))
    }

    func setEnabled(_ enabled: Bool) {
        if enabled && !activationReadiness.prerequisitesReady {
            haptics.warning()
            alertMessage = activationMessage(for: activationReadiness.missingRequirements)
            recordPhoneSetupBlockers()
            return
        }
        state.alarm.isEnabled = enabled
        state.liveAlarmScheduled = false
        log(.info, enabled ? "Alarm enabled" : "Alarm disabled")
        haptics.selection()
        persistAndReschedule(source: .app)
        telemetry.capture(ProductEvent(enabled ? .alarmEnabled : .alarmDisabled, source: .app, alarmKind: .live))
    }

    func toggleRepeatDay(_ index: Int) {
        guard state.alarm.repeatDays.indices.contains(index) else { return }
        state.alarm.repeatDays[index].toggle()
        if state.alarm.repeatDays.allSatisfy({ !$0 }) {
            state.alarm.isEnabled = false
            state.liveAlarmScheduled = false
            log(.warning, "Alarm disabled because no repeat days are selected")
        }
        persistAndReschedule()
        telemetry.capture(ProductEvent(.alarmRepeatChanged, source: .app, count: state.alarm.repeatDays.filter { $0 }.count))
    }

    func setLabel(_ label: String) {
        state.alarm.label = label
        persistAndReschedule()
    }

    func setVibrates(_ vibrates: Bool) {
        state.alarm.vibrates = vibrates
        save()
    }

    func setOnboardingStep(_ step: OnboardingStep) {
        let previousStep = state.onboardingStep
        state.onboardingStep = step
        save()
        if step.rawValue > previousStep.rawValue {
            telemetry.capture(ProductEvent(.onboardingStepCompleted, onboardingStep: previousStep.rawValue, source: .onboarding))
        }
    }

    func completeOnboarding() {
        guard !isActivatingAlarm else { return }
        isActivatingAlarm = true
        Task {
            defer { isActivatingAlarm = false }
            await refreshHealth()
            guard activationReadiness.prerequisitesReady else {
                haptics.warning()
                alertMessage = activationMessage(for: activationReadiness.missingRequirements)
                recordPhoneSetupBlockers()
                return
            }

            state.alarm.isEnabled = true
            state.liveAlarmScheduled = false
            save()
            let schedulingTask = enqueueLiveAlarmReconciliation(source: .onboarding)
            let activationGeneration = schedulingGeneration
            let scheduled = await schedulingTask.value
            guard scheduled, schedulingGeneration == activationGeneration,
                  state.alarm.isEnabled, state.liveAlarmScheduled else {
                if schedulingGeneration == activationGeneration {
                    state.alarm.isEnabled = false
                    state.liveAlarmScheduled = false
                    save()
                }
                return
            }

            let completedStep = state.onboardingStep
            telemetry.capture(ProductEvent(.alarmEnabled, source: .onboarding, alarmKind: .live))
            state.hasCompletedOnboarding = true
            state.hasDeferredOnboarding = false
            state.onboardingStep = .ready
            save()
            telemetry.capture(ProductEvent(.onboardingStepCompleted, onboardingStep: completedStep.rawValue, source: .onboarding))
            telemetry.capture(ProductEvent(.onboardingCompleted, source: .onboarding))
            haptics.success()
        }
    }

    func finishOnboardingLater() {
        let deferredAtStep = state.onboardingStep.rawValue
        state.hasDeferredOnboarding = true
        save()
        telemetry.capture(ProductEvent(.onboardingSkipped, onboardingStep: deferredAtStep, source: .onboarding))
    }

    func resumeOnboarding() {
        state.hasCompletedOnboarding = false
        state.hasDeferredOnboarding = false
        state.onboardingStep = activationReadiness.resumeStep(preserving: state.onboardingStep)
        save()
        telemetry.capture(ProductEvent(.onboardingRestarted, onboardingStep: state.onboardingStep.rawValue, source: .app))
    }

    func restartOnboarding() {
        state.hasCompletedOnboarding = false
        state.hasDeferredOnboarding = false
        state.onboardingStep = activationReadiness.resumeStep
        save()
        telemetry.capture(ProductEvent(.onboardingRestarted, source: .app))
    }

    func setAnalyticsEnabled(_ enabled: Bool) {
        analyticsEnabled = enabled
        telemetry.setAnalyticsEnabled(enabled)
    }

    func track(_ event: ProductEvent) {
        telemetry.capture(event)
    }

    @discardableResult
    func addQrSpot(label: String) -> QrLocation? {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let previousStep = state.onboardingStep
        let source: TelemetrySource = state.hasCompletedOnboarding ? .app : .onboarding
        let id = UUID()
        let spot = QrLocation(
            id: id,
            label: trimmed,
            payload: "superalarm://alarm-qr/\(id.uuidString)",
            createdAt: Date()
        )
        state.qrLocations.append(spot)
        state.alarm.allowedLocationIds.append(id)
        if !state.hasCompletedOnboarding {
            state.onboardingStep = .alarm
        }
        log(.info, "Created QR spot: \(trimmed)")
        haptics.success()
        persistAndReschedule()
        if source == .onboarding, state.onboardingStep.rawValue > previousStep.rawValue {
            telemetry.capture(ProductEvent(.onboardingStepCompleted, onboardingStep: previousStep.rawValue, source: source))
        }
        telemetry.capture(ProductEvent(.dismissalEnrollmentCompleted, targetType: .qr, source: source))
        recordReadinessTransitions()
        return spot
    }

    func deleteQrSpot(_ spot: QrLocation) {
        state.qrLocations.removeAll { $0.id == spot.id }
        state.alarm.allowedLocationIds.removeAll { $0 == spot.id }
        if !hasDismissalTarget && state.alarm.isEnabled {
            state.alarm.isEnabled = false
            log(.warning, "Alarm disabled because the last dismissal target was deleted")
        }
        persistAndReschedule()
        telemetry.capture(ProductEvent(.dismissalTargetDeleted, targetType: .qr, source: .app))
        recordReadinessTransitions()
    }

    func setSpotAllowed(_ spot: QrLocation, allowed: Bool) {
        if allowed {
            if !state.alarm.allowedLocationIds.contains(spot.id) {
                state.alarm.allowedLocationIds.append(spot.id)
            }
        } else {
            state.alarm.allowedLocationIds.removeAll { $0 == spot.id }
            if !hasDismissalTarget && state.alarm.isEnabled {
                state.alarm.isEnabled = false
                log(.warning, "Alarm disabled because no dismissal targets are allowed")
            }
        }
        persistAndReschedule()
        telemetry.capture(ProductEvent(.dismissalTargetAvailabilityChanged, targetType: .qr, source: .app, enabled: allowed))
        recordReadinessTransitions()
    }

    func addObjectTarget(label: String, fingerprints: [String]) {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !fingerprints.isEmpty else { return }
        let previousStep = state.onboardingStep
        let source: TelemetrySource = state.hasCompletedOnboarding ? .app : .onboarding
        let id = UUID()
        let target = ObjectTarget(
            id: id,
            label: trimmed,
            fingerprints: fingerprints,
            matchDistanceThreshold: ObjectFeaturePrintCodec.matchThreshold(for: fingerprints),
            visionRevision: ObjectFeaturePrintCodec.revision,
            createdAt: Date()
        )
        state.objectTargets.append(target)
        state.alarm.allowedObjectTargetIds.append(id)
        if !state.hasCompletedOnboarding {
            state.onboardingStep = .alarm
        }
        log(.info, "Enrolled object: \(trimmed)")
        haptics.success()
        persistAndReschedule()
        if source == .onboarding, state.onboardingStep.rawValue > previousStep.rawValue {
            telemetry.capture(ProductEvent(.onboardingStepCompleted, onboardingStep: previousStep.rawValue, source: source))
        }
        telemetry.capture(ProductEvent(.dismissalEnrollmentCompleted, targetType: .object, source: source, count: fingerprints.count))
        recordReadinessTransitions()
    }

    func deleteObjectTarget(_ target: ObjectTarget) {
        state.objectTargets.removeAll { $0.id == target.id }
        state.alarm.allowedObjectTargetIds.removeAll { $0 == target.id }
        if !hasDismissalTarget && state.alarm.isEnabled {
            state.alarm.isEnabled = false
            log(.warning, "Alarm disabled because the last dismissal target was deleted")
        }
        persistAndReschedule()
        telemetry.capture(ProductEvent(.dismissalTargetDeleted, targetType: .object, source: .app))
        recordReadinessTransitions()
    }

    func setObjectAllowed(_ target: ObjectTarget, allowed: Bool) {
        if allowed {
            if !state.alarm.allowedObjectTargetIds.contains(target.id) {
                state.alarm.allowedObjectTargetIds.append(target.id)
            }
        } else {
            state.alarm.allowedObjectTargetIds.removeAll { $0 == target.id }
            if !hasDismissalTarget && state.alarm.isEnabled {
                state.alarm.isEnabled = false
                log(.warning, "Alarm disabled because no dismissal targets are allowed")
            }
        }
        persistAndReschedule()
        telemetry.capture(ProductEvent(.dismissalTargetAvailabilityChanged, targetType: .object, source: .app, enabled: allowed))
        recordReadinessTransitions()
    }

    func requestNotifications() {
        telemetry.capture(ProductEvent(.permissionActionSelected, source: state.hasCompletedOnboarding ? .app : .onboarding, permissionKind: "notifications"))
        Task {
            do {
                try await notifications.requestAuthorization(includeCritical: health.hasCriticalEntitlement)
                await refreshHealth()
                let allowed = [.authorized, .provisional, .ephemeral].contains(health.authorizationStatus)
                telemetry.capture(ProductEvent(.permissionResultObserved, source: state.hasCompletedOnboarding ? .app : .onboarding, outcome: allowed ? .allowed : .denied, permissionKind: "notifications"))
                if !activationReadiness.prerequisitesReady { recordPhoneSetupBlockers() }
                _ = await enqueueLiveAlarmReconciliation(source: .system).value
            } catch {
                log(.error, "Notification authorization failed: \(error.localizedDescription)")
                alertMessage = "Notification authorization failed. Check iOS Settings and try again."
                telemetry.captureFailure(.notificationAuthorization, error: error)
            }
        }
    }

    func openAppSettings() {
        guard let url = URL(string: UIApplication.openNotificationSettingsURLString) else { return }
        telemetry.capture(ProductEvent(.deviceSettingsOpened, source: state.hasCompletedOnboarding ? .app : .onboarding, permissionKind: "notifications"))
        UIApplication.shared.open(url)
    }

    func openCameraSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        telemetry.capture(ProductEvent(.deviceSettingsOpened, source: state.hasCompletedOnboarding ? .app : .onboarding, permissionKind: "camera"))
        UIApplication.shared.open(url)
    }

    func refreshHealth() async {
        health = await notifications.health()
        recordReadinessTransitions()
    }

    func scheduleVerificationTest() {
        Task {
            await refreshHealth()
            guard hasDismissalTarget else {
                alertMessage = "Choose a dismissal target before verifying this phone."
                telemetry.capture(ProductEvent(.wakeUpTestFailed, source: .app, outcome: .blocked, failureCode: "missing_target", alarmKind: .test))
                return
            }
            guard [.authorized, .provisional, .ephemeral].contains(health.authorizationStatus) else {
                alertMessage = "Allow notifications before running the wake-up test."
                telemetry.capture(ProductEvent(.wakeUpTestFailed, source: .app, outcome: .blocked, failureCode: "notification_blocked", alarmKind: .test))
                return
            }
            guard health.soundSetting == .enabled else {
                alertMessage = "Turn on Sounds for Super Alarm in iOS Settings before running the wake-up test."
                telemetry.capture(ProductEvent(.wakeUpTestFailed, source: .app, outcome: .blocked, failureCode: "sound_blocked", alarmKind: .test))
                return
            }
            guard health.alertSetting == .enabled else {
                alertMessage = "Turn on Alerts for Super Alarm in iOS Settings before running the wake-up test."
                telemetry.capture(ProductEvent(.wakeUpTestFailed, source: .app, outcome: .blocked, failureCode: "alerts_blocked", alarmKind: .test))
                return
            }
            do {
                let triggerAt = try await notifications.scheduleVerificationTest(after: 10, health: health)
                await MainActor.run {
                    self.recentFiredNotifications.removeValue(forKey: AlarmNotificationService.onboardingTestIdentifier)
                    self.state.onboardingTestScheduledAt = triggerAt
                    self.save()
                    self.telemetry.capture(ProductEvent(.alarmScheduled, source: .app, outcome: .success, alarmKind: .test))
                    self.telemetry.capture(ProductEvent(.wakeUpTestScheduled, source: .app, alarmKind: .test))
                }
            } catch {
                await MainActor.run {
                    self.log(.error, "Wake-up test scheduling failed: \(error.localizedDescription)")
                    self.alertMessage = "The wake-up test could not be scheduled. Check notification settings and try again."
                    self.telemetry.capture(ProductEvent(.wakeUpTestFailed, source: .app, outcome: .failed, failureCode: "schedule_failed", alarmKind: .test))
                    self.telemetry.captureFailure(.alarmScheduling, error: error)
                }
            }
        }
    }

    func cancelVerificationTest() {
        Task {
            await notifications.cancelVerificationTest()
            state.onboardingTestScheduledAt = nil
            save()
            telemetry.capture(ProductEvent(.wakeUpTestCancelled, source: .app, outcome: .cancelled, alarmKind: .test))
        }
    }

    func retryLiveAlarmScheduling() {
        Task {
            await refreshHealth()
            guard activationReadiness.prerequisitesReady else {
                alertMessage = activationMessage(for: activationReadiness.missingRequirements)
                recordPhoneSetupBlockers()
                return
            }
            _ = await enqueueLiveAlarmReconciliation(source: .app).value
        }
    }

    func observePhoneSetupBlockers() {
        recordPhoneSetupBlockers()
    }

    func openRinging(kind: AlarmAttempt.Kind, notificationIdentifier: String?) {
        let attempt = AlarmAttempt(
            id: UUID(),
            kind: kind,
            triggeredAt: Date(),
            dismissedAt: nil,
            result: .ringing,
            backupStage: nil,
            dismissalLocationId: nil,
            dismissalMethod: nil
        )
        state.attempts.insert(attempt, at: 0)
        state.runtime = AlarmRuntimeState(attemptId: attempt.id, phase: .ringing, ringingStartedAt: Date(), kind: kind)
        if let notificationIdentifier {
            log(.info, "Opened alarm from notification: \(notificationIdentifier)")
        }
        save()
        let source: TelemetrySource = notificationIdentifier == nil ? .app : .notification
        if let notificationIdentifier {
            recordAlarmNotificationFired(kind: kind, identifier: notificationIdentifier)
        } else {
            telemetry.capture(ProductEvent(.alarmFired, surface: .ringing, source: source, alarmKind: kind))
        }
        telemetry.capture(ProductEvent(.ringingStarted, surface: .ringing, source: source, alarmKind: kind))
        if kind == .test {
            telemetry.capture(ProductEvent(.wakeUpTestStarted, surface: .ringing, source: source, alarmKind: .test))
        }
        isRingingPresented = true
        audio.start(vibrates: state.alarm.vibrates)
    }

    func recordAlarmNotificationFired(kind: AlarmAttempt.Kind, identifier: String) {
        let now = Date()
        if let recordedAt = recentFiredNotifications[identifier], now.timeIntervalSince(recordedAt) < 300 {
            return
        }
        recentFiredNotifications[identifier] = now
        telemetry.capture(ProductEvent(.alarmFired, source: .notification, alarmKind: kind))
    }

    func handleScan(_ payload: String) -> ScanResult {
        guard let alarmKind = state.runtime?.kind else {
            return .invalid("No alarm is ringing.")
        }
        guard let spot = spot(for: payload) else {
            log(.warning, "Rejected unknown QR payload")
            haptics.error()
            telemetry.capture(ProductEvent(.dismissalAttempted, targetType: .qr, source: .app, outcome: .failed, failureCode: "unknown", alarmKind: alarmKind))
            return .invalid("This QR code is not enrolled in Super Alarm.")
        }
        guard state.alarm.allowedLocationIds.contains(spot.id) else {
            log(.warning, "Rejected disallowed QR spot: \(spot.label)")
            haptics.warning()
            telemetry.capture(ProductEvent(.dismissalAttempted, targetType: .qr, source: .app, outcome: .blocked, failureCode: "disallowed", alarmKind: alarmKind))
            return .invalid("\(spot.label) is enrolled but not allowed for this alarm.")
        }
        completeDismissal(targetId: spot.id, targetLabel: spot.label, method: "qr")
        return .valid(spot)
    }

    func dismissAlarm(with target: ObjectTarget) {
        guard let alarmKind = state.runtime?.kind else { return }
        guard state.alarm.allowedObjectTargetIds.contains(target.id) else {
            log(.warning, "Rejected disallowed object: \(target.label)")
            haptics.warning()
            telemetry.capture(ProductEvent(.dismissalAttempted, targetType: .object, source: .app, outcome: .blocked, failureCode: "disallowed", alarmKind: alarmKind))
            return
        }
        completeDismissal(targetId: target.id, targetLabel: target.label, method: "object")
    }

    private func completeDismissal(targetId: UUID, targetLabel: String, method: String) {
        guard let runtime = state.runtime else { return }
        let dismissedAt = Date()
        if let index = state.attempts.firstIndex(where: { $0.id == runtime.attemptId }) {
            state.attempts[index].dismissedAt = dismissedAt
            state.attempts[index].result = .dismissed
            state.attempts[index].dismissalLocationId = targetId
            state.attempts[index].dismissalMethod = method
            dismissedAttempt = state.attempts[index]
        }
        state.runtime = AlarmRuntimeState(
            attemptId: runtime.attemptId,
            phase: .dismissed,
            ringingStartedAt: runtime.ringingStartedAt,
            kind: runtime.kind
        )
        log(.info, "Alarm dismissed with \(targetLabel)")
        haptics.success()
        audio.stop()
        save()
        telemetry.capture(ProductEvent(
            .alarmDismissed,
            targetType: method == "object" ? .object : .qr,
            source: .app,
            outcome: .success,
            durationSeconds: Int(dismissedAt.timeIntervalSince(runtime.ringingStartedAt)),
            alarmKind: runtime.kind
        ))
        if runtime.kind == .test {
            telemetry.capture(ProductEvent(.wakeUpTestPassed, source: .app, outcome: .success, alarmKind: .test))
        }
    }

    func beginDismissal(targetType: DismissalTargetType) {
        guard let alarmKind = state.runtime?.kind else { return }
        telemetry.capture(ProductEvent(.dismissalStarted, surface: .ringing, targetType: targetType, source: .app, alarmKind: alarmKind))
    }

    func stopVerificationRinging() {
        guard let runtime = state.runtime, runtime.kind == .test else { return }
        if let index = state.attempts.firstIndex(where: { $0.id == runtime.attemptId }) {
            state.attempts[index].result = .interrupted
        }
        state.onboardingTestScheduledAt = nil
        telemetry.capture(ProductEvent(.wakeUpTestCancelled, source: .app, outcome: .cancelled, alarmKind: .test))
        closeRinging()
    }

    func closeRinging() {
        audio.stop()
        isRingingPresented = false
        dismissedAttempt = nil
        state.runtime = nil
        save()
    }

    func diagnosticsExportData() -> Data {
        telemetry.capture(ProductEvent(.diagnosticsExported, surface: .settings, source: .app))
        let export = DiagnosticsExport(
            exportedAt: Date(),
            bundleIdentifier: Bundle.main.bundleIdentifier ?? "com.superalarm.app",
            appVersion: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0",
            notificationHealth: ExportedNotificationHealth(health),
            state: state
        )
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        do {
            return try encoder.encode(export)
        } catch {
            telemetry.captureFailure(.diagnosticsExport, error: error)
            return Data("{}".utf8)
        }
    }

    private func spot(for payload: String) -> QrLocation? {
        state.qrLocations.first { $0.payload == payload }
    }

    private var hasDismissalTarget: Bool {
        !allowedLocations.isEmpty || !allowedObjectTargets.isEmpty
    }

    private func recoverInterruptedRuntimeIfNeeded() {
        guard let runtime = state.runtime, runtime.phase == .ringing else { return }
        if let index = state.attempts.firstIndex(where: { $0.id == runtime.attemptId }) {
            state.attempts[index].result = .interrupted
        }
        state.runtime = nil
        log(.warning, "Recovered interrupted ringing state after launch")
        save()
        telemetry.capture(ProductEvent(.ringingStoppedUnexpectedly, source: .system, alarmKind: runtime.kind))
    }

    private func persistAndReschedule(source: TelemetrySource = .system) {
        state.liveAlarmScheduled = false
        save()
        _ = enqueueLiveAlarmReconciliation(source: source)
    }

    private func enqueueLiveAlarmReconciliation(source: TelemetrySource) -> Task<Bool, Never> {
        schedulingGeneration += 1
        let generation = schedulingGeneration
        let previousTask = schedulingTask
        previousTask?.cancel()
        let task = Task { [weak self] in
            if let previousTask { _ = await previousTask.value }
            guard let self, !Task.isCancelled else { return false }
            return await self.reconcileLiveAlarm(generation: generation, source: source)
        }
        schedulingTask = task
        return task
    }

    private func reconcileLiveAlarm(generation: Int, source: TelemetrySource) async -> Bool {
        guard generation == schedulingGeneration, !Task.isCancelled else { return false }
        await notifications.cancelLiveAlarm()
        guard generation == schedulingGeneration, !Task.isCancelled else { return false }
        state.liveAlarmScheduled = false
        save()
        guard state.alarm.isEnabled else { return true }
        guard activationReadiness.prerequisitesReady else {
            recordPhoneSetupBlockers()
            return false
        }
        let alarm = state.alarm
        do {
            try await notifications.schedule(alarm: alarm, health: health)
            guard generation == schedulingGeneration,
                  !Task.isCancelled,
                  state.alarm == alarm,
                  state.alarm.isEnabled,
                  activationReadiness.prerequisitesReady
            else {
                await notifications.cancelLiveAlarm()
                return false
            }
            state.liveAlarmScheduled = true
            if source == .app && state.hasDeferredOnboarding && !state.hasCompletedOnboarding {
                state.hasCompletedOnboarding = true
                state.hasDeferredOnboarding = false
                state.onboardingStep = .ready
                telemetry.capture(ProductEvent(.onboardingCompleted, source: .app))
            }
            log(.info, "Scheduled iOS local alarm notifications")
            save()
            telemetry.capture(ProductEvent(.alarmScheduled, source: source, outcome: .success, alarmKind: .live))
            return true
        } catch {
            await notifications.cancelLiveAlarm()
            guard generation == schedulingGeneration else { return false }
            state.liveAlarmScheduled = false
            log(.error, "Alarm scheduling failed: \(error.localizedDescription)")
            alertMessage = "Alarm scheduling failed. Check notification permission and try again."
            telemetry.capture(ProductEvent(.alarmScheduleFailed, source: source, outcome: .failed, failureCode: "notification_schedule_failed", alarmKind: .live, phoneSetupBlocker: .schedulingFailed))
            telemetry.captureFailure(.alarmScheduling, error: error)
            save()
            return false
        }
    }

    private func recordReadinessTransitions() {
        let readiness = activationReadiness
        if readiness.hasDismissalTarget && !lastTargetReady {
            telemetry.capture(ProductEvent(.dismissalTargetReady, source: state.hasCompletedOnboarding ? .app : .onboarding))
        }
        let permissionsReady = readiness.notificationsAuthorized && readiness.notificationAlertsEnabled && readiness.notificationSoundsEnabled
        if permissionsReady && (!lastPermissionsReady || (readiness.hasDismissalTarget && !lastTargetReady)) {
            telemetry.capture(ProductEvent(.alarmPermissionsReady, source: state.hasCompletedOnboarding ? .app : .onboarding))
        }
        lastTargetReady = readiness.hasDismissalTarget
        lastPermissionsReady = permissionsReady
    }

    private func recordPhoneSetupBlockers() {
        for requirement in activationReadiness.missingRequirements {
            let blocker: PhoneSetupBlocker
            switch requirement {
            case .dismissalTarget: blocker = .dismissalTargetMissing
            case .repeatDay: blocker = .repeatDayMissing
            case .notificationAuthorization:
                blocker = health.authorizationStatus == .denied ? .notificationsDenied : .notificationsNotRequested
            case .notificationAlerts: blocker = .alertsDisabled
            case .notificationSounds: blocker = .soundsDisabled
            }
            telemetry.capture(ProductEvent(.phoneSetupBlockerObserved, surface: .onboardingPhoneSetup, source: .onboarding, outcome: .blocked, phoneSetupBlocker: blocker))
        }
    }

    private func activationMessage(for requirements: [ActivationRequirement]) -> String {
        guard let first = requirements.first else { return "Finish alarm setup and try again." }
        return "Alarm not ready: \(first.label.lowercased())."
    }

    private func log(_ level: AlarmDiagnosticEvent.Level, _ message: String) {
        state.diagnostics.insert(AlarmDiagnosticEvent(id: UUID(), date: Date(), level: level, message: message), at: 0)
        state.diagnostics = Array(state.diagnostics.prefix(80))
    }

    private func save() {
        do {
            try storage.save(state)
        } catch {
            telemetry.captureFailure(.stateSaving, error: error)
        }
    }
}

enum SheetRoute: Identifiable, Equatable {
    case addObject
    case addSpot
    case diagnostics
    case spot(QrLocation)

    var id: String {
        switch self {
        case .addObject: return "addObject"
        case .addSpot: return "addSpot"
        case .diagnostics: return "diagnostics"
        case .spot(let spot): return "spot-\(spot.id.uuidString)"
        }
    }
}

enum ScanResult: Equatable {
    case valid(QrLocation)
    case invalid(String)
}

// MARK: - Services

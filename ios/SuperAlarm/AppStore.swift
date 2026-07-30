import Foundation
import SwiftUI
import UIKit
import UserNotifications

final class AppStore: ObservableObject {
    @Published var state = SuperAlarmState()
    @Published var health = NotificationHealth()
    @Published var activeSheet: SheetRoute?
    @Published var isRingingPresented = false
    @Published var dismissedAttempt: AlarmAttempt?
    @Published var alertMessage: String?

    private let storage = JsonStateStore()
    private let notifications = AlarmNotificationService()
    private let audio = AlarmAudioService()
    private let haptics = HapticsService()
    private var didStart = false

    var allowedLocations: [QrLocation] {
        state.qrLocations.filter { state.alarm.allowedLocationIds.contains($0.id) }
    }

    var allowedObjectTargets: [ObjectTarget] {
        state.objectTargets.filter { state.alarm.allowedObjectTargetIds.contains($0.id) }
    }

    var nextAlarmSummary: String {
        guard state.alarm.isEnabled else { return "Alarm off" }
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

    func start() {
        guard !didStart else { return }
        didStart = true
        state = storage.load()
        recoverInterruptedRuntimeIfNeeded()
        Task { await refreshHealth(); await rescheduleIfNeeded() }
    }

    func handleDeepLink(_ url: URL) {
        guard url.scheme == "superalarm" else { return }
        log(.info, "Opened deep link: \(url.absoluteString)")
    }

    func setTime(_ date: Date) {
        let components = Calendar.current.dateComponents([.hour, .minute], from: date)
        state.alarm.timeMinutes = (components.hour ?? 7) * 60 + (components.minute ?? 30)
        persistAndReschedule()
    }

    func setEnabled(_ enabled: Bool) {
        if enabled && !hasDismissalTarget {
            haptics.warning()
            alertMessage = "Add at least one allowed object or QR spot before enabling the alarm."
            return
        }
        state.alarm.isEnabled = enabled
        log(.info, enabled ? "Alarm enabled" : "Alarm disabled")
        haptics.selection()
        persistAndReschedule()
    }

    func toggleRepeatDay(_ index: Int) {
        guard state.alarm.repeatDays.indices.contains(index) else { return }
        state.alarm.repeatDays[index].toggle()
        if state.alarm.repeatDays.allSatisfy({ !$0 }) {
            state.alarm.isEnabled = false
            log(.warning, "Alarm disabled because no repeat days are selected")
        }
        persistAndReschedule()
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
        state.onboardingStep = step
        save()
    }

    func completeOnboarding() {
        state.hasCompletedOnboarding = true
        state.onboardingStep = .ready
        state.alarm.isEnabled = true
        persistAndReschedule()
    }

    func addQrSpot(label: String) {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return }
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
    }

    func deleteQrSpot(_ spot: QrLocation) {
        state.qrLocations.removeAll { $0.id == spot.id }
        state.alarm.allowedLocationIds.removeAll { $0 == spot.id }
        if !hasDismissalTarget && state.alarm.isEnabled {
            state.alarm.isEnabled = false
            log(.warning, "Alarm disabled because the last dismissal target was deleted")
        }
        persistAndReschedule()
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
    }

    func addObjectTarget(label: String, fingerprints: [String]) {
        let trimmed = label.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty, !fingerprints.isEmpty else { return }
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
    }

    func deleteObjectTarget(_ target: ObjectTarget) {
        state.objectTargets.removeAll { $0.id == target.id }
        state.alarm.allowedObjectTargetIds.removeAll { $0 == target.id }
        if !hasDismissalTarget && state.alarm.isEnabled {
            state.alarm.isEnabled = false
            log(.warning, "Alarm disabled because the last dismissal target was deleted")
        }
        persistAndReschedule()
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
    }

    func requestNotifications() {
        Task {
            do {
                try await notifications.requestAuthorization(includeCritical: health.hasCriticalEntitlement)
                await refreshHealth()
                await rescheduleIfNeeded()
            } catch {
                log(.error, "Notification authorization failed: \(error.localizedDescription)")
                alertMessage = "Notification authorization failed. Check iOS Settings and try again."
            }
        }
    }

    func refreshHealth() async {
        health = await notifications.health()
    }

    func runTestAlarm() {
        openRinging(kind: .test, notificationIdentifier: nil)
    }

    func scheduleOnboardingTest() {
        Task {
            await refreshHealth()
            guard [.authorized, .provisional, .ephemeral].contains(health.authorizationStatus) else {
                alertMessage = "Allow notifications before running the wake-up test."
                return
            }
            do {
                let triggerAt = try await notifications.scheduleOnboardingTest(after: 10, health: health)
                await MainActor.run {
                    self.state.alarm.isEnabled = true
                    self.state.onboardingStep = .wakeUpTest
                    self.state.onboardingTestScheduledAt = triggerAt
                    self.persistAndReschedule()
                }
            } catch {
                await MainActor.run {
                    self.log(.error, "Wake-up test scheduling failed: \(error.localizedDescription)")
                    self.alertMessage = "The wake-up test could not be scheduled. Check notification settings and try again."
                }
            }
        }
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
        isRingingPresented = true
        audio.start(vibrates: state.alarm.vibrates)
    }

    func handleScan(_ payload: String) -> ScanResult {
        guard let spot = spot(for: payload) else {
            log(.warning, "Rejected unknown QR payload")
            haptics.error()
            return .invalid("This QR code is not enrolled in Super Alarm.")
        }
        guard state.alarm.allowedLocationIds.contains(spot.id) else {
            log(.warning, "Rejected disallowed QR spot: \(spot.label)")
            haptics.warning()
            return .invalid("\(spot.label) is enrolled but not allowed for this alarm.")
        }
        completeDismissal(targetId: spot.id, targetLabel: spot.label, method: "qr")
        return .valid(spot)
    }

    func dismissAlarm(with target: ObjectTarget) {
        guard state.alarm.allowedObjectTargetIds.contains(target.id) else {
            log(.warning, "Rejected disallowed object: \(target.label)")
            haptics.warning()
            return
        }
        completeDismissal(targetId: target.id, targetLabel: target.label, method: "object")
    }

    private func completeDismissal(targetId: UUID, targetLabel: String, method: String) {
        guard let runtime = state.runtime else { return }
        if let index = state.attempts.firstIndex(where: { $0.id == runtime.attemptId }) {
            state.attempts[index].dismissedAt = Date()
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
    }

    func closeRinging() {
        audio.stop()
        isRingingPresented = false
        dismissedAttempt = nil
        state.runtime = nil
        save()
    }

    func diagnosticsExportData() -> Data {
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
        return (try? encoder.encode(export)) ?? Data("{}".utf8)
    }

    private func spot(for payload: String) -> QrLocation? {
        state.qrLocations.first { $0.payload == payload }
    }

    private var hasDismissalTarget: Bool {
        !state.alarm.allowedLocationIds.isEmpty || !state.alarm.allowedObjectTargetIds.isEmpty
    }

    private func recoverInterruptedRuntimeIfNeeded() {
        guard let runtime = state.runtime, runtime.phase == .ringing else { return }
        if let index = state.attempts.firstIndex(where: { $0.id == runtime.attemptId }) {
            state.attempts[index].result = .interrupted
        }
        state.runtime = nil
        log(.warning, "Recovered interrupted ringing state after launch")
        save()
    }

    private func persistAndReschedule() {
        save()
        Task { await rescheduleIfNeeded() }
    }

    private func rescheduleIfNeeded() async {
        await notifications.cancelAll()
        guard state.alarm.isEnabled else { return }
        do {
            try await notifications.schedule(alarm: state.alarm, health: health)
            log(.info, "Scheduled iOS local alarm notifications")
            save()
        } catch {
            log(.error, "Alarm scheduling failed: \(error.localizedDescription)")
            alertMessage = "Alarm scheduling failed. Check notification permission and try again."
        }
    }

    private func log(_ level: AlarmDiagnosticEvent.Level, _ message: String) {
        state.diagnostics.insert(AlarmDiagnosticEvent(id: UUID(), date: Date(), level: level, message: message), at: 0)
        state.diagnostics = Array(state.diagnostics.prefix(80))
    }

    private func save() {
        storage.save(state)
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

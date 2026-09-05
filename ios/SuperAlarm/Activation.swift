import Foundation

enum ActivationRequirement: String, CaseIterable, Equatable {
    case dismissalTarget
    case repeatDay
    case notificationAuthorization
    case notificationAlerts
    case notificationSounds

    var label: String {
        switch self {
        case .dismissalTarget: return "Choose a dismissal target"
        case .repeatDay: return "Select at least one repeat day"
        case .notificationAuthorization: return "Allow notifications"
        case .notificationAlerts: return "Turn on notification alerts"
        case .notificationSounds: return "Turn on notification sounds"
        }
    }
}

struct ActivationReadiness: Equatable {
    let hasDismissalTarget: Bool
    let hasRepeatDay: Bool
    let notificationsAuthorized: Bool
    let notificationAlertsEnabled: Bool
    let notificationSoundsEnabled: Bool

    var missingRequirements: [ActivationRequirement] {
        var missing: [ActivationRequirement] = []
        if !hasDismissalTarget { missing.append(.dismissalTarget) }
        if !hasRepeatDay { missing.append(.repeatDay) }
        if !notificationsAuthorized { missing.append(.notificationAuthorization) }
        if notificationsAuthorized && !notificationAlertsEnabled { missing.append(.notificationAlerts) }
        if notificationsAuthorized && !notificationSoundsEnabled { missing.append(.notificationSounds) }
        return missing
    }

    var prerequisitesReady: Bool { missingRequirements.isEmpty }

    var resumeStep: OnboardingStep {
        if !hasDismissalTarget { return .dismissalMethod }
        if !hasRepeatDay { return .alarm }
        return .phoneSetup
    }

    func resumeStep(preserving savedStep: OnboardingStep) -> OnboardingStep {
        guard hasDismissalTarget else { return .dismissalMethod }
        if [.welcome, .dismissalMethod, .alarm].contains(savedStep) { return .alarm }
        return resumeStep
    }
}

enum ActivationStateMigration {
    static let currentVersion = 2

    static func migrate(_ state: inout SuperAlarmState) {
        guard state.migrationVersion < currentVersion else { return }

        if state.hasCompletedOnboarding && state.onboardingStep != .ready {
            state.hasCompletedOnboarding = false
            state.hasDeferredOnboarding = true
        }
        if !state.hasCompletedOnboarding {
            state.onboardingStep = legacyResumeStep(for: state)
        }
        state.liveAlarmScheduled = false
        state.migrationVersion = currentVersion
    }

    private static func legacyResumeStep(for state: SuperAlarmState) -> OnboardingStep {
        let hasQrTarget = state.qrLocations.contains { state.alarm.allowedLocationIds.contains($0.id) }
        let hasObjectTarget = state.objectTargets.contains { state.alarm.allowedObjectTargetIds.contains($0.id) && !$0.fingerprints.isEmpty }
        let hasTarget = hasQrTarget || hasObjectTarget
        if !hasTarget { return .dismissalMethod }
        if !state.alarm.repeatDays.contains(true) { return .alarm }
        if [.welcome, .dismissalMethod, .alarm].contains(state.onboardingStep) { return .alarm }
        return .phoneSetup
    }
}

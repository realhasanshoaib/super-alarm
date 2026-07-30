import Foundation
import UserNotifications

struct SuperAlarmState: Codable, Equatable {
    var alarm = AlarmConfig()
    var qrLocations: [QrLocation] = []
    var objectTargets: [ObjectTarget] = []
    var attempts: [AlarmAttempt] = []
    var diagnostics: [AlarmDiagnosticEvent] = []
    var runtime: AlarmRuntimeState?
    var hasCompletedOnboarding = false
    var onboardingStep = OnboardingStep.welcome
    var onboardingTestScheduledAt: Date?
    var migrationVersion = 1

    init() { }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        alarm = try container.decodeIfPresent(AlarmConfig.self, forKey: .alarm) ?? AlarmConfig()
        qrLocations = try container.decodeIfPresent([QrLocation].self, forKey: .qrLocations) ?? []
        objectTargets = try container.decodeIfPresent([ObjectTarget].self, forKey: .objectTargets) ?? []
        attempts = try container.decodeIfPresent([AlarmAttempt].self, forKey: .attempts) ?? []
        diagnostics = try container.decodeIfPresent([AlarmDiagnosticEvent].self, forKey: .diagnostics) ?? []
        runtime = try container.decodeIfPresent(AlarmRuntimeState.self, forKey: .runtime)
        hasCompletedOnboarding = try container.decodeIfPresent(Bool.self, forKey: .hasCompletedOnboarding) ?? false
        onboardingStep = try container.decodeIfPresent(OnboardingStep.self, forKey: .onboardingStep) ?? .welcome
        onboardingTestScheduledAt = try container.decodeIfPresent(Date.self, forKey: .onboardingTestScheduledAt)
        migrationVersion = try container.decodeIfPresent(Int.self, forKey: .migrationVersion) ?? 1
    }
}

enum OnboardingStep: Int, Codable, Equatable {
    case welcome
    case dismissalMethod
    case alarm
    case phoneSetup
    case wakeUpTest
    case ready
}

struct AlarmConfig: Codable, Equatable {
    var label = "Morning alarm"
    var isEnabled = false
    var timeMinutes = 7 * 60 + 30
    var repeatDays: [Bool] = [true, true, true, true, true, false, false]
    var soundId = "default"
    var vibrates = true
    var backupOffsets: [Int] = [5, 10]
    var allowedLocationIds: [UUID] = []
    var allowedObjectTargetIds: [UUID] = []

    init() { }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        label = try container.decodeIfPresent(String.self, forKey: .label) ?? "Morning alarm"
        isEnabled = try container.decodeIfPresent(Bool.self, forKey: .isEnabled) ?? false
        timeMinutes = try container.decodeIfPresent(Int.self, forKey: .timeMinutes) ?? 7 * 60 + 30
        repeatDays = try container.decodeIfPresent([Bool].self, forKey: .repeatDays) ?? [true, true, true, true, true, false, false]
        soundId = try container.decodeIfPresent(String.self, forKey: .soundId) ?? "default"
        vibrates = try container.decodeIfPresent(Bool.self, forKey: .vibrates) ?? true
        backupOffsets = try container.decodeIfPresent([Int].self, forKey: .backupOffsets) ?? [5, 10]
        allowedLocationIds = try container.decodeIfPresent([UUID].self, forKey: .allowedLocationIds) ?? []
        allowedObjectTargetIds = try container.decodeIfPresent([UUID].self, forKey: .allowedObjectTargetIds) ?? []
    }
}

struct QrLocation: Codable, Identifiable, Equatable {
    var id: UUID
    var label: String
    var payload: String
    var createdAt: Date
}

struct ObjectTarget: Codable, Identifiable, Equatable {
    var id: UUID
    var label: String
    var fingerprints: [String]
    var matchDistanceThreshold: Float
    var visionRevision: Int
    var createdAt: Date
}

struct AlarmAttempt: Codable, Identifiable, Equatable {
    enum Kind: String, Codable { case live, test }
    enum Result: String, Codable { case ringing, dismissed, interrupted, missed }

    var id: UUID
    var kind: Kind
    var triggeredAt: Date
    var dismissedAt: Date?
    var result: Result
    var backupStage: Int?
    var dismissalLocationId: UUID?
    var dismissalMethod: String?
}

struct AlarmRuntimeState: Codable, Equatable {
    enum Phase: String, Codable { case ringing, dismissed }

    var attemptId: UUID
    var phase: Phase
    var ringingStartedAt: Date
    var kind: AlarmAttempt.Kind
}

struct AlarmDiagnosticEvent: Codable, Identifiable, Equatable {
    enum Level: String, Codable { case info, warning, error }

    var id: UUID
    var date: Date
    var level: Level
    var message: String
}

struct NotificationHealth: Equatable {
    var authorizationStatus: UNAuthorizationStatus = .notDetermined
    var criticalSetting: UNNotificationSetting = .notSupported
    var alertSetting: UNNotificationSetting = .notSupported
    var soundSetting: UNNotificationSetting = .notSupported
    var hasCriticalEntitlement = false

    var notificationLabel: String {
        switch authorizationStatus {
        case .authorized, .provisional, .ephemeral: return "On"
        case .denied: return "Denied"
        case .notDetermined: return "Not requested"
        @unknown default: return "Unknown"
        }
    }

    var criticalLabel: String {
        if !hasCriticalEntitlement { return "Entitlement missing" }
        switch criticalSetting {
        case .enabled: return "Enabled"
        case .disabled: return "Denied"
        case .notSupported: return "Unavailable"
        @unknown default: return "Unknown"
        }
    }
}

// MARK: - Store

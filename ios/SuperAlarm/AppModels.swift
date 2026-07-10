import Foundation
import UserNotifications

struct SuperAlarmState: Codable, Equatable {
    var alarm = AlarmConfig()
    var qrLocations: [QrLocation] = []
    var attempts: [AlarmAttempt] = []
    var diagnostics: [AlarmDiagnosticEvent] = []
    var runtime: AlarmRuntimeState?
    var hasCompletedOnboarding = false
    var migrationVersion = 1
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
}

struct QrLocation: Codable, Identifiable, Equatable {
    var id: UUID
    var label: String
    var payload: String
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


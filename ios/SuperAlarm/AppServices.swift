import AVFoundation
import AudioToolbox
import Foundation
import UIKit
import UserNotifications

struct JsonStateStore {
    private var url: URL {
        let directory = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
            .appendingPathComponent("SuperAlarm", isDirectory: true)
        try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        return directory.appendingPathComponent("state-v1.json")
    }

    func load() -> SuperAlarmState {
        guard let data = try? Data(contentsOf: url) else { return SuperAlarmState() }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .iso8601
        return (try? decoder.decode(SuperAlarmState.self, from: data)) ?? SuperAlarmState()
    }

    func save(_ state: SuperAlarmState) {
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted, .sortedKeys]
        encoder.dateEncodingStrategy = .iso8601
        if let data = try? encoder.encode(state) {
            try? data.write(to: url, options: [.atomic])
        }
    }
}

struct AlarmNotificationService {
    func requestAuthorization(includeCritical: Bool) async throws {
        var options: UNAuthorizationOptions = [.alert, .sound, .badge]
        if includeCritical { options.insert(.criticalAlert) }
        _ = try await UNUserNotificationCenter.current().requestAuthorization(options: options)
    }

    func health() async -> NotificationHealth {
        let settings = await UNUserNotificationCenter.current().notificationSettings()
        return NotificationHealth(
            authorizationStatus: settings.authorizationStatus,
            criticalSetting: settings.criticalAlertSetting,
            alertSetting: settings.alertSetting,
            soundSetting: settings.soundSetting,
            hasCriticalEntitlement: EntitlementReader.hasCriticalAlerts
        )
    }

    func cancelAll() async {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: scheduledIdentifiers)
    }

    func schedule(alarm: AlarmConfig, health: NotificationHealth) async throws {
        let center = UNUserNotificationCenter.current()
        for index in alarm.repeatDays.indices where alarm.repeatDays[index] {
            let content = UNMutableNotificationContent()
            content.title = alarm.label
            content.body = "Scan an allowed object or QR spot to dismiss."
            content.categoryIdentifier = "ALARM"
            content.userInfo = ["kind": "live"]
            if health.hasCriticalEntitlement && health.criticalSetting == .enabled {
                if #available(iOS 15.0, *) { content.interruptionLevel = .critical }
                content.sound = UNNotificationSound.defaultCriticalSound(withAudioVolume: 1.0)
            } else {
                content.sound = .default
            }

            var date = DateComponents()
            date.calendar = Calendar.current
            date.weekday = weekdayNumber(for: index)
            date.hour = alarm.timeMinutes / 60
            date.minute = alarm.timeMinutes % 60
            let trigger = UNCalendarNotificationTrigger(dateMatching: date, repeats: true)
            let request = UNNotificationRequest(
                identifier: scheduledIdentifier(for: index),
                content: content,
                trigger: trigger
            )
            try await center.add(request)
        }
    }

    func nextTriggerDate(for alarm: AlarmConfig, now: Date = Date()) -> Date? {
        let calendar = Calendar.current
        let currentWeekday = calendar.component(.weekday, from: now)
        let currentMinutes = calendar.component(.hour, from: now) * 60 + calendar.component(.minute, from: now)
        for offset in 0..<14 {
            guard let day = calendar.date(byAdding: .day, value: offset, to: now) else { continue }
            let weekday = calendar.component(.weekday, from: day)
            let repeatIndex = repeatIndex(forWeekdayNumber: weekday)
            guard alarm.repeatDays.indices.contains(repeatIndex), alarm.repeatDays[repeatIndex] else { continue }
            if offset == 0 && weekday == currentWeekday && alarm.timeMinutes <= currentMinutes { continue }
            var components = calendar.dateComponents([.year, .month, .day], from: day)
            components.hour = alarm.timeMinutes / 60
            components.minute = alarm.timeMinutes % 60
            return calendar.date(from: components)
        }
        return nil
    }

    private var scheduledIdentifiers: [String] {
        (0..<7).map { scheduledIdentifier(for: $0) }
    }

    private func scheduledIdentifier(for repeatIndex: Int) -> String {
        "superalarm-ios-weekday-\(repeatIndex)"
    }

    private func weekdayNumber(for repeatIndex: Int) -> Int {
        [2, 3, 4, 5, 6, 7, 1][repeatIndex]
    }

    private func repeatIndex(forWeekdayNumber weekday: Int) -> Int {
        [1: 6, 2: 0, 3: 1, 4: 2, 5: 3, 6: 4, 7: 5][weekday] ?? 0
    }
}

enum EntitlementReader {
    static var hasCriticalAlerts: Bool {
        provisioningEntitlements?["com.apple.developer.usernotifications.critical-alerts"] as? Bool == true
    }

    private static var provisioningEntitlements: [String: Any]? {
        guard
            let profileURL = Bundle.main.url(forResource: "embedded", withExtension: "mobileprovision"),
            let profileData = try? Data(contentsOf: profileURL),
            let profileText = String(data: profileData, encoding: .isoLatin1),
            let plistStart = profileText.range(of: "<?xml"),
            let plistEnd = profileText.range(of: "</plist>")
        else {
            return nil
        }

        let plistRange = plistStart.lowerBound..<plistEnd.upperBound
        guard
            let plistData = String(profileText[plistRange]).data(using: .utf8),
            let profile = try? PropertyListSerialization.propertyList(from: plistData, format: nil) as? [String: Any]
        else {
            return nil
        }
        return profile["Entitlements"] as? [String: Any]
    }
}

final class AlarmAudioService {
    private let engine = AVAudioEngine()
    private var vibrationTimer: Timer?

    func start(vibrates: Bool) {
        do {
            try AVAudioSession.sharedInstance().setCategory(.playback, mode: .default, options: [.duckOthers])
            try AVAudioSession.sharedInstance().setActive(true)
            startTone()
        } catch {
            AudioServicesPlaySystemSound(1005)
        }
        if vibrates { startVibration() }
    }

    func stop() {
        vibrationTimer?.invalidate()
        vibrationTimer = nil
        engine.stop()
        engine.reset()
        try? AVAudioSession.sharedInstance().setActive(false, options: [.notifyOthersOnDeactivation])
    }

    private func startTone() {
        guard !engine.isRunning else { return }
        let sampleRate = AVAudioSession.sharedInstance().sampleRate > 0 ? AVAudioSession.sharedInstance().sampleRate : 44_100
        var phase = 0.0
        let frequency = 880.0
        let source = AVAudioSourceNode { _, _, frameCount, audioBufferList -> OSStatus in
            let ablPointer = UnsafeMutableAudioBufferListPointer(audioBufferList)
            for frame in 0..<Int(frameCount) {
                let value = Float(sin(phase) * 0.22)
                phase += 2.0 * Double.pi * frequency / sampleRate
                if phase > 2.0 * Double.pi { phase -= 2.0 * Double.pi }
                for buffer in ablPointer {
                    let pointer = buffer.mData?.assumingMemoryBound(to: Float.self)
                    pointer?[frame] = value
                }
            }
            return noErr
        }
        let format = AVAudioFormat(standardFormatWithSampleRate: sampleRate, channels: 1)!
        engine.attach(source)
        engine.connect(source, to: engine.mainMixerNode, format: format)
        try? engine.start()
    }

    private func startVibration() {
        AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        vibrationTimer = Timer.scheduledTimer(withTimeInterval: 1.6, repeats: true) { _ in
            AudioServicesPlaySystemSound(kSystemSoundID_Vibrate)
        }
    }
}

struct HapticsService {
    func selection() { UISelectionFeedbackGenerator().selectionChanged() }
    func success() { UINotificationFeedbackGenerator().notificationOccurred(.success) }
    func warning() { UINotificationFeedbackGenerator().notificationOccurred(.warning) }
    func error() { UINotificationFeedbackGenerator().notificationOccurred(.error) }
}

// MARK: - Root UI

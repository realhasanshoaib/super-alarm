import SwiftUI
import UserNotifications

struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore
    @State private var sheet: OnboardingSheet?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ProgressView(
                    value: Double(store.state.onboardingStep.rawValue + 1),
                    total: Double(OnboardingStep.ready.rawValue + 1)
                )
                .tint(.orange)
                stepContent
            }
            .navigationTitle("Super Alarm")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if store.state.onboardingStep != .welcome {
                        Button {
                            store.setOnboardingStep(previousStep)
                        } label: {
                            Label("Back", systemImage: "chevron.left")
                        }
                    }
                }
                ToolbarItem(placement: .topBarTrailing) {
                    Text("\(store.state.onboardingStep.rawValue + 1) of \(OnboardingStep.ready.rawValue + 1)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .tint(.orange)
        .interactiveDismissDisabled()
        .sheet(item: $sheet) { route in
            switch route {
            case .object:
                AddObjectView()
            case .qr:
                AddQrSpotView()
            }
        }
    }

    @ViewBuilder
    private var stepContent: some View {
        switch store.state.onboardingStep {
        case .welcome:
            WelcomeOnboardingStep {
                store.setOnboardingStep(.dismissalMethod)
            }
        case .dismissalMethod:
            DismissalMethodOnboardingStep(
                onObject: { sheet = .object },
                onQr: { sheet = .qr }
            )
        case .alarm:
            AlarmOnboardingStep {
                store.setOnboardingStep(.phoneSetup)
            }
        case .phoneSetup:
            PhoneSetupOnboardingStep()
        case .wakeUpTest:
            WakeUpTestOnboardingStep()
        case .ready:
            ReadyOnboardingStep()
        }
    }

    private var previousStep: OnboardingStep {
        switch store.state.onboardingStep {
        case .welcome, .dismissalMethod: return .welcome
        case .alarm: return .dismissalMethod
        case .phoneSetup: return .alarm
        case .wakeUpTest: return .phoneSetup
        case .ready: return .wakeUpTest
        }
    }
}

private enum OnboardingSheet: Int, Identifiable {
    case object
    case qr

    var id: Int { rawValue }
}

private struct WelcomeOnboardingStep: View {
    let onContinue: () -> Void

    var body: some View {
        OnboardingPage {
            Spacer()
            Image(systemName: "alarm.waves.left.and.right.fill")
                .font(.system(size: 64))
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            Text("Wake up knowing it works")
                .font(.largeTitle.weight(.semibold))
                .multilineTextAlignment(.center)
                .accessibilityAddTraits(.isHeader)
            Text("Set a wake-up task, prepare this iPhone, and prove the alarm works before tonight.")
                .font(.title3)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
            VStack(spacing: 12) {
                OnboardingFeatureRow(
                    systemImage: "lock.shield",
                    title: "Private by design",
                    detail: "Object scans stay on this iPhone."
                )
                OnboardingFeatureRow(
                    systemImage: "checkmark.seal",
                    title: "Tested before bedtime",
                    detail: "You’ll complete a real notification test."
                )
            }
            Spacer()
            OnboardingPrimaryButton("Set up my alarm", action: onContinue)
        }
    }
}

private struct DismissalMethodOnboardingStep: View {
    let onObject: () -> Void
    let onQr: () -> Void

    var body: some View {
        OnboardingPage(alignment: .leading) {
            Text("Choose how to dismiss")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("Pick something you must get out of bed to reach.")
                .font(.title3)
                .foregroundStyle(.secondary)
            Spacer()
            Button(action: onObject) {
                OnboardingChoiceLabel(
                    systemImage: "camera.viewfinder",
                    title: "Scan an object",
                    detail: "Use your fridge, sink, or coffee machine. No printer needed."
                )
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            Button(action: onQr) {
                OnboardingChoiceLabel(
                    systemImage: "qrcode",
                    title: "Create a QR spot",
                    detail: "Print or display a code somewhere away from bed."
                )
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
        }
    }
}

private struct AlarmOnboardingStep: View {
    @EnvironmentObject private var store: AppStore
    let onContinue: () -> Void

    var body: some View {
        OnboardingPage(alignment: .leading) {
            Text("Set your first alarm")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("Choose a wake-up time and repeat days. You can change these later.")
                .foregroundStyle(.secondary)
            DatePicker(
                "Wake-up time",
                selection: Binding(get: alarmDate, set: store.setTime),
                displayedComponents: .hourAndMinute
            )
            .datePickerStyle(.wheel)
            Text("Repeat")
                .font(.headline)
            WeekdayPickerView(selected: store.state.alarm.repeatDays, onToggle: store.toggleRepeatDay)
            Spacer()
            OnboardingPrimaryButton(
                "Prepare this iPhone",
                enabled: store.state.alarm.repeatDays.contains(true),
                action: onContinue
            )
        }
    }

    private func alarmDate() -> Date {
        var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        components.hour = store.state.alarm.timeMinutes / 60
        components.minute = store.state.alarm.timeMinutes % 60
        return Calendar.current.date(from: components) ?? Date()
    }
}

private struct PhoneSetupOnboardingStep: View {
    @EnvironmentObject private var store: AppStore

    private var notificationsReady: Bool {
        [.authorized, .provisional, .ephemeral].contains(store.health.authorizationStatus)
    }

    var body: some View {
        OnboardingPage(alignment: .leading) {
            Text("Make the alarm reliable")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("Allow alerts and sound, then run a notification test before bedtime.")
                .foregroundStyle(.secondary)
            OnboardingPermissionRow(
                systemImage: "bell.badge",
                title: "Notifications",
                detail: "Delivers the alarm while this app is closed.",
                value: store.health.notificationLabel,
                ready: notificationsReady,
                action: store.requestNotifications
            )
            OnboardingPermissionRow(
                systemImage: "speaker.wave.3",
                title: "Alert sound",
                detail: "Uses Critical Alerts when the entitlement is available.",
                value: store.health.hasCriticalEntitlement ? store.health.criticalLabel : "Standard alert",
                ready: store.health.soundSetting == .enabled,
                action: store.requestNotifications
            )
            Spacer()
            OnboardingPrimaryButton(
                notificationsReady ? "Run wake-up test" : "Allow notifications first",
                enabled: notificationsReady,
                action: store.scheduleOnboardingTest
            )
        }
        .task { await store.refreshHealth() }
    }
}

private struct WakeUpTestOnboardingStep: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        TimelineView(.periodic(from: .now, by: 1)) { context in
            OnboardingPage {
                Spacer()
                Image(systemName: store.onboardingTestPassed ? "checkmark.seal.fill" : "iphone.and.arrow.forward")
                    .font(.system(size: 64))
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
                Text(store.onboardingTestPassed ? "Your wake-up test passed" : "Lock your iPhone")
                    .font(.largeTitle.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(testDetail(at: context.date))
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                if store.onboardingTestPassed {
                    VStack(spacing: 12) {
                        OnboardingResultRow("Notification delivery")
                        OnboardingResultRow("Sound and tap-through")
                        OnboardingResultRow("Target dismissal")
                    }
                }
                Spacer()
                if store.onboardingTestPassed {
                    OnboardingPrimaryButton("Finish setup") {
                        store.setOnboardingStep(.ready)
                    }
                } else if shouldOfferRetry(at: context.date) {
                    OnboardingPrimaryButton("Schedule another 10-second test") {
                        store.scheduleOnboardingTest()
                    }
                }
            }
        }
    }

    private func testDetail(at now: Date) -> String {
        if store.onboardingTestPassed {
            return "Notification delivery, sound, app opening, and dismissal all worked."
        }
        guard let scheduledAt = store.state.onboardingTestScheduledAt else {
            return "Schedule the test, lock this iPhone, tap the notification, and dismiss it with your target."
        }
        let seconds = max(0, Int(ceil(scheduledAt.timeIntervalSince(now))))
        if seconds > 0 {
            return "The notification arrives in \(seconds)s. Tap it, then dismiss the alarm with your enrolled target."
        }
        return "Tap the Super Alarm notification, then dismiss the alarm with your enrolled target."
    }

    private func shouldOfferRetry(at now: Date) -> Bool {
        guard let scheduledAt = store.state.onboardingTestScheduledAt else { return true }
        return now.timeIntervalSince(scheduledAt) > 60
    }
}

private struct ReadyOnboardingStep: View {
    @EnvironmentObject private var store: AppStore

    private var targetName: String {
        if let target = store.allowedObjectTargets.first { return target.label }
        if let spot = store.allowedLocations.first { return spot.label }
        return "wake-up target"
    }

    private var timeText: String {
        var components = DateComponents()
        components.hour = store.state.alarm.timeMinutes / 60
        components.minute = store.state.alarm.timeMinutes % 60
        let date = Calendar.current.date(from: components) ?? Date()
        return date.formatted(date: .omitted, time: .shortened)
    }

    var body: some View {
        OnboardingPage {
            Spacer()
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 72))
                .foregroundStyle(.orange)
                .accessibilityHidden(true)
            Text("Ready for tonight")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("\(timeText) · dismissed with \(targetName)")
                .font(.title3)
                .foregroundStyle(.secondary)
            OnboardingFeatureRow(
                systemImage: "checkmark.shield",
                title: "Alarm protection ready",
                detail: "This iPhone passed its wake-up test."
            )
            Spacer()
            OnboardingPrimaryButton("Go to alarm", action: store.completeOnboarding)
        }
    }
}

private struct OnboardingPage<Content: View>: View {
    let alignment: HorizontalAlignment
    @ViewBuilder let content: Content

    init(
        alignment: HorizontalAlignment = .center,
        @ViewBuilder content: () -> Content
    ) {
        self.alignment = alignment
        self.content = content()
    }

    var body: some View {
        VStack(alignment: alignment, spacing: 20) {
            content
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

private struct OnboardingChoiceLabel: View {
    let systemImage: String
    let title: String
    let detail: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .font(.title2)
                .frame(width: 34)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.caption)
                    .multilineTextAlignment(.leading)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, minHeight: 58)
    }
}

private struct OnboardingFeatureRow: View {
    let systemImage: String
    let title: String
    let detail: String

    var body: some View {
        HStack(spacing: 14) {
            Image(systemName: systemImage)
                .foregroundStyle(.orange)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }
            Spacer()
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct OnboardingPermissionRow: View {
    let systemImage: String
    let title: String
    let detail: String
    let value: String
    let ready: Bool
    let action: () -> Void

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(.orange)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
            Spacer()
            if ready {
                Label(value, systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(.green)
            } else {
                Button("Allow", action: action)
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .background(.thinMaterial, in: RoundedRectangle(cornerRadius: 16))
    }
}

private struct OnboardingResultRow: View {
    let title: String

    init(_ title: String) {
        self.title = title
    }

    var body: some View {
        HStack {
            Label(title, systemImage: "checkmark.circle.fill")
            Spacer()
            Text("Passed")
                .foregroundStyle(.green)
        }
    }
}

private struct OnboardingPrimaryButton: View {
    let title: String
    let enabled: Bool
    let action: () -> Void

    init(_ title: String, enabled: Bool = true, action: @escaping () -> Void) {
        self.title = title
        self.enabled = enabled
        self.action = action
    }

    var body: some View {
        Button(action: action) {
            Text(title)
                .frame(maxWidth: .infinity, minHeight: 50)
        }
        .buttonStyle(.borderedProminent)
        .controlSize(.large)
        .disabled(!enabled)
    }
}

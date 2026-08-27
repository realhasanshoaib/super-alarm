import SwiftUI
import UserNotifications

struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore
    @State private var sheet: OnboardingSheet?
    @State private var showSkipConfirmation = false

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ProgressView(
                    value: Double(store.state.onboardingStep.rawValue + 1),
                    total: Double(OnboardingStep.ready.rawValue + 1)
                )
                ScrollView {
                    stepContent
                }
                .id(store.state.onboardingStep)
                .scrollDismissesKeyboard(.interactively)
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
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Skip") {
                        store.track(ProductEvent(.onboardingSkipOpened, onboardingStep: store.state.onboardingStep.rawValue, source: .onboarding))
                        showSkipConfirmation = true
                    }
                    Text("\(store.state.onboardingStep.rawValue + 1) of \(OnboardingStep.ready.rawValue + 1)")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .interactiveDismissDisabled()
        .confirmationDialog(
            "Skip setup?",
            isPresented: $showSkipConfirmation,
            titleVisibility: .visible
        ) {
            Button("Skip Setup", role: .destructive) { store.skipOnboarding() }
            Button("Continue Setup", role: .cancel) {
                store.track(ProductEvent(.onboardingSkipCancelled, onboardingStep: store.state.onboardingStep.rawValue, source: .onboarding))
            }
        } message: {
            Text("You can use Super Alarm now, but you still need a dismissal target and alarm permissions before relying on it. Run setup again from Settings at any time.")
        }
        .task(id: store.state.onboardingStep) {
            store.track(ProductEvent(
                .onboardingStepViewed,
                surface: telemetrySurface,
                onboardingStep: store.state.onboardingStep.rawValue,
                source: .onboarding
            ))
        }
        .onAppear {
            store.track(ProductEvent(.onboardingStarted, source: .onboarding))
        }
        .sheet(item: $sheet) { route in
            switch route {
            case .object:
                AddObjectView()
            case .qr:
                OnboardingQrSetupView()
            }
        }
        .fullScreenCover(isPresented: $store.isRingingPresented) {
            RingingView()
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

    private var telemetrySurface: TelemetrySurface {
        switch store.state.onboardingStep {
        case .welcome: return .onboardingWelcome
        case .dismissalMethod: return .onboardingDismissalMethod
        case .alarm: return .onboardingAlarm
        case .phoneSetup: return .onboardingPhoneSetup
        case .wakeUpTest: return .onboardingWakeUpTest
        case .ready: return .onboardingReady
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
            BrandMark(size: 96)
            Text("Wake up knowing it works")
                .font(.largeTitle.weight(.semibold))
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            Text("Set a wake-up task, prepare this iPhone, and prove the alarm works before tonight.")
                .font(.title3)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .fixedSize(horizontal: false, vertical: true)
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
            .brandProminentButtonStyle()
            .controlSize(.large)
            Button(action: onQr) {
                OnboardingChoiceLabel(
                    systemImage: "qrcode",
                    title: "Create a QR spot",
                    detail: "Print or display a code somewhere away from bed."
                )
            }
            .brandGlassButtonStyle()
            .controlSize(.large)
        }
    }
}

private struct OnboardingQrSetupView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    @State private var label = ""
    @State private var createdSpot: QrLocation?
    @State private var didTrackStart = false

    var body: some View {
        NavigationStack {
            Group {
                if let createdSpot {
                    ScrollView {
                        VStack(spacing: 20) {
                            Text("Screenshot or show this code on another device before continuing. You’ll scan it during the wake-up test.")
                                .foregroundStyle(.secondary)
                            QrCodeView(payload: createdSpot.payload)
                                .frame(maxWidth: .infinity, minHeight: 280)
                                .accessibilityLabel("QR code for \(createdSpot.label)")
                            ShareLink(item: createdSpot.payload) {
                                Label("Share QR payload", systemImage: "square.and.arrow.up")
                            }
                            .buttonStyle(.bordered)
                        }
                        .padding(24)
                    }
                } else {
                    Form {
                        Section {
                            TextField("Spot name", text: $label)
                                .textInputAutocapitalization(.words)
                        } footer: {
                            Text("Use a place you must physically reach in the morning, like Bathroom sink or Kitchen kettle.")
                        }
                    }
                }
            }
            .navigationTitle(createdSpot == nil ? "Create QR Spot" : "Save Your QR Spot")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(createdSpot == nil ? "Cancel" : "Done") {
                        if createdSpot == nil {
                            store.track(ProductEvent(.dismissalEnrollmentCancelled, targetType: .qr, source: .onboarding, outcome: .cancelled))
                        }
                        dismiss()
                    }
                }
                if createdSpot == nil {
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Create") {
                            createdSpot = store.addQrSpot(label: label)
                        }
                        .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
        .onAppear {
            guard !didTrackStart else { return }
            didTrackStart = true
            store.track(ProductEvent(.dismissalEnrollmentStarted, targetType: .qr, source: .onboarding))
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
    @Environment(\.scenePhase) private var scenePhase

    private var authorizationReady: Bool {
        [.authorized, .provisional, .ephemeral].contains(store.health.authorizationStatus)
    }

    private var soundReady: Bool {
        store.health.soundSetting == .enabled
    }

    private var setupReady: Bool {
        authorizationReady && soundReady
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
                ready: authorizationReady,
                action: notificationAction
            )
            OnboardingPermissionRow(
                systemImage: "speaker.wave.3",
                title: "Alert sound",
                detail: "Uses Critical Alerts when the entitlement is available.",
                value: soundReady
                    ? (store.health.hasCriticalEntitlement ? store.health.criticalLabel : "On")
                    : "Off",
                ready: soundReady,
                action: soundAction
            )
            Spacer()
            OnboardingPrimaryButton(
                setupReady ? "Run wake-up test" : "Prepare notifications and sound",
                enabled: setupReady,
                action: store.scheduleOnboardingTest
            )
        }
        .task { await store.refreshHealth() }
        .onChange(of: scenePhase) { phase in
            guard phase == .active else { return }
            Task { await store.refreshHealth() }
        }
    }

    private func notificationAction() {
        if store.health.authorizationStatus == .denied {
            store.openAppSettings()
        } else {
            store.requestNotifications()
        }
    }

    private func soundAction() {
        if authorizationReady {
            store.openAppSettings()
        } else {
            notificationAction()
        }
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
                    .foregroundStyle(store.onboardingTestPassed ? Color.brandGreen : .accentColor)
                    .accessibilityHidden(true)
                Text(store.onboardingTestPassed ? "Your wake-up test passed" : "Lock your iPhone")
                    .font(.largeTitle.weight(.semibold))
                    .multilineTextAlignment(.center)
                    .accessibilityAddTraits(.isHeader)
                Text(testDetail(at: context.date))
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
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
                .foregroundStyle(Color.brandGreen)
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
        .frame(maxWidth: .infinity, alignment: .top)
        .fixedSize(horizontal: false, vertical: true)
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
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .padding()
        .brandGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
                .foregroundStyle(.tint)
                .frame(width: 28)
            VStack(alignment: .leading, spacing: 3) {
                Text(title)
                    .font(.headline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
            if ready {
                Label(value, systemImage: "checkmark.circle.fill")
                    .font(.caption)
                    .foregroundStyle(Color.brandGreen)
            } else {
                Button("Allow", action: action)
                    .buttonStyle(.bordered)
            }
        }
        .padding()
        .fixedSize(horizontal: false, vertical: true)
        .brandGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
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
                .foregroundStyle(Color.brandGreen)
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
        .brandProminentButtonStyle()
        .controlSize(.large)
        .disabled(!enabled)
    }
}

#if DEBUG
#Preview("Onboarding") {
    OnboardingView()
        .environmentObject(SuperAlarmPreviewData.store(hasCompletedOnboarding: false))
}
#endif

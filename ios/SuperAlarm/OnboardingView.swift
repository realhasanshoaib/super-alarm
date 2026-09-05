import SwiftUI
import UserNotifications

struct OnboardingView: View {
    @EnvironmentObject private var store: AppStore
    @State private var sheet: OnboardingSheet?

    var body: some View {
        NavigationStack {
            VStack(spacing: 0) {
                ProgressView(value: Double(stepNumber), total: 3)
                ScrollView {
                    stepContent
                }
                .id(store.state.onboardingStep)
                .scrollDismissesKeyboard(.interactively)
            }
            .navigationTitle("Set up your alarm")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .topBarLeading) {
                    if stepNumber > 1 {
                        Button {
                            store.setOnboardingStep(previousStep)
                        } label: {
                            Label("Back", systemImage: "chevron.left")
                        }
                    }
                }
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Finish later") {
                        store.track(ProductEvent(.onboardingSkipOpened, onboardingStep: store.state.onboardingStep.rawValue, source: .onboarding))
                        store.finishOnboardingLater()
                    }
                    Text("\(stepNumber) of 3")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
            }
        }
        .interactiveDismissDisabled()
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
                AddObjectView(onUseQr: { sheet = .qr })
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
        switch normalizedStep {
        case .dismissalMethod:
            DismissalMethodOnboardingStep(
                onObject: { sheet = .object },
                onQr: { sheet = .qr },
                onContinue: { store.setOnboardingStep(.alarm) }
            )
        case .alarm:
            AlarmOnboardingStep {
                store.setOnboardingStep(.phoneSetup)
            }
        case .phoneSetup:
            PhoneSetupOnboardingStep()
        case .welcome, .wakeUpTest, .ready:
            EmptyView()
        }
    }

    private var normalizedStep: OnboardingStep {
        switch store.state.onboardingStep {
        case .welcome, .dismissalMethod: return .dismissalMethod
        case .alarm: return .alarm
        case .phoneSetup, .wakeUpTest, .ready: return .phoneSetup
        }
    }

    private var stepNumber: Int {
        switch normalizedStep {
        case .dismissalMethod: return 1
        case .alarm: return 2
        case .phoneSetup: return 3
        case .welcome, .wakeUpTest, .ready: return 1
        }
    }

    private var previousStep: OnboardingStep {
        switch normalizedStep {
        case .alarm: return .dismissalMethod
        case .phoneSetup: return .alarm
        case .welcome, .dismissalMethod, .wakeUpTest, .ready: return .dismissalMethod
        }
    }

    private var telemetrySurface: TelemetrySurface {
        switch normalizedStep {
        case .dismissalMethod: return .onboardingDismissalMethod
        case .alarm: return .onboardingAlarm
        case .phoneSetup: return .onboardingPhoneSetup
        case .welcome, .wakeUpTest, .ready: return .onboardingDismissalMethod
        }
    }
}

private enum OnboardingSheet: Int, Identifiable {
    case object
    case qr

    var id: Int { rawValue }
}

private struct DismissalMethodOnboardingStep: View {
    @EnvironmentObject private var store: AppStore
    let onObject: () -> Void
    let onQr: () -> Void
    let onContinue: () -> Void

    var body: some View {
        OnboardingPage(alignment: .leading) {
            Text("Choose your dismissal target")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("Pick something away from bed. The alarm challenge ends only after you scan it.")
                .font(.title3)
                .foregroundStyle(.secondary)
            if store.activationReadiness.hasDismissalTarget {
                Label("Target ready", systemImage: "checkmark.circle.fill")
                    .font(.headline)
                    .foregroundStyle(Color.brandGreen)
                    .padding(.vertical, 8)
            }
            Spacer()
            Button(action: onObject) {
                OnboardingChoiceLabel(
                    systemImage: "camera.viewfinder",
                    title: "Scan an object",
                    detail: "Use a fridge, sink, or coffee machine. No printer needed."
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
            if store.activationReadiness.hasDismissalTarget {
                OnboardingPrimaryButton("Continue", action: onContinue)
            }
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
                            QrCodeView(payload: createdSpot.payload)
                                .frame(width: 230, height: 230)
                            Text(createdSpot.label)
                                .font(.title2.weight(.semibold))
                            Text("Save or print this code and place it away from bed.")
                                .foregroundStyle(.secondary)
                                .multilineTextAlignment(.center)
                            Button("Done") { dismiss() }
                                .brandProminentButtonStyle()
                                .controlSize(.large)
                        }
                        .padding(24)
                    }
                    .navigationTitle("QR spot ready")
                } else {
                    Form {
                        Section {
                            TextField("Spot name", text: $label)
                                .textInputAutocapitalization(.words)
                        } footer: {
                            Text("Use a place you must physically reach, such as Bathroom sink or Kitchen kettle.")
                        }
                    }
                    .navigationTitle("Create QR spot")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") {
                                store.track(ProductEvent(.dismissalEnrollmentCancelled, targetType: .qr, source: .onboarding, outcome: .cancelled, cancellationReason: .unspecified))
                                dismiss()
                            }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Create") {
                                createdSpot = store.addQrSpot(label: label)
                            }
                            .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
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
            Text("Choose the time")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("Select your wake-up time and at least one repeat day.")
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
                "Continue",
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
        store.activationReadiness.notificationsAuthorized
    }

    private var alertsReady: Bool {
        store.activationReadiness.notificationAlertsEnabled
    }

    private var soundsReady: Bool {
        store.activationReadiness.notificationSoundsEnabled
    }

    var body: some View {
        OnboardingPage(alignment: .leading) {
            Text("Enable alarm notifications")
                .font(.largeTitle.weight(.semibold))
                .accessibilityAddTraits(.isHeader)
            Text("On iPhone, Super Alarm sends a standard notification. You must tap it to start continuous sound and the dismissal challenge.")
                .foregroundStyle(.secondary)
            Label("Silent mode or Focus can silence a regular notification.", systemImage: "exclamationmark.triangle")
                .font(.callout)
                .foregroundStyle(.secondary)
            OnboardingPermissionRow(
                systemImage: "bell.badge",
                title: "Notifications",
                detail: "Allows Super Alarm to notify you at the selected time.",
                value: store.health.notificationLabel,
                ready: authorizationReady,
                action: notificationAction
            )
            OnboardingPermissionRow(
                systemImage: "rectangle.3.group.bubble",
                title: "Alerts",
                detail: "Shows the notification you must tap.",
                value: alertsReady ? "On" : "Off",
                ready: alertsReady,
                action: settingsAction
            )
            OnboardingPermissionRow(
                systemImage: "speaker.wave.3",
                title: "Notification sound",
                detail: "Plays the standard iOS notification sound before you tap.",
                value: soundsReady ? "On" : "Off",
                ready: soundsReady,
                action: settingsAction
            )
            Spacer()
            OnboardingPrimaryButton(
                store.isActivatingAlarm ? "Scheduling…" : "Enable my alarm",
                enabled: store.activationReadiness.prerequisitesReady && !store.isActivatingAlarm,
                action: store.completeOnboarding
            )
        }
        .task {
            await store.refreshHealth()
            store.observePhoneSetupBlockers()
        }
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

    private func settingsAction() {
        if authorizationReady {
            store.openAppSettings()
        } else {
            notificationAction()
        }
    }
}

private struct OnboardingPage<Content: View>: View {
    let alignment: HorizontalAlignment
    @ViewBuilder let content: Content

    init(alignment: HorizontalAlignment = .center, @ViewBuilder content: () -> Content) {
        self.alignment = alignment
        self.content = content()
    }

    var body: some View {
        VStack(alignment: alignment, spacing: 20) {
            content
        }
        .frame(maxWidth: .infinity, minHeight: 620, alignment: .top)
        .padding(24)
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
                Text(title).font(.headline)
                Text(detail)
                    .font(.caption)
                    .foregroundStyle(.secondary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Spacer()
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.vertical, 8)
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
        Button(action: action) {
            HStack(spacing: 14) {
                Image(systemName: systemImage)
                    .font(.title3)
                    .foregroundStyle(ready ? Color.brandGreen : .accentColor)
                    .frame(width: 34)
                VStack(alignment: .leading, spacing: 3) {
                    Text(title).font(.headline)
                    Text(detail)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .fixedSize(horizontal: false, vertical: true)
                }
                Spacer()
                Text(value)
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(ready ? Color.brandGreen : .secondary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .brandGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
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
                .frame(maxWidth: .infinity, minHeight: 48)
        }
        .brandProminentButtonStyle()
        .controlSize(.large)
        .disabled(!enabled)
    }
}

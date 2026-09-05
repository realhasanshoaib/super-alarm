import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit

struct RootView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        TabView {
            NavigationStack { AlarmEditorView() }
                .tabItem { Label("Alarm", systemImage: "alarm") }
            NavigationStack { SettingsHealthView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .sheet(item: $store.activeSheet) { route in
            switch route {
            case .addObject:
                AddObjectView(onUseQr: { store.activeSheet = .addSpot })
            case .addSpot:
                AddQrSpotView()
            case .diagnostics:
                DiagnosticsExportView(data: store.diagnosticsExportData())
            case .spot(let spot):
                QrSpotDetailView(spot: spot)
            }
        }
        .fullScreenCover(isPresented: regularRingingBinding) {
            RingingView()
        }
        .fullScreenCover(isPresented: onboardingBinding) {
            OnboardingView()
        }
        .alert("Super Alarm", isPresented: alertBinding) {
            Button("OK", role: .cancel) { store.alertMessage = nil }
        } message: {
            Text(store.alertMessage ?? "")
        }
    }

    private var onboardingBinding: Binding<Bool> {
        Binding(
            get: { store.shouldPresentOnboarding },
            set: { _ in }
        )
    }

    private var regularRingingBinding: Binding<Bool> {
        Binding(
            get: { !store.shouldPresentOnboarding && store.isRingingPresented },
            set: { if !$0 { store.isRingingPresented = false } }
        )
    }

    private var alertBinding: Binding<Bool> {
        Binding(get: { store.alertMessage != nil }, set: { if !$0 { store.alertMessage = nil } })
    }
}

struct AlarmEditorView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        List {
            if store.shouldShowReadinessCard {
                AlarmReadinessCard()
            }

            Section {
                VStack(spacing: 18) {
                    SignatureDialView(minutes: store.state.alarm.timeMinutes, isEnabled: store.state.alarm.isEnabled)
                        .frame(maxWidth: .infinity)
                        .accessibilityLabel("Alarm time \(timeText)")
                    Text(store.nextAlarmSummary)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                .padding(.vertical, 18)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }

            Section {
                Toggle(isOn: Binding(get: { store.state.alarm.isEnabled }, set: store.setEnabled)) {
                    Label("Alarm", systemImage: "bell")
                }
                DatePicker(
                    "Time",
                    selection: Binding(get: alarmDate, set: store.setTime),
                    displayedComponents: .hourAndMinute
                )
                TextField("Label", text: Binding(get: { store.state.alarm.label }, set: store.setLabel))
                Toggle(isOn: Binding(get: { store.state.alarm.vibrates }, set: store.setVibrates)) {
                    Label("In-app vibration", systemImage: "iphone.radiowaves.left.and.right")
                }
            }

            Section("How this alarm works") {
                Label("Tap the notification to start alarm sound", systemImage: "hand.tap")
                Text("Standard notifications can be silenced by Silent mode or Focus. Open the notification to start continuous sound and the scan challenge.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            if store.state.hasCompletedOnboarding {
                PhoneVerificationCard()
            }

            Section("Repeat") {
                WeekdayPickerView(selected: store.state.alarm.repeatDays) { index in
                    store.toggleRepeatDay(index)
                }
                .padding(.vertical, 6)
            }

            Section("Objects") {
                if store.state.objectTargets.isEmpty {
                    ContentUnavailableRow(
                        systemImage: "camera.viewfinder",
                        title: "No objects",
                        message: "Scan a fixed household object for printer-free dismissal."
                    )
                } else {
                    ForEach(store.state.objectTargets) { target in
                        Toggle(isOn: Binding(
                            get: { store.state.alarm.allowedObjectTargetIds.contains(target.id) },
                            set: { store.setObjectAllowed(target, allowed: $0) }
                        )) {
                            VStack(alignment: .leading, spacing: 3) {
                                Text(target.label)
                                Text("\(target.fingerprints.count) views stored on device")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                    }
                    .onDelete { offsets in
                        offsets.map { store.state.objectTargets[$0] }.forEach(store.deleteObjectTarget)
                    }
                }
                Button {
                    store.activeSheet = .addObject
                } label: {
                    Label("Scan an Object", systemImage: "camera.viewfinder")
                }
            }

            Section("QR Spots") {
                if store.state.qrLocations.isEmpty {
                    ContentUnavailableRow(
                        systemImage: "qrcode",
                        title: "No QR spots",
                        message: "Create a spot if you want QR dismissal too."
                    )
                } else {
                    ForEach(store.state.qrLocations) { spot in
                        Button {
                            store.activeSheet = .spot(spot)
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: store.state.alarm.allowedLocationIds.contains(spot.id) ? "mappin.and.ellipse" : "mappin.slash")
                                    .foregroundStyle(.tint)
                                    .frame(width: 28)
                                VStack(alignment: .leading) {
                                    Text(spot.label)
                                        .foregroundStyle(.primary)
                                    Text(store.state.alarm.allowedLocationIds.contains(spot.id) ? "Allowed" : "Not allowed")
                                        .font(.caption)
                                        .foregroundStyle(.secondary)
                                }
                                Spacer()
                                Image(systemName: "chevron.right")
                                    .font(.caption.weight(.semibold))
                                    .foregroundStyle(.tertiary)
                            }
                        }
                        .accessibilityLabel("\(spot.label), \(store.state.alarm.allowedLocationIds.contains(spot.id) ? "allowed" : "not allowed")")
                    }
                    .onDelete { offsets in
                        offsets.map { store.state.qrLocations[$0] }.forEach(store.deleteQrSpot)
                    }
                }
                Button {
                    store.activeSheet = .addSpot
                } label: {
                    Label("Add QR Spot", systemImage: "plus")
                }
            }
        }
        .navigationTitle("Super Alarm")
        .onAppear { store.track(ProductEvent(.surfaceViewed, surface: .alarm, source: .app)) }
        .toolbar {
            ToolbarItem(placement: .topBarTrailing) {
                Button {
                    store.requestNotifications()
                } label: {
                    Image(systemName: "bell.badge")
                }
                .accessibilityLabel("Request notifications")
            }
        }
    }

    private var timeText: String {
        DateFormatter.localizedString(from: alarmDate(), dateStyle: .none, timeStyle: .short)
    }

    private func alarmDate() -> Date {
        var components = Calendar.current.dateComponents([.year, .month, .day], from: Date())
        components.hour = store.state.alarm.timeMinutes / 60
        components.minute = store.state.alarm.timeMinutes % 60
        return Calendar.current.date(from: components) ?? Date()
    }
}

private struct AlarmReadinessCard: View {
    @EnvironmentObject private var store: AppStore

    private var missingLabels: [String] {
        var labels = store.activationReadiness.missingRequirements.map(\.label)
        if !store.state.hasCompletedOnboarding && store.activationReadiness.hasDismissalTarget &&
            [.welcome, .dismissalMethod, .alarm].contains(store.state.onboardingStep) {
            labels.insert("Confirm alarm time", at: 0)
        }
        if labels.isEmpty && !store.state.hasCompletedOnboarding {
            return ["Enable and schedule the alarm"]
        }
        if labels.isEmpty && !store.state.liveAlarmScheduled {
            return ["Schedule the live alarm"]
        }
        return labels
    }

    var body: some View {
        Section {
            VStack(alignment: .leading, spacing: 12) {
                Label(
                    "Alarm not ready: \(missingLabels.count) \(missingLabels.count == 1 ? "step" : "steps") remaining",
                    systemImage: "exclamationmark.triangle.fill"
                )
                .font(.headline)
                .foregroundStyle(Color.brandOrange)
                ForEach(missingLabels, id: \.self) { label in
                    Label(label, systemImage: "circle")
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Button(actionTitle) {
                    if store.state.hasCompletedOnboarding && store.activationReadiness.prerequisitesReady {
                        store.retryLiveAlarmScheduling()
                    } else if store.state.hasCompletedOnboarding {
                        store.restartOnboarding()
                    } else {
                        store.resumeOnboarding()
                    }
                }
                .brandProminentButtonStyle()
            }
            .padding(.vertical, 6)
        }
    }

    private var actionTitle: String {
        if !store.state.hasCompletedOnboarding { return "Finish setup" }
        return store.activationReadiness.prerequisitesReady ? "Schedule again" : "Repair setup"
    }
}

private struct PhoneVerificationCard: View {
    @EnvironmentObject private var store: AppStore

    private var testIsPending: Bool {
        guard let scheduledAt = store.state.onboardingTestScheduledAt else { return false }
        return scheduledAt.timeIntervalSinceNow > -60 && !store.onboardingTestPassed
    }

    var body: some View {
        Section("Verify this phone") {
            if store.onboardingTestPassed {
                Label("Notification tap and target dismissal verified", systemImage: "checkmark.seal.fill")
                    .foregroundStyle(Color.brandGreen)
            } else if testIsPending {
                Text("A test notification is scheduled. Tap it, then dismiss the challenge with your target.")
                    .font(.subheadline)
                Button("Cancel verification test", role: .destructive) {
                    store.cancelVerificationTest()
                }
            } else {
                Text("Check notification delivery before bedtime. The test does not change your live alarm schedule.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button("Verify this phone now") {
                    store.scheduleVerificationTest()
                }
            }
        }
    }
}

struct SignatureDialView: View {
    var minutes: Int
    var isEnabled: Bool

    private var progress: Double { Double(minutes) / Double(24 * 60) }
    private var hourText: String {
        var components = DateComponents()
        components.hour = minutes / 60
        components.minute = minutes % 60
        let date = Calendar.current.date(from: components) ?? Date()
        return DateFormatter.localizedString(from: date, dateStyle: .none, timeStyle: .short)
    }

    var body: some View {
        ZStack {
            Circle()
                .fill(.clear)
                .brandGlass(in: Circle())
            Circle()
                .strokeBorder(.secondary.opacity(0.14), lineWidth: 1)
            ForEach(0..<60, id: \.self) { tick in
                Rectangle()
                    .fill(tick % 5 == 0 ? Color.primary.opacity(0.55) : Color.secondary.opacity(0.35))
                    .frame(width: tick % 5 == 0 ? 2 : 1, height: tick % 5 == 0 ? 16 : 8)
                    .offset(y: -132)
                    .rotationEffect(.degrees(Double(tick) * 6))
            }
            Circle()
                .trim(from: 0, to: progress)
                .stroke(.tint, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(2)
            Circle()
                .fill(.tint)
                .frame(width: 26, height: 26)
                .offset(y: -135)
                .rotationEffect(.degrees(progress * 360))
                .shadow(color: Color.accentColor.opacity(0.35), radius: 8)
            VStack(spacing: 8) {
                Image(systemName: isEnabled ? "bell.fill" : "bell.slash")
                    .font(.title3)
                    .foregroundStyle(isEnabled ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
                Text(hourText)
                    .font(.system(size: 48, weight: .light, design: .rounded))
                    .monospacedDigit()
                    .minimumScaleFactor(0.7)
            }
        }
        .frame(width: 300, height: 300)
    }
}

struct WeekdayPickerView: View {
    let selected: [Bool]
    let onToggle: (Int) -> Void
    private let labels = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"]

    var body: some View {
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
            ForEach(labels.indices, id: \.self) { index in
                Button {
                    onToggle(index)
                } label: {
                    Text(labels[index])
                        .font(.caption.weight(.semibold))
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                        .frame(maxWidth: .infinity, minHeight: 44)
                }
                .buttonStyle(.bordered)
                .tint(selected.indices.contains(index) && selected[index] ? .accentColor : .secondary)
                .accessibilityLabel(labels[index])
                .accessibilityValue(selected.indices.contains(index) && selected[index] ? "Selected" : "Not selected")
            }
        }
    }
}

struct AddQrSpotView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    @State private var label = ""
    @State private var didTrackStart = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField("Spot name", text: $label)
                        .textInputAutocapitalization(.words)
                } footer: {
                    Text("Use a place you must physically reach in the morning, like Bathroom sink or Kitchen kettle.")
                }
            }
            .navigationTitle("Add QR Spot")
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        store.track(ProductEvent(.dismissalEnrollmentCancelled, targetType: .qr, source: .app, outcome: .cancelled, cancellationReason: .unspecified))
                        dismiss()
                    }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Add") {
                        store.addQrSpot(label: label)
                        dismiss()
                    }
                    .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                }
            }
        }
        .onAppear {
            guard !didTrackStart else { return }
            didTrackStart = true
            store.track(ProductEvent(.dismissalEnrollmentStarted, targetType: .qr, source: .app))
        }
    }
}

struct QrSpotDetailView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    let spot: QrLocation

    private var currentSpot: QrLocation? {
        store.state.qrLocations.first { $0.id == spot.id }
    }

    var body: some View {
        NavigationStack {
            Form {
                if let currentSpot {
                    Section {
                        QrCodeView(payload: currentSpot.payload)
                            .frame(maxWidth: .infinity, minHeight: 260)
                            .padding(.vertical, 12)
                            .accessibilityLabel("QR code for \(currentSpot.label)")
                        Text(currentSpot.payload)
                            .font(.footnote.monospaced())
                            .textSelection(.enabled)
                    }
                    Section {
                        Toggle(isOn: Binding(
                            get: { store.state.alarm.allowedLocationIds.contains(currentSpot.id) },
                            set: { store.setSpotAllowed(currentSpot, allowed: $0) }
                        )) {
                            Label("Allowed for alarm", systemImage: "checkmark.shield")
                        }
                    }
                    Section {
                        ShareLink(item: currentSpot.payload) {
                            Label("Share Payload", systemImage: "square.and.arrow.up")
                        }
                        Button(role: .destructive) {
                            store.deleteQrSpot(currentSpot)
                            dismiss()
                        } label: {
                            Label("Delete QR Spot", systemImage: "trash")
                        }
                    }
                }
            }
            .navigationTitle(spot.label)
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }
}

struct SettingsHealthView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        List {
            Section {
                VStack(spacing: 12) {
                    BrandMark(size: 72)
                    VStack(spacing: 2) {
                        Text("Super Alarm")
                            .font(.title3.weight(.semibold))
                        Text("Version \(appVersion)")
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
                .accessibilityElement(children: .combine)
            }

            Section("Preferences") {
                Toggle("Share anonymous analytics", isOn: Binding(
                    get: { store.analyticsEnabled },
                    set: store.setAnalyticsEnabled
                ))
                Text("Shares product use, device details, and coarse IP-derived location. Alarm labels and scan data stay private.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                HealthRow(
                    systemImage: "bell.fill",
                    title: "Notifications",
                    value: store.health.notificationLabel,
                    tint: .accentColor
                ) {
                    store.requestNotifications()
                }
                HealthRow(
                    systemImage: "camera.fill",
                    title: "Camera",
                    value: "Requested when scanning",
                    tint: .gray
                ) { }
            }

            Section("iPhone alarm delivery") {
                LabeledContent("Delivery mode", value: "Standard notification")
                Text("You must tap the notification to start continuous alarm sound and the dismissal challenge. Silent mode or Focus can silence a regular notification.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Text("Critical Alerts require Apple approval and still do not provide Android-style continuous alarm behavior before you open the app.")
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }

            Section("Tools") {
                if store.state.hasCompletedOnboarding {
                    Button {
                        store.scheduleVerificationTest()
                    } label: {
                        HStack(spacing: 12) {
                            SettingsIconTile(systemImage: "flask.fill", tint: .brandGreen)
                            Text("Verify This Phone")
                                .foregroundStyle(.primary)
                        }
                    }
                }
                Button {
                    store.activeSheet = .diagnostics
                } label: {
                    HStack(spacing: 12) {
                        SettingsIconTile(systemImage: "info", tint: .brandNavy)
                        Text("Diagnostics")
                            .foregroundStyle(.primary)
                    }
                }
                Button {
                    store.restartOnboarding()
                } label: {
                    HStack(spacing: 12) {
                        SettingsIconTile(systemImage: "arrow.counterclockwise", tint: .brandOrange)
                        Text("Run Setup Again")
                            .foregroundStyle(.primary)
                    }
                }
            }

            Section("Recent Attempts") {
                if store.state.attempts.isEmpty {
                    ContentUnavailableRow(systemImage: "clock", title: "No attempts yet", message: "Test alarms and live alarms appear here.")
                } else {
                    ForEach(store.state.attempts.prefix(8)) { attempt in
                        VStack(alignment: .leading, spacing: 4) {
                            Text(attempt.kind == .test ? "Test alarm" : "Live alarm")
                            Text("\(attempt.result.rawValue.capitalized) at \(attempt.triggeredAt.formatted(date: .abbreviated, time: .shortened))")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }
            }

            Section("About") {
                LabeledContent("Version", value: appVersion)
                LabeledContent("Bundle ID", value: Bundle.main.bundleIdentifier ?? "com.superalarm.app")
            }
        }
        .navigationTitle("Settings")
        .onAppear { store.track(ProductEvent(.surfaceViewed, surface: .settings, source: .app)) }
        .refreshable { await store.refreshHealth() }
        .task { await store.refreshHealth() }
    }

    private var appVersion: String {
        Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0"
    }
}

struct SettingsIconTile: View {
    let systemImage: String
    let tint: Color

    var body: some View {
        Image(systemName: systemImage)
            .font(.footnote.weight(.semibold))
            .foregroundStyle(.white)
            .frame(width: 29, height: 29)
            .background(tint, in: RoundedRectangle(cornerRadius: 7, style: .continuous))
            .accessibilityHidden(true)
    }
}

struct HealthRow: View {
    let systemImage: String
    let title: String
    let value: String
    let tint: Color
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                SettingsIconTile(systemImage: systemImage, tint: tint)
                Text(title)
                    .foregroundStyle(.primary)
                Spacer()
                Text(value)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .multilineTextAlignment(.trailing)
            }
        }
        .accessibilityElement(children: .combine)
    }
}

struct ContentUnavailableRow: View {
    let systemImage: String
    let title: String
    let message: String

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(.secondary)
                .frame(width: 30)
            VStack(alignment: .leading, spacing: 4) {
                Text(title)
                    .foregroundStyle(.primary)
                Text(message)
                    .font(.caption)
                    .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 6)
    }
}

private enum RingingScanner: String, Identifiable {
    case object
    case qr

    var id: String { rawValue }
}

struct RingingView: View {
    @EnvironmentObject private var store: AppStore
    @Environment(\.scenePhase) private var scenePhase
    @State private var scanner: RingingScanner?
    @State private var scannerSessionID = UUID()
    @State private var scanMessage: String?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.brandNight, Color.brandNavy], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            NightSkyView()
                .ignoresSafeArea()
            VStack(spacing: 28) {
                Spacer()
                Text("ALARM RINGING")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(Color.brandSky)
                Text(timeString)
                    .font(.system(size: 54, weight: .light, design: .rounded))
                    .monospacedDigit()
                    .foregroundStyle(.white)
                    .minimumScaleFactor(0.7)
                Text(dismissalPrompt)
                    .font(.headline)
                    .foregroundStyle(.white.opacity(0.72))

                VStack(spacing: 12) {
                    if !store.allowedObjectTargets.isEmpty {
                        Button {
                            store.beginDismissal(targetType: .object)
                            scanner = .object
                        } label: {
                            Label("Scan Object", systemImage: "camera.viewfinder")
                                .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .brandProminentButtonStyle()
                        .controlSize(.large)
                        .tint(.brandSky)
                        .accessibilityHint("Opens the object scanner to dismiss the alarm")
                    }
                    if !store.allowedLocations.isEmpty {
                        Button {
                            store.beginDismissal(targetType: .qr)
                            scanner = .qr
                        } label: {
                            Label("Scan QR", systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .brandProminentButtonStyle()
                        .controlSize(.large)
                        .tint(.brandSky)
                    }
                    if store.state.runtime?.kind == .test {
                        Button("Stop test") { store.stopVerificationRinging() }
                            .foregroundStyle(.white)
                            .padding(.vertical, 8)
                    }
                }
                .padding(.top, 8)

                if let scanMessage {
                    Text(scanMessage)
                        .font(.callout)
                        .foregroundStyle(.white.opacity(0.8))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal)
                }

                Spacer()
                allowedTargetsList
            }
            .padding(24)
        }
        .sheet(item: $scanner) { activeScanner in
            NavigationStack {
                Group {
                    switch activeScanner {
                    case .object:
                        ObjectDismissalView(targets: store.allowedObjectTargets)
                    case .qr:
                        QrScannerView { payload in
                            switch store.handleScan(payload) {
                            case .valid:
                                scanner = nil
                            case .invalid(let message):
                                scanMessage = message
                            }
                        }
                    }
                }
                .id(scannerSessionID)
                .navigationTitle(activeScanner == .qr ? "Scan QR" : "Scan object")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Back") { scanner = nil }
                            .accessibilityLabel("Back to alarm")
                    }
                    ToolbarItem(placement: .primaryAction) {
                        Button("Settings") { store.openCameraSettings() }
                            .accessibilityLabel("Open camera settings")
                    }
                }
            }
            .preferredColorScheme(.dark)
        }
        .onChange(of: scenePhase) { phase in
            if phase == .active && scanner != nil { scannerSessionID = UUID() }
        }
        .overlay {
            if store.dismissedAttempt != nil {
                DismissedOverlay {
                    store.closeRinging()
                }
            }
        }
    }

    private var timeString: String {
        DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .short)
    }

    private var dismissalPrompt: String {
        if !store.allowedObjectTargets.isEmpty && !store.allowedLocations.isEmpty {
            return "Scan an allowed object or QR spot"
        }
        if !store.allowedObjectTargets.isEmpty {
            return "Find and scan your allowed object"
        }
        return "Scan an allowed QR spot"
    }

    private var allowedTargetsList: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Allowed targets")
                .font(.subheadline)
                .foregroundStyle(.white.opacity(0.76))
                .padding(.horizontal, 16)
                .padding(.bottom, 8)
            VStack(spacing: 0) {
                ForEach(store.allowedObjectTargets) { target in
                    targetRow(systemImage: "camera.viewfinder", label: target.label)
                    Divider().overlay(.white.opacity(0.14))
                }
                ForEach(store.allowedLocations) { spot in
                    targetRow(systemImage: "mappin.and.ellipse", label: spot.label)
                    if spot.id != store.allowedLocations.last?.id {
                        Divider().overlay(.white.opacity(0.14))
                    }
                }
            }
            .brandGlass(in: RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .frame(maxWidth: .infinity)
    }

    private func targetRow(systemImage: String, label: String) -> some View {
        HStack(spacing: 12) {
            Image(systemName: systemImage)
                .foregroundStyle(.white.opacity(0.88))
            Text(label)
                .foregroundStyle(.white)
            Spacer()
        }
        .frame(minHeight: 50)
        .padding(.horizontal, 14)
    }
}

/// Fixed star field echoing the night sky in the Super Alarm logo.
struct NightSkyView: View {
    private static let stars: [(x: CGFloat, y: CGFloat, radius: CGFloat, opacity: CGFloat)] = [
        (0.12, 0.10, 1.4, 0.55), (0.30, 0.06, 1.0, 0.35), (0.52, 0.13, 1.8, 0.6),
        (0.70, 0.05, 1.2, 0.4), (0.88, 0.11, 1.5, 0.5), (0.08, 0.24, 1.0, 0.3),
        (0.42, 0.21, 1.2, 0.4), (0.63, 0.27, 1.0, 0.3), (0.81, 0.22, 1.3, 0.45),
        (0.20, 0.33, 1.5, 0.5), (0.94, 0.31, 1.0, 0.35), (0.35, 0.40, 1.1, 0.3)
    ]

    var body: some View {
        Canvas { context, size in
            for star in Self.stars {
                let rect = CGRect(
                    x: star.x * size.width - star.radius,
                    y: star.y * size.height - star.radius,
                    width: star.radius * 2,
                    height: star.radius * 2
                )
                context.fill(Path(ellipseIn: rect), with: .color(.white.opacity(star.opacity)))
            }
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}

struct DismissedOverlay: View {
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 68))
                .foregroundStyle(Color.brandGreen)
            Text("Dismissed")
                .font(.largeTitle.bold())
            Button("Done", action: onDone)
                .brandProminentButtonStyle()
                .controlSize(.large)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(.regularMaterial)
    }
}

struct QrCodeView: View {
    let payload: String
    private let context = CIContext()
    private let filter = CIFilter.qrCodeGenerator()

    var body: some View {
        if let image = makeImage() {
            Image(uiImage: image)
                .interpolation(.none)
                .resizable()
                .scaledToFit()
                .padding(24)
        } else {
            ContentUnavailableRow(systemImage: "qrcode", title: "QR unavailable", message: "The payload could not be rendered.")
        }
    }

    private func makeImage() -> UIImage? {
        filter.message = Data(payload.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 12, y: 12))
        guard let cgImage = context.createCGImage(scaled, from: scaled.extent) else { return nil }
        return UIImage(cgImage: cgImage)
    }
}

struct QrScannerView: UIViewControllerRepresentable {
    let onScan: (String) -> Void

    func makeUIViewController(context: Context) -> AvFoundationScannerViewController {
        AvFoundationScannerViewController(onScan: onScan)
    }

    func updateUIViewController(_ uiViewController: AvFoundationScannerViewController, context: Context) { }

    static func dismantleUIViewController(
        _ uiViewController: AvFoundationScannerViewController,
        coordinator: Void
    ) {
        uiViewController.stop()
    }
}

final class AvFoundationScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.superalarm.qr-camera.session")
    private let onScan: (String) -> Void
    private lazy var previewLayer = AVCaptureVideoPreviewLayer(session: session)
    private var didScan = false
    private var isConfigured = false
    private var shouldRun = false

    init(onScan: @escaping (String) -> Void) {
        self.onScan = onScan
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        previewLayer.videoGravity = .resizeAspectFill
        view.layer.addSublayer(previewLayer)
        requestCamera()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer.frame = view.bounds
    }

    override func viewDidAppear(_ animated: Bool) {
        super.viewDidAppear(animated)
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.shouldRun = true
            self.startIfReady()
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        stop()
    }

    func stop() {
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.shouldRun = false
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    private func requestCamera() {
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            configure()
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { [weak self] granted in
                if granted {
                    self?.configure()
                } else {
                    self?.showFallbackLabel("Camera access is required to scan a QR code.")
                }
            }
        case .denied, .restricted:
            showFallbackLabel("Camera access is required. Allow it in iOS Settings.")
        @unknown default:
            showFallbackLabel("Camera access is unavailable.")
        }
    }

    private func configure() {
        sessionQueue.async { [weak self] in
            guard let self, !self.isConfigured else { return }
            self.session.beginConfiguration()
            defer {
                self.session.commitConfiguration()
                self.startIfReady()
            }

            guard
                let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device),
                self.session.canAddInput(input)
            else {
                self.showFallbackLabel("Camera is unavailable.")
                return
            }
            self.session.addInput(input)

            let output = AVCaptureMetadataOutput()
            guard self.session.canAddOutput(output) else {
                self.showFallbackLabel("QR scanning is unavailable.")
                return
            }
            self.session.addOutput(output)
            guard output.availableMetadataObjectTypes.contains(.qr) else {
                self.showFallbackLabel("QR scanning is unavailable.")
                return
            }
            output.setMetadataObjectsDelegate(self, queue: .main)
            output.metadataObjectTypes = [.qr]
            self.isConfigured = true
        }
    }

    private func startIfReady() {
        guard isConfigured, shouldRun, !session.isRunning else { return }
        session.startRunning()
    }

    private func showFallbackLabel(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self, self.view.subviews.compactMap({ $0 as? UILabel }).isEmpty else { return }
            let label = UILabel()
            label.text = text
            label.textColor = .white
            label.textAlignment = .center
            label.numberOfLines = 0
            label.translatesAutoresizingMaskIntoConstraints = false
            self.view.addSubview(label)
            NSLayoutConstraint.activate([
                label.leadingAnchor.constraint(equalTo: self.view.leadingAnchor, constant: 24),
                label.trailingAnchor.constraint(equalTo: self.view.trailingAnchor, constant: -24),
                label.centerYAnchor.constraint(equalTo: self.view.centerYAnchor)
            ])
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard !didScan, let object = metadataObjects.first as? AVMetadataMachineReadableCodeObject, let payload = object.stringValue else { return }
        didScan = true
        onScan(payload)
        DispatchQueue.main.asyncAfter(deadline: .now() + 1) { self.didScan = false }
    }
}

struct DiagnosticsExportView: View {
    @Environment(\.dismiss) private var dismiss
    let data: Data

    var body: some View {
        NavigationStack {
            ActivityView(activityItems: [exportURL])
                .navigationTitle("Diagnostics")
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
        }
    }

    private var exportURL: URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("super-alarm-diagnostics.json")
        try? data.write(to: url, options: [.atomic])
        return url
    }
}

struct ActivityView: UIViewControllerRepresentable {
    let activityItems: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: activityItems, applicationActivities: nil)
    }

    func updateUIViewController(_ uiViewController: UIActivityViewController, context: Context) { }
}

struct DiagnosticsExport: Codable {
    var exportedAt: Date
    var bundleIdentifier: String
    var appVersion: String
    var notificationHealth: ExportedNotificationHealth
    var state: SuperAlarmState
}

struct ExportedNotificationHealth: Codable {
    var authorizationStatus: String
    var criticalSetting: String
    var alertSetting: String
    var soundSetting: String
    var hasCriticalEntitlement: Bool

    init(_ health: NotificationHealth) {
        authorizationStatus = String(describing: health.authorizationStatus)
        criticalSetting = String(describing: health.criticalSetting)
        alertSetting = String(describing: health.alertSetting)
        soundSetting = String(describing: health.soundSetting)
        hasCriticalEntitlement = health.hasCriticalEntitlement
    }
}

#if DEBUG
@MainActor
enum SuperAlarmPreviewData {
    static func store(hasCompletedOnboarding: Bool) -> AppStore {
        let store = AppStore()
        let qrID = UUID()
        let objectID = UUID()

        var state = SuperAlarmState()
        state.hasCompletedOnboarding = hasCompletedOnboarding
        state.alarm.isEnabled = true
        state.alarm.timeMinutes = 7 * 60 + 30
        state.alarm.allowedLocationIds = [qrID]
        state.alarm.allowedObjectTargetIds = [objectID]
        state.qrLocations = [QrLocation(
            id: qrID,
            label: "Bathroom sink",
            payload: "superalarm://alarm-qr/preview",
            createdAt: Date()
        )]
        state.objectTargets = [ObjectTarget(
            id: objectID,
            label: "Blue mug",
            fingerprints: ["preview"],
            matchDistanceThreshold: 0.8,
            visionRevision: ObjectFeaturePrintCodec.revision,
            createdAt: Date()
        )]
        store.state = state
        store.health.authorizationStatus = .authorized
        store.health.alertSetting = .enabled
        store.health.soundSetting = .enabled
        return store
    }
}

#Preview("Alarm editor") {
    NavigationStack {
        AlarmEditorView()
    }
    .environmentObject(SuperAlarmPreviewData.store(hasCompletedOnboarding: true))
}
#endif

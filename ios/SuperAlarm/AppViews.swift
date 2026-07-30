import AVFoundation
import CoreImage.CIFilterBuiltins
import SwiftUI
import UIKit
import VisionKit

struct RootView: View {
    @EnvironmentObject private var store: AppStore

    var body: some View {
        TabView {
            NavigationStack { AlarmEditorView() }
                .tabItem { Label("Alarm", systemImage: "alarm") }
            NavigationStack { SettingsHealthView() }
                .tabItem { Label("Settings", systemImage: "gearshape") }
        }
        .tint(.orange)
        .sheet(item: $store.activeSheet) { route in
            switch route {
            case .addObject:
                AddObjectView()
            case .addSpot:
                AddQrSpotView()
            case .diagnostics:
                DiagnosticsExportView(data: store.diagnosticsExportData())
            case .spot(let spot):
                QrSpotDetailView(spot: spot)
            }
        }
        .fullScreenCover(isPresented: $store.isRingingPresented) {
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
            get: { !store.state.hasCompletedOnboarding },
            set: { _ in }
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
                    Label("Enabled", systemImage: "bell")
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
                                    .foregroundStyle(.orange)
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
                .fill(.thinMaterial)
                .shadow(color: .black.opacity(0.10), radius: 18, y: 8)
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
                .stroke(.orange, style: StrokeStyle(lineWidth: 4, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .padding(2)
            Circle()
                .fill(.orange)
                .frame(width: 26, height: 26)
                .offset(y: -135)
                .rotationEffect(.degrees(progress * 360))
                .shadow(color: .orange.opacity(0.35), radius: 8)
            VStack(spacing: 8) {
                Image(systemName: isEnabled ? "bell.fill" : "bell.slash")
                    .font(.title3)
                    .foregroundStyle(isEnabled ? .orange : .secondary)
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
        HStack(spacing: 8) {
            ForEach(labels.indices, id: \.self) { index in
                Button {
                    onToggle(index)
                } label: {
                    Text(labels[index])
                        .font(.caption.weight(.semibold))
                        .frame(maxWidth: .infinity, minHeight: 32)
                }
                .buttonStyle(.bordered)
                .tint(selected.indices.contains(index) && selected[index] ? .orange : .secondary)
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
                    Button("Cancel") { dismiss() }
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
            Section("Preferences") {
                HealthRow(
                    systemImage: "bell.fill",
                    title: "Notifications",
                    value: store.health.notificationLabel,
                    tint: .orange
                ) {
                    store.requestNotifications()
                }
                HealthRow(
                    systemImage: "checkmark.shield.fill",
                    title: "Critical Alerts",
                    value: store.health.criticalLabel,
                    tint: .orange
                ) {
                    store.requestNotifications()
                }
                HealthRow(
                    systemImage: "camera.fill",
                    title: "Camera",
                    value: "Requested when scanning",
                    tint: .orange
                ) { }
            }

            Section("Tools") {
                Button {
                    store.runTestAlarm()
                } label: {
                    Label("Test Alarm", systemImage: "flask")
                }
                Button {
                    store.activeSheet = .diagnostics
                } label: {
                    Label("Diagnostics", systemImage: "info.circle")
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
                LabeledContent("Version", value: Bundle.main.infoDictionary?["CFBundleShortVersionString"] as? String ?? "1.0.0")
                LabeledContent("Bundle ID", value: Bundle.main.bundleIdentifier ?? "com.superalarm.app")
            }
        }
        .navigationTitle("Settings")
        .refreshable { await store.refreshHealth() }
        .task { await store.refreshHealth() }
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
                Image(systemName: systemImage)
                    .foregroundStyle(tint)
                    .frame(width: 28)
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
    @State private var scanner: RingingScanner?
    @State private var scanMessage: String?

    var body: some View {
        ZStack {
            LinearGradient(colors: [Color.black, Color(white: 0.08)], startPoint: .top, endPoint: .bottom)
                .ignoresSafeArea()
            VStack(spacing: 28) {
                Spacer()
                Text("ALARM RINGING")
                    .font(.caption.weight(.bold))
                    .foregroundStyle(.orange)
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
                            scanner = .object
                        } label: {
                            Label("Scan Object", systemImage: "camera.viewfinder")
                                .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                        .accessibilityHint("Opens the object scanner to dismiss the alarm")
                    }
                    if !store.allowedLocations.isEmpty {
                        Button {
                            scanner = .qr
                        } label: {
                            Label("Scan QR", systemImage: "qrcode.viewfinder")
                                .frame(maxWidth: .infinity, minHeight: 54)
                        }
                        .buttonStyle(.bordered)
                        .controlSize(.large)
                        .tint(.orange)
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
                .ignoresSafeArea()
            }
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
            .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
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

struct DismissedOverlay: View {
    let onDone: () -> Void

    var body: some View {
        VStack(spacing: 18) {
            Image(systemName: "checkmark.circle.fill")
                .font(.system(size: 68))
                .foregroundStyle(.green)
            Text("Dismissed")
                .font(.largeTitle.bold())
            Button("Done", action: onDone)
                .buttonStyle(.borderedProminent)
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

    func makeUIViewController(context: Context) -> UIViewController {
        if #available(iOS 16.0, *), DataScannerViewController.isSupported, DataScannerViewController.isAvailable {
            let scanner = DataScannerViewController(
                recognizedDataTypes: [.barcode(symbologies: [.qr])],
                qualityLevel: .balanced,
                recognizesMultipleItems: false,
                isHighFrameRateTrackingEnabled: false,
                isHighlightingEnabled: true
            )
            scanner.delegate = context.coordinator
            try? scanner.startScanning()
            return scanner
        }
        return AvFoundationScannerViewController(onScan: onScan)
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) { }

    func makeCoordinator() -> Coordinator {
        Coordinator(onScan: onScan)
    }

    final class Coordinator: NSObject, DataScannerViewControllerDelegate {
        let onScan: (String) -> Void
        init(onScan: @escaping (String) -> Void) { self.onScan = onScan }

        @available(iOS 16.0, *)
        func dataScanner(_ dataScanner: DataScannerViewController, didTapOn item: RecognizedItem) {
            if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                onScan(payload)
            }
        }

        @available(iOS 16.0, *)
        func dataScanner(_ dataScanner: DataScannerViewController, didAdd addedItems: [RecognizedItem], allItems: [RecognizedItem]) {
            for item in addedItems {
                if case .barcode(let barcode) = item, let payload = barcode.payloadStringValue {
                    onScan(payload)
                    break
                }
            }
        }
    }
}

final class AvFoundationScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    private let session = AVCaptureSession()
    private let onScan: (String) -> Void
    private var didScan = false

    init(onScan: @escaping (String) -> Void) {
        self.onScan = onScan
        super.init(nibName: nil, bundle: nil)
    }

    required init?(coder: NSCoder) { nil }

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        configure()
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        session.stopRunning()
    }

    private func configure() {
        guard let device = AVCaptureDevice.default(for: .video), let input = try? AVCaptureDeviceInput(device: device), session.canAddInput(input) else {
            showFallbackLabel("Camera is unavailable.")
            return
        }
        session.addInput(input)
        let output = AVCaptureMetadataOutput()
        guard session.canAddOutput(output) else {
            showFallbackLabel("QR scanning is unavailable.")
            return
        }
        session.addOutput(output)
        output.setMetadataObjectsDelegate(self, queue: .main)
        output.metadataObjectTypes = [.qr]

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        preview.frame = view.bounds
        view.layer.addSublayer(preview)
        DispatchQueue.global(qos: .userInitiated).async { self.session.startRunning() }
    }

    private func showFallbackLabel(_ text: String) {
        let label = UILabel()
        label.text = text
        label.textColor = .white
        label.textAlignment = .center
        label.numberOfLines = 0
        label.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(label)
        NSLayoutConstraint.activate([
            label.leadingAnchor.constraint(equalTo: view.leadingAnchor, constant: 24),
            label.trailingAnchor.constraint(equalTo: view.trailingAnchor, constant: -24),
            label.centerYAnchor.constraint(equalTo: view.centerYAnchor)
        ])
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

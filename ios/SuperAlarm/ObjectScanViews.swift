import Foundation
import SwiftUI
import UIKit

struct AddObjectView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    @State private var label = "Fridge"
    @State private var isScanning = false
    @State private var showCancellationReasons = false
    @State private var didResolveEnrollment = false
    let onUseQr: () -> Void

    init(onUseQr: @escaping () -> Void = { }) {
        self.onUseQr = onUseQr
    }

    var body: some View {
        ZStack {
            if isScanning {
                ObjectEnrollmentView(
                    label: label,
                    onCompleted: complete,
                    onCancelRequested: { showCancellationReasons = true },
                    onCameraFallback: useQrAfterCameraFailure
                )
            } else {
                NavigationStack {
                    Form {
                        Section("What scanning looks like") {
                            ObjectEnrollmentDemo()
                            Text("Center one fixed object, then move slowly around it while Super Alarm saves several views on this iPhone.")
                                .font(.subheadline)
                                .foregroundStyle(.secondary)
                        }
                        Section {
                            TextField("Object name", text: $label)
                                .textInputAutocapitalization(.words)
                        } footer: {
                            Text("Choose a distinctive object away from bed, such as your fridge or coffee machine.")
                        }
                        Section {
                            Button("Use a QR spot instead") { useQr(reason: .qrFallback) }
                        }
                    }
                    .navigationTitle("New Wake Object")
                    .toolbar {
                        ToolbarItem(placement: .cancellationAction) {
                            Button("Cancel") { showCancellationReasons = true }
                        }
                        ToolbarItem(placement: .confirmationAction) {
                            Button("Open Camera") { startScan() }
                                .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                        }
                    }
                }
            }
        }
        .confirmationDialog("Why are you stopping?", isPresented: $showCancellationReasons, titleVisibility: .visible) {
            Button("Camera or scanning problem") { cancel(reason: .cameraProblem) }
            Button("Privacy concern") { cancel(reason: .privacyConcern) }
            Button("I’ll finish later") { cancel(reason: .finishLater) }
            Button("Use a QR spot instead") { useQr(reason: .qrFallback) }
            Button("Close without saying", role: .cancel) { cancel(reason: .unspecified) }
        } message: {
            Text("Answering is optional and helps improve setup.")
        }
        .onDisappear {
            guard !didResolveEnrollment else { return }
            trackCancellation(.unspecified)
            didResolveEnrollment = true
        }
    }

    private func startScan() {
        store.track(ProductEvent(
            .dismissalEnrollmentStarted,
            surface: .objectEnrollment,
            targetType: .object,
            source: store.state.hasCompletedOnboarding ? .app : .onboarding
        ))
        isScanning = true
    }

    private func complete() {
        didResolveEnrollment = true
        dismiss()
    }

    private func cancel(reason: EnrollmentCancellationReason) {
        trackCancellation(reason)
        didResolveEnrollment = true
        dismiss()
    }

    private func useQrAfterCameraFailure() {
        useQr(reason: .cameraProblem)
    }

    private func useQr(reason: EnrollmentCancellationReason) {
        trackCancellation(reason)
        didResolveEnrollment = true
        dismiss()
        DispatchQueue.main.async { onUseQr() }
    }

    private func trackCancellation(_ reason: EnrollmentCancellationReason) {
        store.track(ProductEvent(
            .dismissalEnrollmentCancelled,
            surface: .objectEnrollment,
            targetType: .object,
            source: store.state.hasCompletedOnboarding ? .app : .onboarding,
            outcome: .cancelled,
            cancellationReason: reason
        ))
    }
}

struct ObjectEnrollmentView: View {
    @EnvironmentObject private var store: AppStore
    let label: String
    let onCompleted: () -> Void
    let onCancelRequested: () -> Void
    let onCameraFallback: () -> Void

    @State private var fingerprints: [String] = []
    @State private var lastCaptureAt: TimeInterval = 0
    @State private var issue: ObjectFrameIssue?
    @State private var cameraFailure: ObjectCameraFailure?
    @State private var isComplete = false
    @State private var didTrackFailure = false
    @State private var cameraSessionID = UUID()

    private let instructions = [
        "Center the object inside the ring",
        "Move slowly to the left",
        "Sweep slowly to the right",
        "Raise the phone a little",
        "Lower the phone a little",
        "Move closer to a distinctive detail",
        "Ease back to show the whole object",
        "Hold steady to finish"
    ]

    var body: some View {
        ZStack {
            ObjectCameraView(mode: .enrollment, onFrame: handleFrame, onError: handleError)
                .id(cameraSessionID)
                .ignoresSafeArea()
            ObjectScanGuide(progress: progress, active: issue == nil && cameraFailure == nil)
            ObjectScanTopBar(title: "Scan \(label)", onClose: onCancelRequested)
            ObjectScanPanel(
                title: fingerprints.isEmpty ? "Look at \(label)" : "Keep moving slowly",
                message: cameraFailure?.message ?? issue?.rawValue ?? instructions[min(fingerprints.count, instructions.count - 1)],
                progress: progress
            )
            if let cameraFailure {
                VStack(spacing: 12) {
                    Spacer()
                    if cameraFailure == .permissionDenied {
                        Button("Open iOS Settings", action: store.openCameraSettings)
                            .brandProminentButtonStyle()
                    }
                    Button("Try Camera Again", action: retryCamera)
                        .brandGlassButtonStyle()
                        .tint(.white)
                    Button("Use a QR spot instead", action: onCameraFallback)
                        .brandGlassButtonStyle()
                        .tint(.white)
                }
                .padding(24)
                .padding(.bottom, 130)
            }
        }
        .background(Color.black)
    }

    private var progress: Double {
        Double(fingerprints.count) / Double(instructions.count)
    }

    private func handleFrame(_ sample: ObjectFrameSample) {
        guard !isComplete else { return }
        issue = sample.issue
        guard sample.issue == nil, let fingerprint = sample.fingerprint else { return }
        let elapsed = sample.capturedAt - lastCaptureAt
        guard elapsed >= 0.325 else { return }
        let movedEnough = fingerprints.last
            .flatMap { ObjectFeaturePrintCodec.distance($0, fingerprint) }
            .map { $0 >= 1 }
            ?? true
        guard movedEnough || elapsed >= 0.9 else { return }

        fingerprints.append(fingerprint)
        lastCaptureAt = sample.capturedAt
        UISelectionFeedbackGenerator().selectionChanged()
        guard fingerprints.count == instructions.count else { return }
        isComplete = true
        let selected = ObjectFeaturePrintCodec.selectDiverse(fingerprints, count: 5)
        store.addObjectTarget(label: label, fingerprints: selected)
        onCompleted()
    }

    private func handleError(_ failure: ObjectCameraFailure) {
        cameraFailure = failure
        guard !didTrackFailure else { return }
        didTrackFailure = true
        store.track(ProductEvent(
            .dismissalEnrollmentFailed,
            surface: .objectEnrollment,
            targetType: .object,
            source: store.state.hasCompletedOnboarding ? .app : .onboarding,
            outcome: .failed,
            failureCode: failure.rawValue
        ))
    }

    private func retryCamera() {
        cameraFailure = nil
        didTrackFailure = false
        cameraSessionID = UUID()
    }
}

private struct ObjectEnrollmentDemo: View {
    var body: some View {
        HStack(spacing: 12) {
            demoStep("1", image: "camera.viewfinder", text: "Center")
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
            demoStep("2", image: "arrow.left.and.right", text: "Move")
            Image(systemName: "chevron.right").foregroundStyle(.secondary)
            demoStep("3", image: "checkmark.circle", text: "Save")
        }
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Center the object, move around it slowly, then save")
    }

    private func demoStep(_ number: String, image: String, text: String) -> some View {
        VStack(spacing: 6) {
            Image(systemName: image)
                .font(.title2)
            Text("\(number). \(text)")
                .font(.caption.weight(.semibold))
        }
        .frame(maxWidth: .infinity)
    }
}

struct ObjectDismissalView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    let targets: [ObjectTarget]

    @State private var issue: ObjectFrameIssue?
    @State private var cameraFailure: ObjectCameraFailure?
    @State private var candidate: ObjectMatchCandidate?
    @State private var matchingFrames = 0
    @State private var isComplete = false
    @State private var tracker = ObjectVisionMatchTracker()

    var body: some View {
        ZStack {
            ObjectCameraView(
                mode: .recognition(targets),
                onFrame: handleFrame,
                onError: { cameraFailure = $0 }
            )
            .ignoresSafeArea()
            ObjectScanGuide(progress: Double(matchingFrames) / 2, active: matchingFrames > 0)
            ObjectScanTopBar(title: "Find your object", onClose: { dismiss() })
            ObjectScanPanel(
                title: candidate?.accepted == true ? "Recognizing \(candidate?.target.label ?? "object")" : "Move close to your object",
                message: statusMessage,
                progress: Double(matchingFrames) / 2
            )
        }
        .background(Color.black)
    }

    private var statusMessage: String {
        if let cameraFailure { return cameraFailure.message }
        if let issue { return issue.rawValue }
        if matchingFrames > 0 { return "Hold steady for a moment" }
        return "Fill the ring, then move the phone slowly"
    }

    private func handleFrame(_ sample: ObjectFrameSample) {
        guard !isComplete else { return }
        issue = sample.issue
        candidate = sample.candidate
        matchingFrames = tracker.accept(sample.candidate, at: sample.capturedAt)
        guard matchingFrames >= 2, let target = sample.candidate?.target else { return }
        isComplete = true
        UINotificationFeedbackGenerator().notificationOccurred(.success)
        store.dismissAlarm(with: target)
        dismiss()
    }
}

private struct ObjectScanGuide: View {
    let progress: Double
    let active: Bool

    var body: some View {
        ZStack {
            Circle()
                .stroke(Color.white.opacity(0.28), lineWidth: 7)
            Circle()
                .trim(from: 0, to: min(1, max(0, progress)))
                .stroke(Color.mint, style: StrokeStyle(lineWidth: 7, lineCap: .round))
                .rotationEffect(.degrees(-90))
            RoundedRectangle(cornerRadius: 72, style: .continuous)
                .stroke(active ? Color.mint : Color.white, lineWidth: 2)
                .padding(10)
        }
        .frame(width: 300, height: 300)
        .accessibilityHidden(true)
    }
}

private struct ObjectScanTopBar: View {
    let title: String
    let onClose: () -> Void

    var body: some View {
        VStack {
            HStack(spacing: 12) {
                Button(action: onClose) {
                    Image(systemName: "chevron.backward")
                        .font(.title3.weight(.semibold))
                        .frame(width: 44, height: 44)
                }
                .accessibilityLabel("Close scanner")
                Text(title)
                    .font(.title2.weight(.semibold))
                Spacer()
            }
            .padding(.horizontal, 8)
            .padding(.top, 8)
            .foregroundStyle(.white)
            .background(Color.black.opacity(0.66))
            Spacer()
        }
    }
}

private struct ObjectScanPanel: View {
    let title: String
    let message: String
    let progress: Double

    var body: some View {
        VStack {
            Spacer()
            VStack(alignment: .leading, spacing: 10) {
                Text(title)
                    .font(.title2.weight(.semibold))
                Text(message)
                    .font(.body)
                ProgressView(value: min(1, max(0, progress)))
                    .tint(.brandGreen)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .foregroundStyle(.white)
            .background(Color.black.opacity(0.76))
        }
    }
}

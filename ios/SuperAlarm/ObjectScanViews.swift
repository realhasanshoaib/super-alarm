import SwiftUI
import UIKit

struct AddObjectView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var label = "Fridge"
    @State private var isScanning = false

    var body: some View {
        if isScanning {
            ObjectEnrollmentView(label: label)
        } else {
            NavigationStack {
                Form {
                    Section {
                        TextField("Object name", text: $label)
                            .textInputAutocapitalization(.words)
                    } footer: {
                        Text("Choose a fixed, distinctive object away from your bed, such as your fridge or coffee machine.")
                    }
                }
                .navigationTitle("New Wake Object")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button("Cancel") { dismiss() }
                    }
                    ToolbarItem(placement: .confirmationAction) {
                        Button("Start Scan") { isScanning = true }
                            .disabled(label.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                    }
                }
            }
        }
    }
}

struct ObjectEnrollmentView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    let label: String

    @State private var fingerprints: [String] = []
    @State private var lastCaptureAt: TimeInterval = 0
    @State private var issue: ObjectFrameIssue?
    @State private var error: String?
    @State private var isComplete = false

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
            ObjectCameraView(
                mode: .enrollment,
                onFrame: handleFrame,
                onError: { error = $0 }
            )
            .ignoresSafeArea()
            ObjectScanGuide(progress: progress, active: issue == nil && error == nil)
            ObjectScanTopBar(title: "Scan \(label)", onClose: { dismiss() })
            ObjectScanPanel(
                title: fingerprints.isEmpty ? "Look at \(label)" : "Keep moving slowly",
                message: error ?? issue?.rawValue ?? instructions[min(fingerprints.count, instructions.count - 1)],
                progress: progress
            )
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
        dismiss()
    }
}

struct ObjectDismissalView: View {
    @Environment(\.dismiss) private var dismiss
    @EnvironmentObject private var store: AppStore
    let targets: [ObjectTarget]

    @State private var issue: ObjectFrameIssue?
    @State private var error: String?
    @State private var candidate: ObjectMatchCandidate?
    @State private var matchingFrames = 0
    @State private var isComplete = false
    @State private var tracker = ObjectVisionMatchTracker()

    var body: some View {
        ZStack {
            ObjectCameraView(
                mode: .recognition(targets),
                onFrame: handleFrame,
                onError: { error = $0 }
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
        if let error { return error }
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
                    .tint(.mint)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(20)
            .foregroundStyle(.white)
            .background(Color.black.opacity(0.76))
        }
    }
}

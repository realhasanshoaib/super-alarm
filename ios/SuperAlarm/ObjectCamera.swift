import AVFoundation
import ImageIO
import QuartzCore
import SwiftUI
import UIKit
import Vision

struct ObjectCameraView: UIViewControllerRepresentable {
    let mode: ObjectCaptureMode
    let onFrame: (ObjectFrameSample) -> Void
    let onError: (String) -> Void

    func makeUIViewController(context: Context) -> ObjectCameraViewController {
        ObjectCameraViewController(mode: mode, onFrame: onFrame, onError: onError)
    }

    func updateUIViewController(_ uiViewController: ObjectCameraViewController, context: Context) { }

    static func dismantleUIViewController(_ uiViewController: ObjectCameraViewController, coordinator: Void) {
        uiViewController.stop()
    }
}

final class ObjectCameraViewController: UIViewController, AVCaptureVideoDataOutputSampleBufferDelegate {
    private let mode: ObjectCaptureMode
    private let onFrame: (ObjectFrameSample) -> Void
    private let onError: (String) -> Void
    private let session = AVCaptureSession()
    private let sessionQueue = DispatchQueue(label: "com.superalarm.object-camera.session")
    private let analysisQueue = DispatchQueue(label: "com.superalarm.object-camera.analysis", qos: .userInitiated)
    private let previewLayer: AVCaptureVideoPreviewLayer
    private var videoOutput: AVCaptureVideoDataOutput?
    private var captureOrientation: AVCaptureVideoOrientation = .portrait
    private var references: [(ObjectTarget, [VNFeaturePrintObservation])] = []
    private var lastAnalyzedAt: TimeInterval = 0
    private let lifecycleLock = NSLock()
    private var _stopped = false
    private let guideRegionLock = NSLock()
    private var _guideRegion = CGRect(x: 0.12, y: 0.12, width: 0.76, height: 0.76)

    private var stopped: Bool {
        lifecycleLock.lock()
        defer { lifecycleLock.unlock() }
        return _stopped
    }

    private var guideRegion: CGRect {
        guideRegionLock.lock()
        defer { guideRegionLock.unlock() }
        return _guideRegion
    }

    init(
        mode: ObjectCaptureMode,
        onFrame: @escaping (ObjectFrameSample) -> Void,
        onError: @escaping (String) -> Void
    ) {
        self.mode = mode
        self.onFrame = onFrame
        self.onError = onError
        previewLayer = AVCaptureVideoPreviewLayer(session: session)
        if case .recognition(let targets) = mode {
            references = targets.map { target in
                (target, target.fingerprints.compactMap(ObjectFeaturePrintCodec.decode))
            }.filter { !$0.1.isEmpty && $0.0.visionRevision == ObjectFeaturePrintCodec.revision }
        }
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
        let orientation = Self.captureOrientation(
            for: view.window?.windowScene?.interfaceOrientation
        )
        if let connection = previewLayer.connection, connection.isVideoOrientationSupported {
            connection.videoOrientation = orientation
        }
        sessionQueue.async { [weak self] in
            guard let self else { return }
            self.captureOrientation = orientation
            if
                let connection = self.videoOutput?.connection(with: .video),
                connection.isVideoOrientationSupported
            {
                connection.videoOrientation = orientation
            }
        }
        let guideSide = min(280, min(view.bounds.width, view.bounds.height))
        let guideFrame = CGRect(
            x: view.bounds.midX - guideSide / 2,
            y: view.bounds.midY - guideSide / 2,
            width: guideSide,
            height: guideSide
        )
        let region = previewLayer.metadataOutputRectConverted(fromLayerRect: guideFrame)
        guideRegionLock.lock()
        _guideRegion = region
        guideRegionLock.unlock()
    }

    func stop() {
        lifecycleLock.lock()
        _stopped = true
        lifecycleLock.unlock()
        sessionQueue.async { [self] in
            videoOutput?.setSampleBufferDelegate(nil, queue: nil)
            if session.isRunning { session.stopRunning() }
            DispatchQueue.main.async { [self] in
                previewLayer.session = nil
            }
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
                    self?.emitError("Camera access is required to scan an object.")
                }
            }
        case .denied, .restricted:
            emitError("Camera access is required. Allow it in iOS Settings.")
        @unknown default:
            emitError("Camera access is unavailable.")
        }
    }

    private func configure() {
        sessionQueue.async { [weak self] in
            guard let self, !self.stopped else { return }
            self.session.beginConfiguration()
            self.session.sessionPreset = .vga640x480
            var ready = false
            defer {
                self.session.commitConfiguration()
                if ready && !self.stopped {
                    self.session.startRunning()
                }
            }

            guard
                let device = AVCaptureDevice.default(.builtInWideAngleCamera, for: .video, position: .back),
                let input = try? AVCaptureDeviceInput(device: device),
                self.session.canAddInput(input)
            else {
                self.emitError("Camera is unavailable.")
                return
            }
            self.session.addInput(input)
            if (try? device.lockForConfiguration()) != nil {
                if device.isFocusModeSupported(.continuousAutoFocus) {
                    device.focusMode = .continuousAutoFocus
                }
                device.unlockForConfiguration()
            }

            let output = AVCaptureVideoDataOutput()
            output.alwaysDiscardsLateVideoFrames = true
            output.videoSettings = [
                kCVPixelBufferPixelFormatTypeKey as String:
                    kCVPixelFormatType_420YpCbCr8BiPlanarFullRange
            ]
            guard self.session.canAddOutput(output) else {
                self.emitError("Object scanning is unavailable.")
                return
            }
            self.session.addOutput(output)
            self.videoOutput = output
            output.setSampleBufferDelegate(self, queue: self.analysisQueue)
            if let connection = output.connection(with: .video), connection.isVideoOrientationSupported {
                connection.videoOrientation = self.captureOrientation
            }
            ready = true
        }
    }

    private static func captureOrientation(
        for interfaceOrientation: UIInterfaceOrientation?
    ) -> AVCaptureVideoOrientation {
        switch interfaceOrientation {
        case .portraitUpsideDown:
            return .portraitUpsideDown
        case .landscapeLeft:
            return .landscapeLeft
        case .landscapeRight:
            return .landscapeRight
        default:
            return .portrait
        }
    }

    func captureOutput(
        _ output: AVCaptureOutput,
        didOutput sampleBuffer: CMSampleBuffer,
        from connection: AVCaptureConnection
    ) {
        let capturedAt = CACurrentMediaTime()
        guard !stopped, capturedAt - lastAnalyzedAt >= 0.1 else { return }
        lastAnalyzedAt = capturedAt
        guard let pixelBuffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        guard let region = Self.normalizedRegion(guideRegion) else {
            emit(ObjectFrameSample(fingerprint: nil, candidate: nil, issue: .blurry, capturedAt: capturedAt))
            return
        }
        if let issue = frameIssue(pixelBuffer, region: region) {
            emit(ObjectFrameSample(fingerprint: nil, candidate: nil, issue: issue, capturedAt: capturedAt))
            return
        }

        let request = VNGenerateImageFeaturePrintRequest()
        request.revision = ObjectFeaturePrintCodec.revision
        request.regionOfInterest = CGRect(
            x: region.minX,
            y: 1 - region.maxY,
            width: region.width,
            height: region.height
        )
        request.imageCropAndScaleOption = .centerCrop
        do {
            try VNImageRequestHandler(
                cvPixelBuffer: pixelBuffer,
                orientation: .up,
                options: [:]
            ).perform([request])
            guard let observation = request.results?.first else { return }
            emit(sample(from: observation, capturedAt: capturedAt))
        } catch {
            emitError("Object recognition could not start: \(error.localizedDescription)")
        }
    }

    private func sample(
        from observation: VNFeaturePrintObservation,
        capturedAt: TimeInterval
    ) -> ObjectFrameSample {
        switch mode {
        case .enrollment:
            return ObjectFrameSample(
                fingerprint: try? ObjectFeaturePrintCodec.encode(observation),
                candidate: nil,
                issue: nil,
                capturedAt: capturedAt
            )
        case .recognition:
            let candidate = references.compactMap { target, prints -> ObjectMatchCandidate? in
                guard let distance = prints.compactMap({
                    ObjectFeaturePrintCodec.distance(observation, $0)
                }).min() else { return nil }
                return ObjectMatchCandidate(target: target, distance: distance)
            }.min { $0.distance < $1.distance }
            return ObjectFrameSample(
                fingerprint: nil,
                candidate: candidate,
                issue: nil,
                capturedAt: capturedAt
            )
        }
    }

    private func emit(_ sample: ObjectFrameSample) {
        DispatchQueue.main.async { [weak self] in
            guard self?.stopped == false else { return }
            self?.onFrame(sample)
        }
    }

    private func emitError(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard self?.stopped == false else { return }
            self?.onError(message)
        }
    }

    private func frameIssue(_ pixelBuffer: CVPixelBuffer, region: CGRect) -> ObjectFrameIssue? {
        CVPixelBufferLockBaseAddress(pixelBuffer, .readOnly)
        defer { CVPixelBufferUnlockBaseAddress(pixelBuffer, .readOnly) }
        guard
            CVPixelBufferGetPlaneCount(pixelBuffer) > 0,
            let base = CVPixelBufferGetBaseAddressOfPlane(pixelBuffer, 0)
        else {
            return .blurry
        }

        let width = CVPixelBufferGetWidthOfPlane(pixelBuffer, 0)
        let height = CVPixelBufferGetHeightOfPlane(pixelBuffer, 0)
        let bytesPerRow = CVPixelBufferGetBytesPerRowOfPlane(pixelBuffer, 0)
        guard width > 0, height > 0, let region = Self.normalizedRegion(region) else {
            return .blurry
        }
        let left = max(0, min(width - 1, Int(floor(region.minX * Double(width)))))
        let top = max(0, min(height - 1, Int(floor(region.minY * Double(height)))))
        let right = max(left + 1, min(width, Int(ceil(region.maxX * Double(width)))))
        let bottom = max(top + 1, min(height, Int(ceil(region.maxY * Double(height)))))
        let side = min(right - left, bottom - top)
        guard side > 0 else { return .blurry }
        let step = max(2, side / 64)
        let columnCount = (side + step - 1) / step
        var previousRow = Array(repeating: -1, count: columnCount)
        let bytes = base.assumingMemoryBound(to: UInt8.self)
        var luminanceTotal = 0
        var edgeTotal = 0
        var edgeSamples = 0
        var samples = 0

        for y in stride(from: top, to: top + side, by: step) {
            var previous: Int?
            var column = 0
            for x in stride(from: left, to: left + side, by: step) {
                let luminance = Int(bytes[y * bytesPerRow + x])
                luminanceTotal += luminance
                samples += 1
                if let previous {
                    edgeTotal += abs(luminance - previous)
                    edgeSamples += 1
                }
                if previousRow[column] >= 0 {
                    edgeTotal += abs(luminance - previousRow[column])
                    edgeSamples += 1
                }
                previousRow[column] = luminance
                previous = luminance
                column += 1
            }
        }

        let averageLuminance = Double(luminanceTotal) / Double(samples)
        let averageEdge = edgeSamples == 0 ? 0 : Double(edgeTotal) / Double(edgeSamples)
        if averageLuminance < 38 { return .tooDark }
        if averageLuminance > 220 { return .tooBright }
        if averageEdge < 2 { return .blurry }
        return nil
    }

    private static func normalizedRegion(_ region: CGRect) -> CGRect? {
        guard
            region.minX.isFinite,
            region.minY.isFinite,
            region.maxX.isFinite,
            region.maxY.isFinite
        else {
            return nil
        }
        let unitRegion = CGRect(x: 0, y: 0, width: 1, height: 1)
        let normalized = region.standardized.intersection(unitRegion)
        return normalized.isNull || normalized.isEmpty ? nil : normalized
    }
}

import Foundation
import Vision

enum ObjectFeaturePrintCodec {
    static let revision = VNGenerateImageFeaturePrintRequestRevision1

    static func encode(_ observation: VNFeaturePrintObservation) throws -> String {
        try NSKeyedArchiver.archivedData(withRootObject: observation, requiringSecureCoding: true)
            .base64EncodedString()
    }

    static func decode(_ encoded: String) -> VNFeaturePrintObservation? {
        guard let data = Data(base64Encoded: encoded) else { return nil }
        return try? NSKeyedUnarchiver.unarchivedObject(ofClass: VNFeaturePrintObservation.self, from: data)
    }

    static func distance(_ first: String, _ second: String) -> Float? {
        guard let firstPrint = decode(first), let secondPrint = decode(second) else { return nil }
        return distance(firstPrint, secondPrint)
    }

    static func distance(_ first: VNFeaturePrintObservation, _ second: VNFeaturePrintObservation) -> Float? {
        var distance: Float = 0
        guard (try? first.computeDistance(&distance, to: second)) != nil else { return nil }
        return distance
    }

    static func selectDiverse(_ fingerprints: [String], count: Int) -> [String] {
        guard fingerprints.count > count else { return fingerprints }
        let decoded = fingerprints.compactMap { fingerprint in
            decode(fingerprint).map { (fingerprint, $0) }
        }
        guard decoded.count > count else { return decoded.map(\.0) }

        var selected = [decoded[0]]
        var remaining = Array(decoded.dropFirst())
        while selected.count < count, !remaining.isEmpty {
            let nextIndex = remaining.indices.max { left, right in
                minimumDistance(from: remaining[left].1, to: selected) <
                    minimumDistance(from: remaining[right].1, to: selected)
            } ?? remaining.startIndex
            selected.append(remaining.remove(at: nextIndex))
        }
        return selected.map(\.0)
    }

    static func matchThreshold(for fingerprints: [String]) -> Float {
        let prints = fingerprints.compactMap(decode)
        guard prints.count > 1 else { return 14 }
        let nearestDistances = prints.indices.compactMap { index -> Float? in
            prints.indices
                .filter { $0 != index }
                .compactMap { distance(prints[index], prints[$0]) }
                .min()
        }.sorted()
        guard !nearestDistances.isEmpty else { return 14 }
        let median = nearestDistances[nearestDistances.count / 2]
        return min(18, max(8, median * 1.6 + 2))
    }

    private static func minimumDistance(
        from candidate: VNFeaturePrintObservation,
        to selected: [(String, VNFeaturePrintObservation)]
    ) -> Float {
        selected.compactMap { distance(candidate, $0.1) }.min() ?? 0
    }
}

struct ObjectMatchCandidate {
    let target: ObjectTarget
    let distance: Float

    var accepted: Bool {
        distance <= target.matchDistanceThreshold
    }
}

struct ObjectFrameSample {
    let fingerprint: String?
    let candidate: ObjectMatchCandidate?
    let issue: ObjectFrameIssue?
    let capturedAt: TimeInterval
}

enum ObjectCaptureMode {
    case enrollment
    case recognition([ObjectTarget])
}

final class ObjectVisionMatchTracker {
    private var recentMatches: [(UUID?, TimeInterval)] = []

    func accept(_ candidate: ObjectMatchCandidate?, at timestamp: TimeInterval) -> Int {
        recentMatches.append((candidate?.accepted == true ? candidate?.target.id : nil, timestamp))
        recentMatches = Array(recentMatches.suffix(3))
            .filter { timestamp - $0.1 <= 0.65 }
        guard candidate?.accepted == true, let targetId = candidate?.target.id else { return 0 }
        return recentMatches.count { $0.0 == targetId }
    }
}

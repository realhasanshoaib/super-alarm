import Foundation
import CoreGraphics

enum ObjectFrameIssue: String {
    case tooDark = "Add light so the object's details are visible"
    case tooBright = "Move away from glare"
    case lowDetail = "Include an edge, handle, or distinctive detail"
}

enum ObjectCaptureQuality {
    static func issue(averageLuminance: Double, averageEdge: Double, clippedFraction: Double) -> ObjectFrameIssue? {
        if averageLuminance < 18 && averageEdge < 2 { return .tooDark }
        if clippedFraction > 0.95 { return .tooBright }
        if averageEdge < 2 { return .lowDetail }
        return nil
    }

    static func normalizedRegion(pixelRect: CGRect, width: Int, height: Int) -> CGRect? {
        guard width > 0, height > 0,
              pixelRect.minX.isFinite, pixelRect.minY.isFinite,
              pixelRect.maxX.isFinite, pixelRect.maxY.isFinite else { return nil }
        let normalized = CGRect(
            x: pixelRect.minX / Double(width), y: pixelRect.minY / Double(height),
            width: pixelRect.width / Double(width), height: pixelRect.height / Double(height)
        ).standardized.intersection(CGRect(x: 0, y: 0, width: 1, height: 1))
        return normalized.isNull || normalized.isEmpty ? nil : normalized
    }
}

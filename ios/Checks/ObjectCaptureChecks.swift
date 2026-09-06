import Foundation
import CoreGraphics

@main
enum ObjectCaptureChecks {
    static func main() {
        precondition(ObjectCaptureQuality.issue(averageLuminance: 230, averageEdge: 8, clippedFraction: 0.05) == nil)
        precondition(ObjectCaptureQuality.issue(averageLuminance: 30, averageEdge: 5, clippedFraction: 0) == nil)
        precondition(ObjectCaptureQuality.issue(averageLuminance: 5, averageEdge: 0.2, clippedFraction: 0) == .tooDark)
        precondition(ObjectCaptureQuality.issue(averageLuminance: 254, averageEdge: 0.2, clippedFraction: 0.99) == .tooBright)
        precondition(ObjectCaptureQuality.issue(averageLuminance: 150, averageEdge: 0.5, clippedFraction: 0) == .lowDetail)
        let portrait = ObjectCaptureQuality.normalizedRegion(
            pixelRect: CGRect(x: 100, y: 180, width: 280, height: 280), width: 480, height: 640
        )!
        precondition(abs(portrait.width * 480 - portrait.height * 640) < 0.001)
        precondition(abs(portrait.midX - 0.5) < 0.001 && abs(portrait.midY - 0.5) < 0.001)
        precondition(ObjectCaptureQuality.normalizedRegion(pixelRect: .zero, width: 480, height: 640) == nil)
        print("Object capture checks passed")
    }
}

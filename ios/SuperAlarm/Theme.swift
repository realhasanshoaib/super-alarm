import SwiftUI

extension Color {
    /// Filled blue actions keep white labels readable on both light and camera surfaces.
    static let brandBlue = Color(red: 29 / 255, green: 56 / 255, blue: 165 / 255)
    /// Deep navy from the Super Alarm logo (#0A1646).
    static let brandNavy = Color(red: 10 / 255, green: 22 / 255, blue: 70 / 255)
    /// Near-black night blue used behind the navy on the ringing screen.
    static let brandNight = Color(red: 4 / 255, green: 9 / 255, blue: 32 / 255)
    /// Lifted periwinkle from the accent's dark variant, for text on navy.
    static let brandSky = Color(red: 119 / 255, green: 145 / 255, blue: 248 / 255)
    /// Sunrise orange from the logo (#FD9102), reserved for tiny accents.
    static let brandOrange = Color(red: 253 / 255, green: 145 / 255, blue: 2 / 255)
    /// Green dot from the logo (#04B479), used for success states.
    static let brandGreen = Color(red: 4 / 255, green: 180 / 255, blue: 121 / 255)
}

extension View {
    /// Liquid Glass on iOS 26, material fallback earlier.
    @ViewBuilder
    func brandGlass<S: Shape>(in shape: S) -> some View {
        if #available(iOS 26.0, *) {
            glassEffect(.regular, in: shape)
        } else {
            background(.thinMaterial, in: shape)
        }
    }

    @ViewBuilder
    func brandProminentButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glassProminent)
        } else {
            buttonStyle(.borderedProminent)
        }
    }

    @ViewBuilder
    func brandGlassButtonStyle() -> some View {
        if #available(iOS 26.0, *) {
            buttonStyle(.glass)
        } else {
            buttonStyle(.bordered)
        }
    }
}

/// The app logo tile, presented like an app icon.
struct BrandMark: View {
    var size: CGFloat = 64

    private var shape: RoundedRectangle {
        RoundedRectangle(cornerRadius: size * 0.22, style: .continuous)
    }

    var body: some View {
        Image("AppLogo")
            .resizable()
            .scaledToFit()
            .frame(width: size, height: size)
            .clipShape(shape)
            .overlay(shape.strokeBorder(.primary.opacity(0.08), lineWidth: 0.5))
            .accessibilityHidden(true)
    }
}

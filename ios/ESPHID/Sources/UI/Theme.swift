import SwiftUI
import UIKit

/// Design tokens shared with the on-device console and the Android app
/// (graphite neutrals, amber indicator accent, system + monospaced readouts).
enum Palette {
  static let paper = dyn(0xF8F6F4, 0x110E0B)
  static let surface = dyn(0xFEFEFC, 0x1A1613)
  static let sunken = dyn(0xEDEBE8, 0x0A0806)
  static let line = dyn(0xDBD9D5, 0x2F2A26)
  static let lineStrong = dyn(0xBBB7B2, 0x4A443F)
  static let ink = dyn(0x211C18, 0xEEEBE5)
  static let ink2 = dyn(0x544F4A, 0xB8B3AD)
  static let ink3 = dyn(0x78746F, 0x8D8883)
  static let accent = dyn(0xEF861B, 0xF99B39)
  static let accentStrong = dyn(0xE06D00, 0xED8725)
  static let tint = dyn(0xB85500, 0xF99B39)
  static let onAccent = dyn(0x23140A, 0x211208)
  static let accentWash = dyn(0xFCE3C8, 0x3B220D)
  static let ok = dyn(0x218A45, 0x5AC576)
  static let bad = dyn(0xC22826, 0xF66D62)
  static let dot = dyn(0xCDCAC6, 0x342F2B)

  private static func dyn(_ light: UInt32, _ dark: UInt32) -> Color {
    Color(UIColor { $0.userInterfaceStyle == .dark ? UIColor(hex: dark) : UIColor(hex: light) })
  }
}

extension UIColor {
  convenience init(hex: UInt32) {
    self.init(
      red: CGFloat((hex >> 16) & 0xFF) / 255, green: CGFloat((hex >> 8) & 0xFF) / 255,
      blue: CGFloat(hex & 0xFF) / 255, alpha: 1)
  }
}

extension Font {
  static let readout = Font.system(.subheadline, design: .monospaced)
  static let readoutSmall = Font.system(.caption, design: .monospaced)
}

// MARK: - Buttons

struct PrimaryButtonStyle: ButtonStyle {
  @Environment(\.isEnabled) private var enabled
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .font(.body.weight(.semibold))
      .foregroundStyle(Palette.onAccent)
      .frame(maxWidth: .infinity, minHeight: 50)
      .background(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
          .fill(configuration.isPressed ? Palette.accentStrong : Palette.accent)
      )
      .opacity(enabled ? 1 : 0.5)
      .scaleEffect(configuration.isPressed ? 0.99 : 1)
      .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
  }
}

struct SecondaryButtonStyle: ButtonStyle {
  @Environment(\.isEnabled) private var enabled
  func makeBody(configuration: Configuration) -> some View {
    configuration.label
      .font(.body.weight(.medium))
      .foregroundStyle(Palette.ink)
      .frame(maxWidth: .infinity, minHeight: 50)
      .background(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
          .fill(configuration.isPressed ? Palette.sunken : Palette.surface)
      )
      .overlay(
        RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Palette.lineStrong)
      )
      .opacity(enabled ? 1 : 0.5)
  }
}

/// A physical-looking keycap. `on` renders the latched (sticky modifier) state.
struct KeycapStyle: ButtonStyle {
  var on = false
  var minWidth: CGFloat = 48
  func makeBody(configuration: Configuration) -> some View {
    let pressed = configuration.isPressed
    configuration.label
      .font(.subheadline.weight(.medium))
      .lineLimit(1)
      .foregroundStyle(on ? Palette.onAccent : Palette.ink)
      .padding(.horizontal, 10)
      .frame(minWidth: minWidth, minHeight: 44)
      .background(
        RoundedRectangle(cornerRadius: 8, style: .continuous)
          .fill(on ? Palette.accent : (pressed ? Palette.sunken : Palette.surface))
      )
      .overlay(
        RoundedRectangle(cornerRadius: 8, style: .continuous)
          .strokeBorder(on ? Palette.accentStrong : Palette.lineStrong, lineWidth: 1)
      )
      .overlay(alignment: .bottom) {
        if !pressed && !on {
          RoundedRectangle(cornerRadius: 8, style: .continuous)
            .fill(Palette.lineStrong).frame(height: 2).padding(.horizontal, 2).offset(y: -0.5)
            .mask(RoundedRectangle(cornerRadius: 8, style: .continuous))
            .allowsHitTesting(false)
        }
      }
      .offset(y: pressed ? 1 : 0)
      .contentShape(Rectangle())
  }
}

// MARK: - Small components

struct StatusDot: View {
  enum Tone { case live, wait, bad, off }
  var tone: Tone
  var body: some View {
    Circle()
      .fill(color)
      .frame(width: 8, height: 8)
      .background(Circle().fill(color.opacity(0.22)).frame(width: 14, height: 14))
      .accessibilityHidden(true)
  }
  private var color: Color {
    switch tone {
    case .live: Palette.ok
    case .wait: Palette.accent
    case .bad: Palette.bad
    case .off: Palette.ink3
    }
  }
}

struct Chip: View {
  var tone: StatusDot.Tone
  var text: String
  var body: some View {
    HStack(spacing: 6) {
      Circle().fill(dotColor).frame(width: 6, height: 6)
      Text(text).font(.caption.weight(.medium)).foregroundStyle(Palette.ink2).lineLimit(1)
    }
    .padding(.horizontal, 8)
    .frame(height: 24)
    .background(Capsule().fill(Palette.sunken))
  }
  private var dotColor: Color {
    switch tone {
    case .live: Palette.ok
    case .wait: Palette.accent
    case .bad: Palette.bad
    case .off: Palette.ink3
    }
  }
}

struct ReadoutRow: View {
  var label: String
  var value: String
  var tone: Color? = nil
  var body: some View {
    LabeledContent(label) {
      Text(value).font(.readout).foregroundStyle(tone ?? Palette.ink).textSelection(.enabled)
        .multilineTextAlignment(.trailing)
    }
  }
}

struct SignalBars: View {
  var rssi: Int
  var body: some View {
    let n = rssi >= -55 ? 4 : rssi >= -67 ? 3 : rssi >= -78 ? 2 : 1
    HStack(alignment: .bottom, spacing: 2) {
      ForEach(1...4, id: \.self) { i in
        RoundedRectangle(cornerRadius: 1)
          .fill(i <= n ? Palette.ink2 : Palette.lineStrong)
          .frame(width: 3, height: CGFloat(3 + i * 3))
      }
    }
    .accessibilityLabel("Signal \(n) of 4")
  }
}

/// Monospaced access key display with copy.
struct KeyField: View {
  @Binding var text: String
  var placeholder = "Access key"
  var body: some View {
    TextField(placeholder, text: $text)
      .font(.system(.body, design: .monospaced))
      .textInputAutocapitalization(.never)
      .autocorrectionDisabled()
      .textContentType(.password)
  }
}

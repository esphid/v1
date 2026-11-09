import ESPHIDKit
import SwiftUI
import UIKit

// MARK: - Trackpad

struct PointerPrefs: Equatable {
  var sensitivity: Double
  var acceleration: Double
  var natural: Bool
  var haptics: Bool
}

/// Multi-touch trackpad. Uses coalesced touches so ProMotion devices feed every 120/240 Hz sample.
/// 1 finger: move · tap: left click · 2-finger tap: right click · 3-finger tap: middle click
/// 2 fingers: scroll · press and hold, then move: drag
struct Trackpad: UIViewRepresentable {
  var prefs: PointerPrefs
  var onMove: (Double, Double) -> Void
  var onScroll: (Double, Double) -> Void
  var onClick: (MouseButtons) -> Void
  var onDrag: (Bool) -> Void
  var onActive: (Bool) -> Void = { _ in }

  func makeUIView(context: Context) -> TrackpadSurface {
    let v = TrackpadSurface()
    v.isMultipleTouchEnabled = true
    v.backgroundColor = .clear
    v.isAccessibilityElement = true
    v.accessibilityLabel = "Trackpad"
    v.accessibilityHint = "Drag to move the pointer. Tap to click."
    v.accessibilityTraits = .allowsDirectInteraction
    return v
  }

  func updateUIView(_ v: TrackpadSurface, context: Context) {
    v.prefs = prefs
    v.onMove = onMove
    v.onScroll = onScroll
    v.onClick = onClick
    v.onDrag = onDrag
    v.onActive = onActive
  }
}

final class TrackpadSurface: UIView {
  var prefs = PointerPrefs(sensitivity: 1.4, acceleration: 0.6, natural: true, haptics: true)
  var onMove: (Double, Double) -> Void = { _, _ in }
  var onScroll: (Double, Double) -> Void = { _, _ in }
  var onClick: (MouseButtons) -> Void = { _ in }
  var onDrag: (Bool) -> Void = { _ in }
  var onActive: (Bool) -> Void = { _ in }

  private var touchesDown = Set<UITouch>()
  private var maxTouches = 0
  private var startTime: TimeInterval = 0
  private var travel: CGFloat = 0
  private var lastSample: TimeInterval = 0
  private var dragging = false
  private var holdTimer: Timer?
  private var accel = PointerAccelerator()
  private let tapHaptic = UIImpactFeedbackGenerator(style: .light)
  private let dragHaptic = UIImpactFeedbackGenerator(style: .medium)

  private let scrollStep: CGFloat = 14  // points of finger travel per wheel notch
  private let tapTime: TimeInterval = 0.25
  private let holdTime: TimeInterval = 0.42

  override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
    if touchesDown.isEmpty {
      maxTouches = 0
      travel = 0
      startTime = event?.timestamp ?? CACurrentMediaTime()
      lastSample = startTime
      onActive(true)
      if prefs.haptics { tapHaptic.prepare() }
    }
    touchesDown.formUnion(touches)
    maxTouches = max(maxTouches, touchesDown.count)
    holdTimer?.invalidate()
    if touchesDown.count == 1 && !dragging {
      holdTimer = Timer.scheduledTimer(withTimeInterval: holdTime, repeats: false) { [weak self] _ in
        MainActor.assumeIsolated { self?.beginDrag() }
      }
    }
  }

  private func beginDrag() {
    guard touchesDown.count == 1, travel < 10, !dragging else { return }
    dragging = true
    if prefs.haptics { dragHaptic.impactOccurred() }
    onDrag(true)
  }

  override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
    accel.sensitivity = prefs.sensitivity
    accel.acceleration = prefs.acceleration
    if touchesDown.count == 1, let t = touches.first {
      for s in event?.coalescedTouches(for: t) ?? [t] {
        let p = s.location(in: self)
        let q = s.previousLocation(in: self)
        let dx = Double(p.x - q.x)
        let dy = Double(p.y - q.y)
        travel += abs(p.x - q.x) + abs(p.y - q.y)
        let dt = max(0.001, s.timestamp - lastSample)
        lastSample = s.timestamp
        let out = accel.apply(dx: dx, dy: dy, dt: dt)
        onMove(out.dx, out.dy)
      }
      if travel >= 10 { holdTimer?.invalidate() }
    } else if touchesDown.count >= 2 {
      holdTimer?.invalidate()
      // Centroid motion of all fingers on the pad, from this event's touches.
      var sx: CGFloat = 0
      var sy: CGFloat = 0
      for t in touches {
        let p = t.location(in: self)
        let q = t.previousLocation(in: self)
        sx += p.x - q.x
        sy += p.y - q.y
      }
      let n = CGFloat(touchesDown.count)
      sx /= n
      sy /= n
      travel += abs(sx) + abs(sy)
      let sign: CGFloat = prefs.natural ? 1 : -1
      onScroll(Double(sy * sign / scrollStep), Double(-sx * sign / scrollStep))
    }
  }

  override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
    finish(touches, event: event, cancelled: false)
  }

  override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
    finish(touches, event: event, cancelled: true)
  }

  private func finish(_ touches: Set<UITouch>, event: UIEvent?, cancelled: Bool) {
    touchesDown.subtract(touches)
    guard touchesDown.isEmpty else { return }
    holdTimer?.invalidate()
    onActive(false)
    if dragging {
      dragging = false
      onDrag(false)
      return
    }
    let now = event?.timestamp ?? CACurrentMediaTime()
    guard !cancelled, now - startTime < tapTime, travel < 10 * CGFloat(maxTouches) else { return }
    let button: MouseButtons = maxTouches >= 3 ? .middle : maxTouches == 2 ? .right : .left
    if prefs.haptics { tapHaptic.impactOccurred(intensity: 0.6) }
    onClick(button)
  }
}

// MARK: - Keyboard capture

/// Invisible first responder that turns the iOS keyboard into a remote keyboard.
/// Software keys arrive as text; a hardware keyboard (iPad, Magic Keyboard) is passed
/// through as raw HID key down/up, including modifiers, so shortcuts work exactly.
struct KeyCapture: UIViewRepresentable {
  @Binding var isActive: Bool
  var onText: (String) -> Void
  var onBackspace: () -> Void
  var onHardwareKey: (UInt8, Bool) -> Void

  func makeUIView(context: Context) -> KeyCaptureView {
    let v = KeyCaptureView()
    v.onResign = { DispatchQueue.main.async { if isActive { isActive = false } } }
    return v
  }

  func updateUIView(_ v: KeyCaptureView, context: Context) {
    v.onText = onText
    v.onBackspace = onBackspace
    v.onHardwareKey = onHardwareKey
    v.onResign = { DispatchQueue.main.async { if isActive { isActive = false } } }
    if isActive, !v.isFirstResponder {
      DispatchQueue.main.async { _ = v.becomeFirstResponder() }
    } else if !isActive, v.isFirstResponder {
      DispatchQueue.main.async { _ = v.resignFirstResponder() }
    }
  }
}

final class KeyCaptureView: UIView, UIKeyInput, UITextInputTraits {
  var onText: (String) -> Void = { _ in }
  var onBackspace: () -> Void = {}
  var onHardwareKey: (UInt8, Bool) -> Void = { _, _ in }
  var onResign: () -> Void = {}

  // UITextInputTraits: send exactly what was typed.
  var autocorrectionType: UITextAutocorrectionType = .no
  var autocapitalizationType: UITextAutocapitalizationType = .none
  var spellCheckingType: UITextSpellCheckingType = .no
  var smartQuotesType: UITextSmartQuotesType = .no
  var smartDashesType: UITextSmartDashesType = .no
  var smartInsertDeleteType: UITextSmartInsertDeleteType = .no
  var keyboardType: UIKeyboardType = .asciiCapable
  var returnKeyType: UIReturnKeyType = .default
  var inlinePredictionType: UITextInlinePredictionType = .no

  override var canBecomeFirstResponder: Bool { true }

  override func resignFirstResponder() -> Bool {
    let r = super.resignFirstResponder()
    if r { onResign() }
    return r
  }

  var hasText: Bool { true }  // so Delete keeps working on an "empty" field
  func insertText(_ text: String) { onText(text) }
  func deleteBackward() { onBackspace() }

  private var held = Set<UInt8>()

  override func pressesBegan(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    var rest = Set<UIPress>()
    for p in presses {
      if let u = usage(p) {
        held.insert(u)
        onHardwareKey(u, true)
      } else {
        rest.insert(p)
      }
    }
    if !rest.isEmpty { super.pressesBegan(rest, with: event) }
  }

  override func pressesEnded(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    release(presses, event: event, cancelled: false)
  }

  override func pressesCancelled(_ presses: Set<UIPress>, with event: UIPressesEvent?) {
    release(presses, event: event, cancelled: true)
  }

  private func release(_ presses: Set<UIPress>, event: UIPressesEvent?, cancelled: Bool) {
    var rest = Set<UIPress>()
    for p in presses {
      if let u = usage(p), held.remove(u) != nil {
        onHardwareKey(u, false)
      } else {
        rest.insert(p)
      }
    }
    if !rest.isEmpty {
      if cancelled { super.pressesCancelled(rest, with: event) } else {
        super.pressesEnded(rest, with: event)
      }
    }
  }

  /// UIKeyboardHIDUsage values are USB HID usages (page 0x07).
  private func usage(_ p: UIPress) -> UInt8? {
    guard let key = p.key else { return nil }
    let raw = key.keyCode.rawValue
    guard raw >= 0x04, raw <= 0xE7 else { return nil }
    return UInt8(raw)
  }
}

// MARK: - Text → key taps for sticky modifiers

/// US layout: character → (needs shift, key). Used when a sticky modifier is latched,
/// so "Ctrl" + typing "c" sends Ctrl+C instead of the letter.
enum USLayout {
  static func key(for ch: Character) -> (HIDKey, Bool)? {
    let s = String(ch)
    if let k = lower[s] { return (k, false) }
    if let k = upper[s] { return (k, true) }
    return nil
  }
  private static let letters: [HIDKey] = [
    .a, .b, .c, .d, .e, .f, .g, .h, .i, .j, .k, .l, .m, .n, .o, .p, .q, .r, .s, .t, .u, .v, .w,
    .x, .y, .z,
  ]
  private static let digits: [HIDKey] = [
    .digit1, .digit2, .digit3, .digit4, .digit5, .digit6, .digit7, .digit8, .digit9, .digit0,
  ]
  private static let lower: [String: HIDKey] = {
    var m = [String: HIDKey]()
    for (i, k) in letters.enumerated() { m[String(UnicodeScalar(97 + i)!)] = k }
    for (i, k) in digits.enumerated() { m[String("1234567890".dropFirst(i).first!)] = k }
    let p: [(String, HIDKey)] = [
      (" ", .space), ("-", .minus), ("=", .equal), ("[", .lbracket), ("]", .rbracket),
      ("\\", .backslash), (";", .semicolon), ("'", .quote), ("`", .grave), (",", .comma),
      (".", .period), ("/", .slash), ("\n", .enter), ("\t", .tab),
    ]
    for (c, k) in p { m[c] = k }
    return m
  }()
  private static let upper: [String: HIDKey] = {
    var m = [String: HIDKey]()
    for (i, k) in letters.enumerated() { m[String(UnicodeScalar(65 + i)!)] = k }
    for (i, k) in digits.enumerated() { m[String("!@#$%^&*()".dropFirst(i).first!)] = k }
    let p: [(String, HIDKey)] = [
      ("_", .minus), ("+", .equal), ("{", .lbracket), ("}", .rbracket), ("|", .backslash),
      (":", .semicolon), ("\"", .quote), ("~", .grave), ("<", .comma), (">", .period),
      ("?", .slash),
    ]
    for (c, k) in p { m[c] = k }
    return m
  }()
}

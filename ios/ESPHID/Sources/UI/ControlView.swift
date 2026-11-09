import ESPHIDKit
import SwiftUI

struct ControlView: View {
  let deviceID: String
  @Environment(AppModel.self) private var model
  @AppStorage("pointer.sensitivity") private var sensitivity = 1.4
  @AppStorage("pointer.acceleration") private var acceleration = 0.6
  @AppStorage("pointer.natural") private var natural = true
  @AppStorage("haptics") private var haptics = true

  @State private var keyboardUp = false
  @State private var sticky: Modifiers = []
  @State private var showKeys = false
  @State private var showSettings = false
  @State private var padActive = false
  @State private var held: MouseButtons = []

  private var conn: HIDConnection { model.connection }
  private var device: SavedDevice? { model.device(deviceID) }
  private var live: Bool { conn.state == .connected }

  var body: some View {
    VStack(spacing: 12) {
      statusStrip
      padUnit
      if keyboardUp {
        KeyBar(sticky: $sticky, onKey: tapKey, onHide: { keyboardUp = false })
      } else {
        HStack(spacing: 10) {
          Button { keyboardUp = true } label: { Label("Keyboard", systemImage: "keyboard") }
            .buttonStyle(SecondaryButtonStyle())
          Button { showKeys = true } label: { Label("Keys & media", systemImage: "command") }
            .buttonStyle(SecondaryButtonStyle())
        }
        .disabled(!live)
      }
    }
    .padding(.horizontal, 16)
    .padding(.bottom, 8)
    .background(Palette.paper.ignoresSafeArea())
    .background {
      KeyCapture(
        isActive: $keyboardUp, onText: typed, onBackspace: backspace, onHardwareKey: hardwareKey
      )
      .frame(width: 0, height: 0)
    }
    .navigationTitle(device?.name ?? "Device")
    .navigationBarTitleDisplayMode(.inline)
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) { linkMenu }
      ToolbarItem(placement: .topBarTrailing) {
        Button { showSettings = true } label: {
          Image(systemName: "slider.horizontal.3").accessibilityLabel("Device settings")
        }
      }
    }
    .task(id: deviceID) { if let d = device { await model.open(d) } }
    .onDisappear { model.close() }
    .onChange(of: keyboardUp) { _, up in if !up, !sticky.isEmpty { sticky = [] } }
    .sheet(isPresented: $showKeys) {
      KeysSheet(onKey: { k in conn.tap(k, modifiers: sticky); sticky = [] }, onConsumer: conn.consumerTap,
        onRelease: { sticky = []; conn.releaseAll() })
        .presentationDetents([.medium, .large])
    }
    .sheet(isPresented: $showSettings) { DeviceSettingsView(deviceID: deviceID) }
  }

  // MARK: status + link

  private var statusStrip: some View {
    HStack(spacing: 10) {
      Chip(tone: usbTone, text: usbText)
      Spacer(minLength: 0)
      HStack(spacing: 10) {
        LedLabel(text: "NUM", on: conn.leds.contains(.numLock))
        LedLabel(text: "CAPS", on: conn.leds.contains(.capsLock))
        LedLabel(text: "SCRL", on: conn.leds.contains(.scrollLock))
      }
      .accessibilityElement(children: .combine)
    }
    .padding(.top, 4)
  }

  private var usbTone: StatusDot.Tone {
    guard live, let m = conn.usbMounted else { return .off }
    return m ? .live : .bad
  }
  private var usbText: String {
    guard live else { return "Not connected" }
    switch conn.usbMounted {
    case .some(true): return "Host connected"
    case .some(false): return "No USB host"
    case .none: return "Host status unknown"
    }
  }

  private var linkMenu: some View {
    Menu {
      if let d = device {
        Section("Connect over") {
          ForEach(model.transports(for: d), id: \.self) { k in
            Button {
              model.setPreferred(k, for: d)
              let fresh = model.device(d.id) ?? d
              Task { await model.open(fresh, via: k) }
            } label: {
              if model.activeTransport == k {
                Label(k.title, systemImage: "checkmark")
              } else {
                Text(k.title)
              }
              Text(k.detail)
            }
          }
        }
        Button("Reconnect", systemImage: "arrow.clockwise") {
          Task { await model.open(d, via: model.activeTransport) }
        }
      }
    } label: {
      HStack(spacing: 6) {
        StatusDot(tone: linkTone)
        Text(linkText).font(.readoutSmall).foregroundStyle(Palette.ink2).monospacedDigit()
      }
      .padding(.horizontal, 10).frame(height: 30)
      .background(Capsule().fill(Palette.sunken))
    }
    .accessibilityLabel("Connection: \(linkText)")
  }

  private var linkTone: StatusDot.Tone {
    switch conn.state {
    case .connected: .live
    case .connecting, .authenticating: .wait
    case .failed: .bad
    case .idle: model.connectError == nil ? .off : .bad
    }
  }
  private var linkText: String {
    let name = model.activeTransport?.title ?? "—"
    switch conn.state {
    case .connected:
      guard let r = conn.rttMs else { return name }
      return "\(name) · \(r < 10 ? String(format: "%.1f", r) : String(Int(r.rounded()))) ms"
    case .connecting, .authenticating: return name
    case .failed, .idle: return "Offline"
    }
  }

  // MARK: pad

  private var padUnit: some View {
    VStack(spacing: 1) {
      ZStack {
        DotGrid().fill(Palette.dot)
        Trackpad(
          prefs: PointerPrefs(
            sensitivity: sensitivity, acceleration: acceleration, natural: natural,
            haptics: haptics),
          onMove: { conn.moveMouse(dx: $0, dy: $1) },
          onScroll: { conn.scroll(wheel: $0, pan: $1) },
          onClick: { conn.click($0) },
          onDrag: { down in
            held = down ? held.union(.left) : held.subtracting(.left)
            conn.setButtons(held)
          },
          onActive: { padActive = $0 }
        )
        .allowsHitTesting(live)
        padOverlay.allowsHitTesting(!live)
      }
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .background(Palette.sunken)
      HStack(spacing: 1) {
        MouseButtonView(title: "LEFT", bit: .left, held: $held, send: conn.setButtons)
        MouseButtonView(title: "MID", bit: .middle, held: $held, send: conn.setButtons)
          .frame(width: 76)
        MouseButtonView(title: "RIGHT", bit: .right, held: $held, send: conn.setButtons)
      }
      .frame(height: 54)
      .disabled(!live)
    }
    .background(Palette.lineStrong)
    .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
    .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(Palette.lineStrong))
  }

  @ViewBuilder private var padOverlay: some View {
    switch conn.state {
    case .connected:
      Text("Drag to move · tap to click\nTwo fingers to scroll · hold, then move to drag")
        .font(.footnote).foregroundStyle(Palette.ink3).multilineTextAlignment(.center)
        .opacity(padActive ? 0 : 1)
        .animation(.easeOut(duration: 0.18), value: padActive)
        .allowsHitTesting(false)
    case .connecting, .authenticating:
      VStack(spacing: 10) {
        ProgressView()
        Text(
          conn.state == .authenticating
            ? "Checking access key…" : "Connecting over \(model.activeTransport?.title ?? "")…"
        )
        .font(.subheadline).foregroundStyle(Palette.ink2)
        if model.activeTransport == .ble {
          Text("If iOS asks to pair, tap Pair.").font(.footnote).foregroundStyle(Palette.ink3)
        }
      }
    case .idle, .failed:
      if let d = device { OfflineCard(device: d, message: failureText) }
    }
  }

  private var failureText: String {
    if let e = model.connectError { return e }
    if case .failed(let m) = conn.state { return m }
    return "Not connected."
  }

  // MARK: keyboard

  private func typed(_ text: String) {
    guard live else { return }
    if !sticky.isEmpty, text.count == 1, let ch = text.first, let (k, shift) = USLayout.key(for: ch) {
      conn.tap(k, modifiers: shift ? sticky.union(.shift) : sticky)
      sticky = []
      return
    }
    conn.type(text)
    if !sticky.isEmpty { sticky = [] }
  }

  private func backspace() {
    guard live else { return }
    conn.tap(.backspace, modifiers: sticky)
    sticky = []
  }

  private func tapKey(_ k: HIDKey) {
    conn.tap(k, modifiers: sticky)
    sticky = []
  }

  private func hardwareKey(_ usage: UInt8, _ down: Bool) {
    guard live, let k = HIDKey(rawValue: usage) else { return }
    if down { conn.keyDown(k) } else { conn.keyUp(k) }
  }
}

// MARK: - Pieces

private struct DotGrid: Shape {
  func path(in rect: CGRect) -> Path {
    var p = Path()
    let step: CGFloat = 16
    var y = step / 2
    while y < rect.height {
      var x = step / 2
      while x < rect.width {
        p.addEllipse(in: CGRect(x: x - 0.75, y: y - 0.75, width: 1.5, height: 1.5))
        x += step
      }
      y += step
    }
    return p
  }
}

private struct LedLabel: View {
  var text: String
  var on: Bool
  var body: some View {
    HStack(spacing: 4) {
      Circle().fill(on ? Palette.accentStrong : Palette.lineStrong).frame(width: 6, height: 6)
      Text(text).font(.readoutSmall).foregroundStyle(on ? Palette.ink : Palette.ink3)
    }
    .accessibilityLabel("\(text) \(on ? "on" : "off")")
  }
}

private struct MouseButtonView: View {
  var title: String
  var bit: MouseButtons
  @Binding var held: MouseButtons
  var send: (MouseButtons) -> Void
  @State private var down = false

  var body: some View {
    Text(title)
      .font(.caption.weight(.semibold)).tracking(0.6)
      .foregroundStyle(down ? Palette.ink : Palette.ink2)
      .frame(maxWidth: .infinity, maxHeight: .infinity)
      .background(down ? Palette.accentWash : Palette.surface)
      .contentShape(Rectangle())
      .gesture(
        DragGesture(minimumDistance: 0)
          .onChanged { _ in
            guard !down else { return }
            down = true
            held.insert(bit)
            send(held)
          }
          .onEnded { _ in
            down = false
            held.remove(bit)
            send(held)
          }
      )
      .accessibilityElement()
      .accessibilityLabel("\(title.capitalized) mouse button")
      .accessibilityAddTraits(.isButton)
      .accessibilityAction {
        held.insert(bit)
        send(held)
        held.remove(bit)
        send(held)
      }
  }
}

private struct OfflineCard: View {
  @Environment(AppModel.self) private var model
  var device: SavedDevice
  var message: String
  var body: some View {
    VStack(spacing: 14) {
      Text("Can’t reach \(device.name)").font(.headline).foregroundStyle(Palette.ink)
      Text(message).font(.subheadline).foregroundStyle(Palette.ink2)
        .multilineTextAlignment(.center).fixedSize(horizontal: false, vertical: true)
      Button("Try again") { Task { await model.open(device, via: model.activeTransport) } }
        .buttonStyle(PrimaryButtonStyle()).frame(maxWidth: 240)
      let others = model.transports(for: device).filter { $0 != model.activeTransport }
      if !others.isEmpty {
        Menu("Use another connection") {
          ForEach(others, id: \.self) { k in
            Button(k.title) {
              model.setPreferred(k, for: device)
              Task { await model.open(model.device(device.id) ?? device, via: k) }
            }
          }
        }
        .font(.subheadline.weight(.medium))
      }
    }
    .padding(20)
    .frame(maxWidth: 360)
  }
}

/// Special keys above the iOS keyboard. Modifiers latch until the next key.
private struct KeyBar: View {
  @Binding var sticky: Modifiers
  var onKey: (HIDKey) -> Void
  var onHide: () -> Void

  private let mods: [(String, Modifiers)] = [
    ("Ctrl", .ctrl), ("Alt", .alt), ("Shift", .shift), ("Cmd", .gui),
  ]
  private let keys: [(String, HIDKey)] = [
    ("Esc", .esc), ("Tab", .tab), ("←", .left), ("↑", .up), ("↓", .down), ("→", .right),
    ("Home", .home), ("End", .end), ("PgUp", .pageup), ("PgDn", .pagedown), ("Del", .delete),
  ]

  var body: some View {
    HStack(spacing: 8) {
      ScrollView(.horizontal, showsIndicators: false) {
        HStack(spacing: 6) {
          ForEach(mods, id: \.0) { m in
            Button(m.0) { sticky.formSymmetricDifference(m.1) }
              .buttonStyle(KeycapStyle(on: sticky.contains(m.1)))
              .accessibilityAddTraits(sticky.contains(m.1) ? .isSelected : [])
          }
          Divider().frame(height: 28)
          ForEach(keys, id: \.0) { k in
            Button(k.0) { onKey(k.1) }.buttonStyle(KeycapStyle())
          }
        }
        .padding(.vertical, 2)
      }
      Button(action: onHide) {
        Image(systemName: "keyboard.chevron.compact.down")
      }
      .buttonStyle(KeycapStyle(minWidth: 44))
      .accessibilityLabel("Hide keyboard")
    }
  }
}

private struct KeysSheet: View {
  var onKey: (HIDKey) -> Void
  var onConsumer: (ConsumerKey) -> Void
  var onRelease: () -> Void
  @Environment(\.dismiss) private var dismiss

  private let media: [(String, ConsumerKey, String)] = [
    ("backward.end.fill", .prev, "Previous"), ("playpause.fill", .playPause, "Play or pause"),
    ("forward.end.fill", .next, "Next"), ("speaker.minus.fill", .volumeDown, "Volume down"),
    ("speaker.slash.fill", .mute, "Mute"), ("speaker.plus.fill", .volumeUp, "Volume up"),
    ("sun.min.fill", .brightnessDown, "Brightness down"),
    ("sun.max.fill", .brightnessUp, "Brightness up"),
  ]
  private let fkeys: [HIDKey] = [.f1, .f2, .f3, .f4, .f5, .f6, .f7, .f8, .f9, .f10, .f11, .f12]
  private let nav: [(String, HIDKey)] = [
    ("Esc", .esc), ("Tab", .tab), ("Enter", .enter), ("Space", .space), ("Insert", .insert),
    ("Del", .delete), ("Home", .home), ("End", .end), ("PgUp", .pageup), ("PgDn", .pagedown),
    ("PrtSc", .printscreen), ("Menu", .menu),
  ]
  private let grid = [GridItem(.adaptive(minimum: 64), spacing: 8)]

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 22) {
          group("Media") {
            LazyVGrid(columns: [GridItem(.adaptive(minimum: 64), spacing: 8)], spacing: 8) {
              ForEach(media, id: \.0) { m in
                Button { onConsumer(m.1) } label: { Image(systemName: m.0).font(.body) }
                  .buttonStyle(KeycapStyle()).accessibilityLabel(m.2)
              }
            }
          }
          group("Function keys") {
            LazyVGrid(columns: grid, spacing: 8) {
              ForEach(fkeys, id: \.self) { k in
                Button(k.displayName) { onKey(k) }.buttonStyle(KeycapStyle())
              }
            }
          }
          group("Navigation") {
            LazyVGrid(columns: grid, spacing: 8) {
              ForEach(nav, id: \.0) { k in Button(k.0) { onKey(k.1) }.buttonStyle(KeycapStyle()) }
            }
          }
          Button("Release all keys") {
            onRelease()
            dismiss()
          }
          .buttonStyle(SecondaryButtonStyle())
          Text("Use this if a key or button seems stuck on the computer.")
            .font(.footnote).foregroundStyle(Palette.ink3)
        }
        .padding(20)
      }
      .background(Palette.paper)
      .navigationTitle("Keys & media")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
  }

  private func group(_ title: String, @ViewBuilder _ content: () -> some View) -> some View {
    VStack(alignment: .leading, spacing: 10) {
      Text(title).font(.subheadline.weight(.semibold)).foregroundStyle(Palette.ink2)
      content()
    }
  }
}

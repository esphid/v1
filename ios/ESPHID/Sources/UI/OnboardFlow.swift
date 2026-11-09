import ESPHIDKit
import SwiftUI

/// First contact with a discovered device: set up (claim + WiFi + radio) or sign in.
struct OnboardFlow: View {
  let discovered: DiscoveredDevice
  var onDone: (SavedDevice) -> Void

  @Environment(AppModel.self) private var model
  @Environment(\.dismiss) private var dismiss

  enum Step: Equatable { case connecting, claim, signIn, wifi, radio, finishing, done, failed(String) }

  @State private var step: Step = .connecting
  @State private var rpc: RPCClient?
  @State private var info: DeviceInfo?
  @State private var token = TokenGenerator.make()
  @State private var name = ""
  @State private var signInKey = ""
  @State private var networks: [WiFiNetwork] = []
  @State private var scanning = false
  @State private var ssid = ""
  @State private var password = ""
  @State private var radio = "wifi"
  @State private var busy = false
  @State private var error: String?
  @State private var progressNote = ""
  @State private var saved: SavedDevice?
  @State private var joinedIP: String?
  @State private var copied = false

  private var viaBLE: Bool { if case .ble = discovered.source { true } else { false } }
  private var kind: TransportKind { viaBLE ? .ble : .http }

  var body: some View {
    NavigationStack {
      ScrollView {
        VStack(alignment: .leading, spacing: 24) {
          if showsProgress { StepBar(index: stepIndex) }
          content
        }
        .padding(20)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
      }
      .background(Palette.paper)
      .scrollDismissesKeyboard(.interactively)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) {
          Button("Cancel") {
            rpc?.close()
            dismiss()
          }
          .disabled(step == .finishing)
        }
      }
      .interactiveDismissDisabled(step == .finishing)
    }
    .task { await connect() }
  }

  // MARK: steps

  @ViewBuilder private var content: some View {
    switch step {
    case .connecting:
      Header(
        title: "Connecting to \(discovered.name)",
        message: viaBLE
          ? "If iOS asks to pair, tap Pair. The connection is encrypted."
          : "Reaching the device on this network.")
      ProgressView().frame(maxWidth: .infinity).padding(.top, 24)

    case .claim:
      Header(
        title: "Set up \(info?.name ?? discovered.name)",
        message:
          "Choose an access key. The app and the device page use it to control this device, and it can’t be shown again later. This app keeps a copy in your keychain."
      )
      VStack(alignment: .leading, spacing: 8) {
        Text("Name").font(.subheadline.weight(.medium)).foregroundStyle(Palette.ink2)
        TextField("Device name", text: $name)
          .textFieldStyle(BoxedFieldStyle())
          .textInputAutocapitalization(.words)
      }
      VStack(alignment: .leading, spacing: 8) {
        Text("Access key").font(.subheadline.weight(.medium)).foregroundStyle(Palette.ink2)
        HStack(spacing: 8) {
          KeyField(text: $token).textFieldStyle(BoxedFieldStyle())
          Button {
            token = TokenGenerator.make()
          } label: {
            Image(systemName: "arrow.clockwise").frame(width: 50, height: 50)
          }
          .buttonStyle(.plain)
          .background(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.lineStrong))
          .accessibilityLabel("Generate a new key")
        }
        Button(copied ? "Copied" : "Copy key") {
          UIPasteboard.general.string = token
          copied = true
        }
        .font(.subheadline.weight(.medium))
      }
      ErrorText(error)
      Button("Save access key") { Task { await claim() } }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(busy || !validToken(token))
        .overlay { if busy { ProgressView().tint(Palette.onAccent) } }

    case .signIn:
      Header(
        title: "Add \(info?.name ?? discovered.name)",
        message:
          "This device is already set up. Enter its access key. You can find it in your password manager, or reset the key from the device page.")
      KeyField(text: $signInKey, placeholder: "Access key").textFieldStyle(BoxedFieldStyle())
        .submitLabel(.go).onSubmit { Task { await signIn() } }
      ErrorText(error)
      Button("Add device") { Task { await signIn() } }
        .buttonStyle(PrimaryButtonStyle())
        .disabled(busy || signInKey.count < 16)
        .overlay { if busy { ProgressView().tint(Palette.onAccent) } }

    case .wifi:
      Header(
        title: "Join WiFi",
        message:
          "WiFi lets you connect over your network, from anywhere with Tailscale, or through MQTT. The device needs a 2.4 GHz network.")
      VStack(spacing: 0) {
        HStack {
          Text("Networks").font(.subheadline.weight(.medium)).foregroundStyle(Palette.ink2)
          Spacer()
          if scanning {
            ProgressView().controlSize(.small)
          } else {
            Button("Scan again") { Task { await scan() } }.font(.subheadline)
          }
        }
        .padding(.bottom, 8)
        if networks.isEmpty && !scanning {
          Text("No networks found yet.").font(.subheadline).foregroundStyle(Palette.ink3)
            .frame(maxWidth: .infinity, alignment: .leading).padding(.vertical, 8)
        }
        VStack(spacing: 0) {
          ForEach(networks.prefix(12)) { n in
            Button {
              ssid = n.ssid ?? ""
            } label: {
              HStack(spacing: 12) {
                Text(n.ssid ?? "").foregroundStyle(Palette.ink).lineLimit(1)
                Spacer()
                if (n.auth ?? "open") != "open" {
                  Image(systemName: "lock.fill").font(.caption).foregroundStyle(Palette.ink3)
                }
                SignalBars(rssi: n.rssi ?? -90)
              }
              .padding(.horizontal, 14).frame(minHeight: 48)
              .background(ssid == n.ssid ? Palette.accentWash : Palette.surface)
            }
            .buttonStyle(.plain)
            Divider().overlay(Palette.line)
          }
        }
        .clipShape(RoundedRectangle(cornerRadius: 12, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Palette.line))
      }
      TextField("Network name", text: $ssid).textFieldStyle(BoxedFieldStyle())
        .textInputAutocapitalization(.never).autocorrectionDisabled()
      SecureField("Password", text: $password).textFieldStyle(BoxedFieldStyle())
      ErrorText(error)
      Button("Continue") {
        error = nil
        radio = "wifi"
        step = .radio
      }
      .buttonStyle(PrimaryButtonStyle())
      .disabled(ssid.isEmpty)
      if viaBLE {
        Button("Skip — use Bluetooth only") {
          ssid = ""
          password = ""
          radio = "ble"
          step = .radio
        }
        .buttonStyle(SecondaryButtonStyle())
      }

    case .radio:
      Header(
        title: "How will you connect?",
        message:
          "The device has one 2.4 GHz radio. Running WiFi and Bluetooth together adds latency to both, so pick the one you’ll use most. You can change it later.")
      VStack(spacing: 10) {
        RadioCard(
          id: "ble", selection: $radio, title: "Bluetooth only",
          detail: "Fastest from a phone nearby. WiFi, Tailscale and MQTT turn off.")
        RadioCard(
          id: "wifi", selection: $radio, title: "WiFi only",
          detail: "Fastest over your network, Tailscale and MQTT. Bluetooth turns off.",
          disabled: ssid.isEmpty && !(info?.wifi?.connected ?? false))
        RadioCard(
          id: "both", selection: $radio, title: "Both",
          detail: "Most flexible. Each one gets a little slower.")
      }
      ErrorText(error)
      Button("Finish setup") { Task { await finish() } }
        .buttonStyle(PrimaryButtonStyle())

    case .finishing:
      Header(title: "Finishing", message: progressNote)
      ProgressView().frame(maxWidth: .infinity).padding(.top, 12)

    case .done:
      Header(
        title: "Ready",
        message:
          "Plug \(saved?.name ?? "the device") into the computer you want to control. It shows up there as a keyboard and mouse."
      )
      if let saved {
        VStack(spacing: 0) {
          DoneRow(label: "Device ID", value: saved.id)
          if let ip = joinedIP { DoneRow(label: "Address", value: ip) }
          DoneRow(label: "Connects over", value: saved.preferredTransport.title)
        }
        .background(RoundedRectangle(cornerRadius: 12).fill(Palette.surface))
        .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.line))
      }
      if !viaBLE, !ssid.isEmpty {
        Text(
          "The setup hotspot turns off in about a minute. Reconnect this phone to \(ssid) to keep using WiFi."
        )
        .font(.subheadline).foregroundStyle(Palette.ink2)
      }
      Button("Open trackpad") { if let saved { onDone(saved) } }
        .buttonStyle(PrimaryButtonStyle())

    case .failed(let message):
      Header(title: "Couldn’t connect", message: message)
      Button("Try again") { Task { await connect() } }.buttonStyle(PrimaryButtonStyle())
    }
  }

  private var showsProgress: Bool {
    switch step {
    case .claim, .wifi, .radio, .finishing, .done: info?.claimed == false || saved != nil
    default: false
    }
  }
  private var stepIndex: Int {
    switch step {
    case .claim: 0
    case .wifi: 1
    case .radio, .finishing: 2
    default: 3
    }
  }

  // MARK: actions

  private func connect() async {
    step = .connecting
    error = nil
    do {
      rpc?.close()
      let r = try await DeviceSession.openRPC(
        to: discovered.savedDevice(), via: kind, token: nil)
      rpc = r
      let i = try await r.info()
      info = i
      name = i.name ?? discovered.name
      step = i.claimed == false ? .claim : .signIn
    } catch {
      step = .failed(describe(error))
    }
  }

  private func authorize(_ key: String) async throws -> RPCClient {
    if viaBLE, let rpc {
      try await rpc.auth(token: key)
      return rpc
    }
    rpc?.close()
    let r = try await DeviceSession.openRPC(to: discovered.savedDevice(), via: .http, token: key)
    _ = try await r.getConfig()  // validates the key
    rpc = r
    return r
  }

  private func claim() async {
    guard let rpc else { return }
    busy = true
    error = nil
    defer { busy = false }
    do {
      try await rpc.claim(token: token)
      let authed = try await authorize(token)
      info = try await authed.info()
      if let id = info?.id { model.store.setToken(token, for: id) }
      if info?.wifi?.connected == true {
        radio = "wifi"
        step = .radio
      } else {
        step = .wifi
        Task { await scan() }
      }
    } catch {
      self.error = describe(error)
    }
  }

  private func signIn() async {
    let key = signInKey.trimmingCharacters(in: .whitespacesAndNewlines)
    busy = true
    error = nil
    defer { busy = false }
    do {
      let r = try await authorize(key)
      let i = try await r.info()
      guard let id = i.id else { throw RPCError.decoding("Missing device ID") }
      let d = makeSaved(info: i, ip: i.wifi?.ip, radio: i.radio_mode ?? "both")
      model.store.setToken(key, for: id)
      model.store.upsert(d)
      r.close()
      saved = d
      onDone(d)
    } catch {
      self.error = describe(error)
    }
  }

  private func scan() async {
    guard let rpc else { return }
    scanning = true
    defer { scanning = false }
    if let list = try? await rpc.scanWiFi() {
      var seen = Set<String>()
      networks = list.filter { n in
        guard let s = n.ssid, !s.isEmpty, !seen.contains(s) else { return false }
        seen.insert(s)
        return true
      }
    }
  }

  private func finish() async {
    guard let rpc else { return }
    error = nil
    step = .finishing
    do {
      if !ssid.isEmpty {
        progressNote = "Joining \(ssid). This takes up to 30 seconds."
        let patch: JSONValue = ["wifi": ["ssid": .string(ssid), "password": .string(password)]]
        _ = try await rpc.setConfig(patch)
        joinedIP = try await waitForWiFi(rpc)
        if joinedIP == nil {
          error = "Couldn’t join \(ssid). Check the password and that it’s a 2.4 GHz network."
          step = .wifi
          return
        }
      } else {
        joinedIP = info?.wifi?.ip
      }
      progressNote = "Saving settings."
      var patch: [String: JSONValue] = ["radio_mode": .string(radio)]
      let trimmed = name.trimmingCharacters(in: .whitespaces)
      if !trimmed.isEmpty, trimmed != info?.name { patch["name"] = .string(trimmed) }
      let result = try await rpc.setConfig(.object(patch))
      let fresh = (try? await rpc.info()) ?? info ?? DeviceInfo()
      let d = makeSaved(info: fresh, ip: joinedIP, radio: radio)
      if let id = fresh.id { model.store.setToken(token, for: id) }
      model.store.upsert(d)
      if result.rebootRequired {
        progressNote = "Restarting the device."
        try? await rpc.reboot(delayMs: 300)
      }
      rpc.close()
      saved = d
      step = .done
    } catch {
      self.error = describe(error)
      step = .radio
    }
  }

  private func waitForWiFi(_ rpc: RPCClient) async throws -> String? {
    let deadline = Date().addingTimeInterval(40)
    while Date() < deadline {
      try await Task.sleep(for: .seconds(1.5))
      guard let i = try? await rpc.info() else { continue }
      if i.wifi?.connected == true, let ip = i.wifi?.ip, !ip.isEmpty { return ip }
    }
    return nil
  }

  private func makeSaved(info i: DeviceInfo, ip: String?, radio: String) -> SavedDevice {
    var peripheral: UUID?
    var host = ip
    var port = 80
    switch discovered.source {
    case .ble(let p, _): peripheral = p
    case .lan(let h, let p):
      if host == nil {
        host = h
        port = p
      }
    }
    if radio == "ble" { host = nil }
    if radio == "wifi" { peripheral = nil }
    let preferred: TransportKind = (radio == "ble" || host == nil) ? .ble : .websocket
    return SavedDevice(
      id: i.id ?? discovered.deviceId ?? discovered.id,
      name: i.name ?? (name.isEmpty ? discovered.name : name),
      host: host, port: port, bleIdentifier: peripheral, preferredTransport: preferred,
      fwVariant: i.fw_variant, otaRepo: i.ota?.repo)
  }

  private func validToken(_ t: String) -> Bool {
    (16...64).contains(t.count) && t.unicodeScalars.allSatisfy { (0x21...0x7E).contains($0.value) }
  }
}

// MARK: - pieces

private struct Header: View {
  var title: String
  var message: String
  var body: some View {
    VStack(alignment: .leading, spacing: 8) {
      Text(title).font(.title.weight(.semibold)).foregroundStyle(Palette.ink)
      if !message.isEmpty {
        Text(message).font(.body).foregroundStyle(Palette.ink2)
          .fixedSize(horizontal: false, vertical: true)
      }
    }
  }
}

private struct StepBar: View {
  var index: Int
  private let labels = ["Access key", "WiFi", "Connection", "Ready"]
  var body: some View {
    HStack(spacing: 8) {
      ForEach(labels.indices, id: \.self) { i in
        VStack(alignment: .leading, spacing: 6) {
          Capsule().fill(i <= index ? Palette.accentStrong : Palette.line).frame(height: 3)
          Text(labels[i]).font(.caption2.weight(.medium))
            .foregroundStyle(i <= index ? Palette.ink2 : Palette.ink3)
        }
      }
    }
    .accessibilityElement(children: .ignore)
    .accessibilityLabel("Step \(index + 1) of 4, \(labels[index])")
  }
}

private struct RadioCard: View {
  var id: String
  @Binding var selection: String
  var title: String
  var detail: String
  var disabled = false
  var body: some View {
    Button {
      selection = id
    } label: {
      HStack(alignment: .top, spacing: 12) {
        Image(systemName: selection == id ? "largecircle.fill.circle" : "circle")
          .foregroundStyle(selection == id ? Palette.accentStrong : Palette.ink3)
          .font(.title3)
        VStack(alignment: .leading, spacing: 3) {
          Text(title).font(.body.weight(.semibold)).foregroundStyle(Palette.ink)
          Text(detail).font(.subheadline).foregroundStyle(Palette.ink2)
            .fixedSize(horizontal: false, vertical: true)
        }
        Spacer(minLength: 0)
      }
      .padding(14)
      .background(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
          .fill(selection == id ? Palette.accentWash : Palette.surface)
      )
      .overlay(
        RoundedRectangle(cornerRadius: 12, style: .continuous)
          .strokeBorder(selection == id ? Palette.accentStrong : Palette.lineStrong)
      )
    }
    .buttonStyle(.plain)
    .disabled(disabled)
    .opacity(disabled ? 0.5 : 1)
    .accessibilityAddTraits(selection == id ? .isSelected : [])
  }
}

private struct DoneRow: View {
  var label: String
  var value: String
  var body: some View {
    HStack {
      Text(label).foregroundStyle(Palette.ink2)
      Spacer()
      Text(value).font(.readout).foregroundStyle(Palette.ink).textSelection(.enabled)
    }
    .padding(.horizontal, 14).frame(minHeight: 46)
  }
}

struct ErrorText: View {
  var text: String?
  init(_ text: String?) { self.text = text }
  var body: some View {
    if let text {
      Label(text, systemImage: "exclamationmark.circle")
        .font(.subheadline).foregroundStyle(Palette.bad)
        .fixedSize(horizontal: false, vertical: true)
    }
  }
}

struct BoxedFieldStyle: TextFieldStyle {
  func _body(configuration: TextField<Self._Label>) -> some View {
    configuration
      .padding(.horizontal, 14)
      .frame(minHeight: 50)
      .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(Palette.surface))
      .overlay(
        RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(Palette.lineStrong))
  }
}

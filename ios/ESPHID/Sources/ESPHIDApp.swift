import ESPHIDKit
import SwiftUI

@main
struct ESPHIDApp: App {
  @State private var model = AppModel()
  @Environment(\.scenePhase) private var scenePhase

  var body: some Scene {
    WindowGroup {
      RootView()
        .environment(model)
        .tint(Palette.tint)
    }
    .onChange(of: scenePhase) { _, phase in model.scenePhaseChanged(phase) }
  }
}

/// App-wide state: saved devices, discovery, and the single live HID connection.
@MainActor @Observable final class AppModel {
  let store = DeviceStore()
  let discovery = DeviceDiscovery()
  let connection = HIDConnection()
  private(set) var activeDevice: SavedDevice?
  private(set) var activeTransport: TransportKind?
  var connectError: String?
  private var resumeOnActive = false

  func device(_ id: String) -> SavedDevice? { store.devices.first { $0.id == id } }

  /// Transports this saved device can use, fastest first.
  func transports(for d: SavedDevice) -> [TransportKind] {
    var out: [TransportKind] = []
    if d.bleIdentifier != nil { out.append(.ble) }
    if d.host != nil { out += [.websocket, .http] }
    if d.mqtt != nil { out.append(.mqtt) }
    return out
  }

  func preferredTransport(for d: SavedDevice) -> TransportKind? {
    let all = transports(for: d)
    return all.contains(d.preferredTransport) ? d.preferredTransport : all.first
  }

  func open(_ d: SavedDevice, via kind: TransportKind? = nil) async {
    connectError = nil
    activeDevice = d
    guard let k = kind ?? preferredTransport(for: d) else {
      connectError = "This device has no saved way to connect. Add an address or Bluetooth in Settings."
      return
    }
    activeTransport = k
    guard let token = store.token(for: d.id) else {
      connectError = "No access key is saved for this device."
      return
    }
    do {
      try await connection.connect(
        to: d, via: k, token: token, mqttPassword: store.mqttPassword(for: d.id))
    } catch is CancellationError {
    } catch {
      if activeDevice?.id == d.id { connectError = describe(error) }
    }
  }

  func close() {
    if connection.state == .connected { connection.releaseAll() }
    connection.disconnect()
    activeDevice = nil
    activeTransport = nil
    connectError = nil
  }

  func setPreferred(_ k: TransportKind, for d: SavedDevice) {
    var x = d
    x.preferredTransport = k
    store.upsert(x)
    if activeDevice?.id == d.id { activeDevice = x }
  }

  func scenePhaseChanged(_ phase: ScenePhase) {
    switch phase {
    case .background:
      guard activeDevice != nil, connection.state != .idle else { return }
      if connection.state == .connected { connection.releaseAll() }
      connection.disconnect()
      resumeOnActive = true
    case .active:
      guard resumeOnActive, let d = activeDevice.flatMap({ device($0.id) }) else { return }
      resumeOnActive = false
      let k = activeTransport
      Task { await open(d, via: k) }
    default: break
    }
  }

  /// Keep LAN addresses fresh: DHCP may hand the device a new IP.
  func syncDiscovery() {
    for f in discovery.found {
      guard case .lan(let host, let port) = f.source, let id = f.deviceId,
        var d = device(id), d.host != host || d.port != port
      else { continue }
      // Don't replace a Tailscale/manual address with a LAN one.
      if let h = d.host, !(h.hasSuffix(".local") || h.hasSuffix(".local.") || isPrivateIPv4(h)) {
        continue
      }
      d.host = host
      d.port = port
      store.upsert(d)
    }
  }
}

func isPrivateIPv4(_ s: String) -> Bool {
  let p = s.split(separator: ".").compactMap { Int($0) }
  guard p.count == 4 else { return false }
  return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && (16...31).contains(p[1]))
}

func describe(_ error: Error) -> String {
  if let e = error as? RPCError {
    switch e {
    case .unauthorized: return "The access key was not accepted."
    case .remote(let code, let message):
      if code == 401 { return "The access key was not accepted." }
      if code == 429 { return "Too many attempts. Wait 30 seconds and try again." }
      if code == 503 { return "The device is busy (an update may be running)." }
      return message.isEmpty ? "The device returned error \(code)." : message
    case .timeout: return "The device didn’t answer in time."
    case .transport(let m): return m
    case .decoding(let m): return "The device sent an unexpected reply. \(m)"
    }
  }
  return error.localizedDescription
}

extension TransportKind {
  var title: String {
    switch self {
    case .ble: "Bluetooth"
    case .websocket: "WiFi"
    case .http: "HTTP"
    case .mqtt: "MQTT"
    }
  }
  var detail: String {
    switch self {
    case .ble: "Direct, lowest latency nearby"
    case .websocket: "LAN or Tailscale, live socket"
    case .http: "One request per input. Slowest"
    case .mqtt: "Through your broker"
    }
  }
  var symbol: String {
    switch self {
    case .ble: "dot.radiowaves.left.and.right"
    case .websocket: "wifi"
    case .http: "network"
    case .mqtt: "point.3.connected.trianglepath.dotted"
    }
  }
}

struct RootView: View {
  @Environment(AppModel.self) private var model
  @State private var path = NavigationPath()

  var body: some View {
    NavigationStack(path: $path) {
      DevicesView(path: $path)
        .navigationDestination(for: String.self) { id in ControlView(deviceID: id) }
    }
  }
}

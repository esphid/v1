import Foundation
import Observation

func makeTransport(_ d: SavedDevice, _ kind: TransportKind, _ token: String?, _ password: String?)
  -> Transport
{
  switch kind {
  case .ble: return BLETransport(device: d, token: token)
  case .http: return HTTPTransport(device: d, token: token)
  case .mqtt, .websocket:
    return SocketTransport(device: d, token: token, kind: kind, password: password)
  }
}
public enum DeviceSession {
  public static func openRPC(
    to device: SavedDevice, via kind: TransportKind, token: String?, mqttPassword: String? = nil
  ) async throws -> RPCClient {
    let t = makeTransport(device, kind, token, mqttPassword)
    try await t.open()
    let rpc = RPCClient(channel: t)
    if kind == .ble, let token { try await rpc.auth(token: token) }
    return rpc
  }
}
@MainActor @Observable public final class HIDConnection {
  public enum State: Equatable, Sendable {
    case idle, connecting, authenticating, connected
    case failed(String)
  }
  public private(set) var state: State = .idle
  public private(set) var transport: TransportKind?
  public private(set) var rttMs: Double?
  public private(set) var leds: KeyboardLEDs = []
  public private(set) var usbMounted: Bool?
  public private(set) var rpc: RPCClient?
  private var driver: Transport?
  private var pingTask: Task<Void, Never>?
  private var pings = [UInt32: TimeInterval]()
  private var authWait: CheckedContinuation<Void, Error>?
  private var generation = UUID()
  public init() {}
  public func connect(
    to device: SavedDevice, via kind: TransportKind, token: String, mqttPassword: String? = nil
  ) async throws {
    disconnect()
    let gen = generation
    state = .connecting
    transport = kind
    let t = makeTransport(device, kind, token, mqttPassword)
    driver = t
    t.events = { [weak self] events in
      Task { @MainActor in
        guard let self, self.generation == gen else { return }
        self.receive(events)
      }
    }
    t.failure = { [weak self] error in
      Task { @MainActor in
        guard let self, self.generation == gen else { return }
        self.state = .failed(error)
        self.authWait?.resume(throwing: RPCError.transport(error))
        self.authWait = nil
        self.pingTask?.cancel()
      }
    }
    do {
      try await t.open()
      guard generation == gen else { throw CancellationError() }
      rpc = RPCClient(channel: t)
      state = .authenticating
      if kind == .ble || kind == .websocket {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<Void, Error>) in
          authWait = c
          t.send(.auth(token))
          Task { @MainActor [weak self] in
            try? await Task.sleep(for: .seconds(10))
            guard let self, self.generation == gen, self.authWait != nil else { return }
            self.authWait?.resume(throwing: RPCError.timeout)
            self.authWait = nil
          }
        }
      } else {
        _ = try await rpc?.getConfig()
      }
      guard generation == gen else { throw CancellationError() }
      state = .connected
      t.send(.getState)
      pingTask = Task { @MainActor [weak self] in
        while !Task.isCancelled {
          guard self != nil else { return }
          self?.ping()
          do { try await Task.sleep(for: .seconds(2)) } catch { return }
        }
      }
    } catch {
      if generation == gen {
        state = .failed(error.localizedDescription)
        t.close()
      }
      throw error
    }
  }
  private func ping() {
    let n = UInt32.random(in: 0...UInt32.max)
    pings = pings.filter { Date.timeIntervalSinceReferenceDate - $0.value < 10 }
    pings[n] = Date.timeIntervalSinceReferenceDate
    driver?.send(.ping(n))
  }
  private func receive(_ events: [HIDEvent]) {
    for event in events {
      switch event {
      case .auth(let result):
        if result == 0 {
          authWait?.resume()
        } else {
          authWait?.resume(throwing: RPCError.unauthorized)
        }
        authWait = nil
      case .pong(let n, _):
        if let start = pings.removeValue(forKey: n) {
          let ms = (Date.timeIntervalSinceReferenceDate - start) * 1000
          rttMs = rttMs.map { $0 * 0.8 + ms * 0.2 } ?? ms
        }
      case .leds(let v): leds = v
      case .state(let flags, let l, _):
        usbMounted = flags & 1 != 0
        leds = l
      default: break
      }
    }
  }
  public func disconnect() {
    generation = UUID()
    pingTask?.cancel()
    pingTask = nil
    authWait?.resume(throwing: CancellationError())
    authWait = nil
    driver?.close()
    driver = nil
    rpc = nil
    transport = nil
    state = .idle
    pings = [:]
    rttMs = nil
    leds = []
    usbMounted = nil
  }
  private func send(_ command: HIDCommand) { if state == .connected { driver?.send(command) } }
  public func moveMouse(dx: Double, dy: Double) {
    if state == .connected { driver?.motion(dx, dy) }
  }
  public func scroll(wheel: Double, pan: Double) {
    if state == .connected { driver?.motion(wheel, pan, scroll: true) }
  }
  public func setButtons(_ b: MouseButtons) { send(.mouseButtons(b)) }
  public func click(_ b: MouseButtons = .left) { send(.mouseClick(b)) }
  public func keyDown(_ k: HIDKey) { send(.keyDown(k)) }
  public func keyUp(_ k: HIDKey) { send(.keyUp(k)) }
  public func tap(_ k: HIDKey, modifiers: Modifiers = []) {
    send(.keyTap(k, modifiers: modifiers))
  }
  public func setModifiers(_ m: Modifiers) { send(.modifiers(m)) }
  public func type(_ text: String) { send(.text(text)) }
  public func consumerTap(_ k: ConsumerKey) { send(.consumerTap(k)) }
  public func releaseAll() { send(.releaseAll) }
}
public struct FirmwareRelease: Sendable {
  public let version: String
  public let tag: String
  public let notes: String
  public let publishedAt: Date?
  public let assetURL: URL
}
public enum FirmwareUpdates {
  public static func latest(repo: String, variant: String) async throws -> FirmwareRelease? {
    guard repo.range(of: #"^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$"#, options: .regularExpression) != nil
    else { throw RPCError.transport("Invalid GitHub repository") }
    let url = URL(string: "https://api.github.com/repos/\(repo)/releases/latest")!
    var req = URLRequest(url: url)
    req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
    let (data, response) = try await URLSession.shared.data(for: req)
    if (response as? HTTPURLResponse)?.statusCode == 404 { return nil }
    guard (response as? HTTPURLResponse)?.statusCode == 200 else {
      throw RPCError.transport("GitHub release request failed")
    }
    let v = try JSONDecoder().decode(JSONValue.self, from: data)
    guard let tag = v["tag_name"]?.string,
      let asset = v["assets"]?.array?.first(where: { $0["name"]?.string == "esphid-\(variant).bin" }
      ), let s = asset["browser_download_url"]?.string, let assetURL = URL(string: s)
    else { return nil }
    return .init(
      version: tag.hasPrefix("v") ? String(tag.dropFirst()) : tag, tag: tag,
      notes: v["body"]?.string ?? "",
      publishedAt: v["published_at"]?.string.flatMap { ISO8601DateFormatter().date(from: $0) },
      assetURL: assetURL)
  }
  public static func isNewer(_ a: String, than b: String) -> Bool {
    func parts(_ v: String) -> ([Int], [String]) {
      let s = (v.hasPrefix("v") ? String(v.dropFirst()) : v).split(
        separator: "+", maxSplits: 1, omittingEmptySubsequences: false)[0].split(
          separator: "-", maxSplits: 1, omittingEmptySubsequences: false)
      return (
        s[0].split(separator: ".").map { Int($0) ?? 0 },
        s.count > 1 ? s[1].split(separator: ".").map(String.init) : []
      )
    }
    let (x, p) = parts(a)
    let (y, q) = parts(b)
    for i in 0..<max(x.count, y.count) {
      let u = i < x.count ? x[i] : 0
      let v = i < y.count ? y[i] : 0
      if u != v { return u > v }
    }
    if p.isEmpty || q.isEmpty { return p.isEmpty && !q.isEmpty }
    for i in 0..<min(p.count, q.count) {
      if p[i] == q[i] { continue }
      if let u = Int(p[i]), let v = Int(q[i]) { return u > v }
      if Int(p[i]) != nil { return false }
      if Int(q[i]) != nil { return true }
      return p[i] > q[i]
    }
    return p.count > q.count
  }
}

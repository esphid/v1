import Foundation

public enum RPCError: Error, LocalizedError, Sendable {
  case remote(code: Int, message: String)
  case timeout
  case transport(String)
  case decoding(String)
  case unauthorized
  public var errorDescription: String? {
    switch self {
    case .remote(let c, let m): return "RPC \(c): \(m)"
    case .timeout: return "Request timed out"
    case .transport(let m), .decoding(let m): return m
    case .unauthorized: return "Unauthorized"
    }
  }
}
public protocol RPCChannel: Sendable {
  func call(_ method: String, params: JSONValue?) async throws -> JSONValue
}
struct RPCWire {
  static func request(
    id: Int, method: String, params: JSONValue?, token: String?, reply: String? = nil
  ) throws -> Data {
    var obj: [String: JSONValue] = [
      "id": .number(Double(id)), "method": .string(method), "params": params ?? .object([:]),
    ]
    if let token { obj["token"] = .string(token) }
    if let reply { obj["reply_to"] = .string(reply) }
    return try JSONEncoder().encode(JSONValue.object(obj))
  }
  static func response(_ data: Data) throws -> (Int, JSONValue) {
    let v = try JSONDecoder().decode(JSONValue.self, from: data)
    guard case .number(let number)? = v["id"], let id = Int(exactly: number) else {
      throw RPCError.decoding("Missing response id")
    }
    if let e = v["error"] {
      if case .number(let number)? = e["code"], let c = Int(exactly: number) {
        if c == 401 { throw RPCError.unauthorized }
        throw RPCError.remote(code: c, message: e["message"]?.string ?? "RPC error")
      }
      throw RPCError.decoding("Malformed RPC error")
    }
    guard let result = v["result"] else { throw RPCError.decoding("Missing result") }
    return (id, result)
  }
}
public final class RPCClient: Sendable {
  private let channel: any RPCChannel
  public init(channel: any RPCChannel) { self.channel = channel }
  public func close() { (channel as? Transport)?.close() }
  deinit { (channel as? Transport)?.close() }
  public func info() async throws -> DeviceInfo {
    try await channel.call("info", params: nil).decoded(DeviceInfo.self)
  }
  public func auth(token: String) async throws {
    _ = try await channel.call("auth", params: ["token": .string(token)])
  }
  public func claim(token: String) async throws {
    _ = try await channel.call("claim", params: ["token": .string(token)])
  }
  public func setToken(_ token: String) async throws {
    _ = try await channel.call("token.set", params: ["token": .string(token)])
  }
  public func getConfig() async throws -> DeviceConfig {
    try await channel.call("config.get", params: nil).decoded(DeviceConfig.self)
  }
  public func setConfig(_ patch: JSONValue) async throws -> (
    config: DeviceConfig, rebootRequired: Bool
  ) {
    let r = try await channel.call("config.set", params: patch)
    guard let c = r["config"] else { throw RPCError.decoding("Missing config") }
    return (try c.decoded(DeviceConfig.self), r["reboot_required"]?.bool ?? false)
  }
  public func scanWiFi() async throws -> [WiFiNetwork] {
    let r = try await channel.call("wifi.scan", params: nil)
    return try (r["networks"] ?? []).decoded([WiFiNetwork].self)
  }
  public func startOTA(url: String, sha256: String? = nil) async throws {
    var p: [String: JSONValue] = ["url": .string(url)]
    if let sha256 { p["sha256"] = .string(sha256) }
    _ = try await channel.call("ota.start", params: .object(p))
  }
  public func otaStatus() async throws -> OTAStatus {
    try await channel.call("ota.status", params: nil).decoded(OTAStatus.self)
  }
  public func reboot(delayMs: Int = 0) async throws {
    _ = try await channel.call("system.reboot", params: ["delay_ms": .number(Double(delayMs))])
  }
  public func factoryReset() async throws {
    _ = try await channel.call("system.factory_reset", params: nil)
  }
  public func regenerateHTTPSCertificate() async throws -> String {
    let r = try await channel.call("https.regenerate", params: nil)
    guard let f = r["fingerprint"]?.string else { throw RPCError.decoding("Missing fingerprint") }
    return f
  }
  public func sendHID(json: JSONValue) async throws {
    _ = try await channel.call("hid", params: json)
  }
}

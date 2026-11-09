import Foundation
import Observation
import Security

public enum JSONValue: Codable, Sendable, Equatable {
  case null
  case bool(Bool)
  case number(Double)
  case string(String)
  case array([JSONValue])
  case object([String: JSONValue])
  public init(from decoder: Decoder) throws {
    let c = try decoder.singleValueContainer()
    if c.decodeNil() {
      self = .null
    } else if let v = try? c.decode(Bool.self) {
      self = .bool(v)
    } else if let v = try? c.decode(Double.self) {
      self = .number(v)
    } else if let v = try? c.decode(String.self) {
      self = .string(v)
    } else if let v = try? c.decode([JSONValue].self) {
      self = .array(v)
    } else {
      self = .object(try c.decode([String: JSONValue].self))
    }
  }
  public func encode(to encoder: Encoder) throws {
    var c = encoder.singleValueContainer()
    switch self {
    case .null: try c.encodeNil()
    case .bool(let v): try c.encode(v)
    case .number(let v): try c.encode(v)
    case .string(let v): try c.encode(v)
    case .array(let v): try c.encode(v)
    case .object(let v): try c.encode(v)
    }
  }
  public subscript(_ key: String) -> JSONValue? {
    if case .object(let v) = self { return v[key] }
    return nil
  }
  public subscript(_ i: Int) -> JSONValue? {
    if case .array(let v) = self, v.indices.contains(i) { return v[i] }
    return nil
  }
  public var string: String? {
    if case .string(let v) = self { return v }
    return nil
  }
  public var bool: Bool? {
    if case .bool(let v) = self { return v }
    return nil
  }
  public var array: [JSONValue]? {
    if case .array(let v) = self { return v }
    return nil
  }
  public func decoded<T: Decodable>(_ type: T.Type) throws -> T {
    try JSONDecoder().decode(type, from: JSONEncoder().encode(self))
  }
}
extension JSONValue: ExpressibleByNilLiteral, ExpressibleByBooleanLiteral,
  ExpressibleByIntegerLiteral, ExpressibleByFloatLiteral, ExpressibleByStringLiteral,
  ExpressibleByArrayLiteral, ExpressibleByDictionaryLiteral
{
  public init(nilLiteral: ()) { self = .null }
  public init(booleanLiteral v: Bool) { self = .bool(v) }
  public init(integerLiteral v: Int) { self = .number(Double(v)) }
  public init(floatLiteral v: Double) { self = .number(v) }
  public init(stringLiteral v: String) { self = .string(v) }
  public init(arrayLiteral elements: JSONValue...) { self = .array(elements) }
  public init(dictionaryLiteral elements: (String, JSONValue)...) {
    self = .object(Dictionary(elements, uniquingKeysWith: { _, b in b }))
  }
}
public struct DeviceInfo: Codable, Sendable, Equatable {
  public var id: String?
  public var name: String?
  public var model: String?
  public var fw_version: String?
  public var fw_variant: String?
  public var idf_version: String?
  public var proto: Int?
  public var claimed: Bool?
  public var uptime_s: Int?
  public var free_heap: Int?
  public var radio_mode: String?
  public var usb: USBInfo?
  public var wifi: WiFiInfo?
  public var ble: BLEInfo?
  public var https: HTTPSInfo?
  public var mqtt: MQTTInfo?
  public var tailscale: TailscaleInfo?
  public var ota: OTAInfo?
  public init() {}
}
public struct USBInfo: Codable, Sendable, Equatable {
  public var mounted: Bool?
  public var suspended: Bool?
  public var leds: UInt8?
  public init() {}
}
public struct WiFiInfo: Codable, Sendable, Equatable {
  public var mode: String?
  public var connected: Bool?
  public var ssid: String?
  public var ip: String?
  public var rssi: Int?
  public var mac: String?
  public var ap_active: Bool?
  public var ap_ip: String?
  public init() {}
}
public struct BLEInfo: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var connections: Int?
  public init() {}
}
public struct HTTPSInfo: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var port: Int?
  public var fingerprint: String?
  public init() {}
}
public struct MQTTInfo: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var connected: Bool?
  public init() {}
}
public struct TailscaleInfo: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var state: String?
  public var ip: String?
  public var hostname: String?
  public init() {}
}
public struct OTAInfo: Codable, Sendable, Equatable {
  public var state: String?
  public var progress: Double?
  public var repo: String?
  public var latest_url: String?
  public init() {}
}
public struct DeviceConfig: Codable, Sendable, Equatable {
  public var name: String?
  public var radio_mode: String?
  public var wifi: WiFiConfig?
  public var https: HTTPSInfo?
  public var mqtt: MQTTConfig?
  public var tailscale: TailscaleConfig?
  public var keyboard: KeyboardConfig?
  public init() {}
}
public struct WiFiConfig: Codable, Sendable, Equatable {
  public var ssid: String?
  public var password_set: Bool?
  public var hostname: String?
  public var ap_fallback: Bool?
  public init() {}
}
public struct MQTTConfig: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var uri: String?
  public var username: String?
  public var password_set: Bool?
  public var client_id: String?
  public var topic_prefix: String?
  public var require_token: Bool?
  public init() {}
}
public struct TailscaleConfig: Codable, Sendable, Equatable {
  public var enabled: Bool?
  public var auth_key_set: Bool?
  public var control_url: String?
  public var hostname: String?
  public init() {}
}
public struct KeyboardConfig: Codable, Sendable, Equatable {
  public var layout: String?
  public var type_delay_ms: Int?
  public init() {}
}
public struct OTAStatus: Codable, Sendable, Equatable {
  public var state: String?
  public var progress: Double?
  public var bytes: Int?
  public var total: Int?
  public var error: String?
  public var version: String?
  public init() {}
}
public struct WiFiNetwork: Codable, Sendable, Identifiable, Hashable {
  public var ssid: String?
  public var rssi: Int?
  public var auth: String?
  public var channel: Int?
  public var id: String { "\(ssid ?? "")/\(channel ?? 0)/\(auth ?? "")" }
}
public enum TransportKind: String, Codable, CaseIterable, Sendable {
  case ble, websocket, http, mqtt
}
public struct MQTTSettings: Codable, Hashable, Sendable {
  public var uri: String
  public var username: String?
  public var topicPrefix: String
  public init(uri: String, username: String? = nil, topicPrefix: String) {
    self.uri = uri
    self.username = username
    self.topicPrefix = topicPrefix
  }
}
public struct SavedDevice: Codable, Identifiable, Hashable, Sendable {
  public var id: String
  public var name: String
  public var host: String?
  public var port: Int
  public var httpsPort: Int?
  public var tlsFingerprint: String?
  public var bleIdentifier: UUID?
  public var mqtt: MQTTSettings?
  public var preferredTransport: TransportKind
  public var fwVariant: String?
  public var otaRepo: String?
  public init(
    id: String, name: String, host: String? = nil, port: Int = 80, httpsPort: Int? = nil,
    tlsFingerprint: String? = nil, bleIdentifier: UUID? = nil, mqtt: MQTTSettings? = nil,
    preferredTransport: TransportKind = .websocket, fwVariant: String? = nil, otaRepo: String? = nil
  ) {
    self.id = id
    self.name = name
    self.host = host
    self.port = port
    self.httpsPort = httpsPort
    self.tlsFingerprint = tlsFingerprint
    self.bleIdentifier = bleIdentifier
    self.mqtt = mqtt
    self.preferredTransport = preferredTransport
    self.fwVariant = fwVariant
    self.otaRepo = otaRepo
  }
  public static func bleSetupDevice(peripheral: UUID, name: String) -> Self {
    .init(
      id: peripheral.uuidString, name: name, bleIdentifier: peripheral, preferredTransport: .ble)
  }
}
public enum TokenGenerator {
  public static func make(length: Int = 32) -> String {
    let chars = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz".utf8)
    var out = [UInt8]()
    while out.count < max(0, length) {
      var b: UInt8 = 0
      guard SecRandomCopyBytes(kSecRandomDefault, 1, &b) == errSecSuccess else {
        preconditionFailure("Secure random source unavailable")
      }
      if b < 248 { out.append(chars[Int(b) % 62]) }
    }
    return String(decoding: out, as: UTF8.self)
  }
}
@MainActor @Observable public final class DeviceStore {
  public private(set) var devices: [SavedDevice]
  private let defaults: UserDefaults
  private let key: String
  public init(defaults: UserDefaults = .standard, key: String = "ESPHID.devices") {
    self.defaults = defaults
    self.key = key
    devices =
      (defaults.data(forKey: key).flatMap {
        try? JSONDecoder().decode([SavedDevice].self, from: $0)
      }) ?? []
  }
  public func upsert(_ d: SavedDevice) {
    if let i = devices.firstIndex(where: { $0.id == d.id }) {
      devices[i] = d
    } else {
      devices.append(d)
    }
    persist()
  }
  public func remove(id: String) {
    devices.removeAll { $0.id == id }
    setToken(nil, for: id)
    setMQTTPassword(nil, for: id)
    persist()
  }
  private func persist() {
    if let data = try? JSONEncoder().encode(devices) { defaults.set(data, forKey: key) }
  }
  public func token(for id: String) -> String? { secret(id + ".token") }
  public func setToken(_ value: String?, for id: String) { put(value, id + ".token") }
  public func mqttPassword(for id: String) -> String? { secret(id + ".mqtt") }
  public func setMQTTPassword(_ value: String?, for id: String) { put(value, id + ".mqtt") }
  private func query(_ account: String) -> [String: Any] {
    [
      kSecClass as String: kSecClassGenericPassword,
      kSecAttrService as String: "dev.scalexy.esphid", kSecAttrAccount as String: account,
    ]
  }
  private func secret(_ account: String) -> String? {
    var q = query(account)
    q[kSecReturnData as String] = true
    var result: CFTypeRef?
    guard SecItemCopyMatching(q as CFDictionary, &result) == errSecSuccess,
      let data = result as? Data
    else { return nil }
    return String(data: data, encoding: .utf8)
  }
  private func put(_ value: String?, _ account: String) {
    let q = query(account)
    guard let value else {
      SecItemDelete(q as CFDictionary)
      return
    }
    let attrs = [kSecValueData as String: Data(value.utf8)]
    let status = SecItemUpdate(q as CFDictionary, attrs as CFDictionary)
    if status == errSecItemNotFound {
      var add = q
      add.merge(attrs) { _, b in b }
      add[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
      SecItemAdd(add as CFDictionary, nil)
    }
  }
}

import Foundation

extension Data {
  mutating func le<T: FixedWidthInteger>(_ n: T) {
    var v = n.littleEndian
    Swift.withUnsafeBytes(of: &v) { append(contentsOf: $0) }
  }
  func uint(_ offset: Int, _ count: Int) -> UInt32 {
    (0..<count).reduce(0) { $0 | UInt32(self[startIndex + offset + $1]) << ($1 * 8) }
  }
}
public enum HIDCommand: Sendable, Equatable {
  case mouseMove(dx: Int16, dy: Int16)
  case mouseButtons(MouseButtons)
  case mouseScroll(wheel: Int8, pan: Int8)
  case mouseClick(MouseButtons)
  case mouseAbs(x: UInt16, y: UInt16, buttons: MouseButtons)
  case mouseReport(buttons: MouseButtons, dx: Int16, dy: Int16, wheel: Int8, pan: Int8)
  case keyDown(HIDKey)
  case keyUp(HIDKey)
  case keyTap(HIDKey, modifiers: Modifiers)
  case keyReport(modifiers: Modifiers, keys: [UInt8])
  case modifiers(Modifiers)
  case text(String)
  case releaseAll
  case consumerDown(ConsumerKey)
  case consumerUp
  case consumerTap(ConsumerKey)
  case delay(UInt16)
  case ping(UInt32)
  case getState
  case auth(String)
  public static func encode(_ commands: [Self]) -> Data {
    commands.reduce(into: Data()) { $0.append($1.bytes) }
  }
  var bytes: Data {
    var d = Data()
    switch self {
    case .mouseMove(let x, let y):
      d.append(1)
      d.le(x)
      d.le(y)
    case .mouseButtons(let b): d.append(contentsOf: [2, b.rawValue])
    case .mouseScroll(let w, let p):
      d.append(contentsOf: [3, UInt8(bitPattern: w), UInt8(bitPattern: p)])
    case .mouseClick(let b): d.append(contentsOf: [4, b.rawValue])
    case .mouseAbs(let x, let y, let b):
      d.append(5)
      d.le(x)
      d.le(y)
      d.append(b.rawValue)
    case .mouseReport(let b, let x, let y, let w, let p):
      d.append(contentsOf: [6, b.rawValue])
      d.le(x)
      d.le(y)
      d.append(contentsOf: [UInt8(bitPattern: w), UInt8(bitPattern: p)])
    case .keyDown(let k): d.append(contentsOf: [0x10, k.rawValue])
    case .keyUp(let k): d.append(contentsOf: [0x11, k.rawValue])
    case .keyTap(let k, let m): d.append(contentsOf: [0x12, m.rawValue, k.rawValue])
    case .keyReport(let m, let k):
      d.append(contentsOf: [0x13, m.rawValue])
      d.append(contentsOf: Array((k + Array(repeating: 0, count: 6)).prefix(6)))
    case .modifiers(let m): d.append(contentsOf: [0x14, m.rawValue])
    case .text(let s):
      var chunk = Data()
      for scalar in s.unicodeScalars {
        let bytes = Data(String(scalar).utf8)
        if chunk.count + bytes.count > 255 {
          d.append(contentsOf: [0x15, UInt8(chunk.count)])
          d.append(chunk)
          chunk.removeAll(keepingCapacity: true)
        }
        chunk.append(bytes)
      }
      if !chunk.isEmpty {
        d.append(contentsOf: [0x15, UInt8(chunk.count)])
        d.append(chunk)
      }
    case .releaseAll: d.append(0x16)
    case .consumerDown(let k):
      d.append(0x18)
      d.le(k.rawValue)
    case .consumerUp: d.append(0x19)
    case .consumerTap(let k):
      d.append(0x1a)
      d.le(k.rawValue)
    case .delay(let ms):
      d.append(0x20)
      d.le(min(ms, 5000))
    case .ping(let n):
      d.append(0x30)
      d.le(n)
    case .getState: d.append(0x31)
    case .auth(let s):
      let b = Array(s.utf8.prefix(255))
      d.append(contentsOf: [0x40, UInt8(b.count)])
      d.append(contentsOf: b)
    }
    return d
  }
}
public enum HIDEvent: Sendable, Equatable {
  case pong(nonce: UInt32, uptimeMs: UInt32)
  case leds(KeyboardLEDs)
  case state(flags: UInt8, leds: KeyboardLEDs, queueDepth: UInt16)
  case error(code: UInt8, opcode: UInt8)
  case auth(result: UInt8)
  public static func decode(_ data: Data) -> [Self] {
    let d = Data(data)
    var i = 0
    var result: [Self] = []
    while i < d.count {
      let op = d[i]
      let sizes: [UInt8: Int] = [0x80: 8, 0x81: 1, 0x82: 4, 0x83: 2, 0x84: 1]
      guard let n = sizes[op], i + 1 + n <= d.count else { break }
      i += 1
      switch op {
      case 0x80: result.append(.pong(nonce: d.uint(i, 4), uptimeMs: d.uint(i + 4, 4)))
      case 0x81: result.append(.leds(.init(rawValue: d[i])))
      case 0x82:
        result.append(
          .state(flags: d[i], leds: .init(rawValue: d[i + 1]), queueDepth: UInt16(d.uint(i + 2, 2)))
        )
      case 0x83: result.append(.error(code: d[i], opcode: d[i + 1]))
      default: result.append(.auth(result: d[i]))
      }
      i += n
    }
    return result
  }
}

/// Preserves ordering barriers while merging adjacent motion, retaining overflow.
struct MotionCoalescer {
  enum Item {
    case move(Int, Int)
    case scroll(Int, Int)
    case command(HIDCommand)
  }
  var items: [Item] = []
  mutating func append(_ c: HIDCommand) {
    switch c {
    case .mouseMove(let x, let y): move(Int(x), Int(y))
    case .mouseScroll(let w, let p): scroll(Int(w), Int(p))
    default: items.append(.command(c))
    }
  }
  mutating func move(_ x: Int, _ y: Int) {
    for i in items.indices.reversed() {
      if case .command = items[i] { break }
      if case .move(let a, let b) = items[i] {
        items[i] = .move(a + x, b + y)
        return
      }
    }
    items.append(.move(x, y))
  }
  mutating func scroll(_ w: Int, _ p: Int) {
    for i in items.indices.reversed() {
      if case .command = items[i] { break }
      if case .scroll(let a, let b) = items[i] {
        items[i] = .scroll(a + w, b + p)
        return
      }
    }
    items.append(.scroll(w, p))
  }
  mutating func packet(limit: Int) -> Data {
    var out = Data()
    while let first = items.first {
      var c: HIDCommand
      var remainder: Item?
      switch first {
      case .command(let cmd): c = cmd
      case .move(let x, let y):
        let a = Int16(clamping: x)
        let b = Int16(clamping: y)
        c = .mouseMove(dx: a, dy: b)
        if x != Int(a) || y != Int(b) { remainder = .move(x - Int(a), y - Int(b)) }
      case .scroll(let x, let y):
        let a = Int8(clamping: x)
        let b = Int8(clamping: y)
        c = .mouseScroll(wheel: a, pan: b)
        if x != Int(a) || y != Int(b) { remainder = .scroll(x - Int(a), y - Int(b)) }
      }
      let bytes = c.bytes
      guard out.count + bytes.count <= limit else { break }
      out.append(bytes)
      items.removeFirst()
      if let remainder { items.insert(remainder, at: 0) }
    }
    return out
  }
}
public enum BLERPCCodec {
  public static func chunks(_ data: Data, mtu: Int) throws -> [Data] {
    guard data.count <= 8192, mtu > 1 else { throw RPCError.transport("Invalid BLE RPC size/MTU") }
    let b = Array(data)
    let n = max(1, (b.count + mtu - 2) / (mtu - 1))
    return (0..<n).map { i in
      var d = Data([UInt8((i == 0 ? 1 : 0) | (i == n - 1 ? 2 : 0))])
      let start = i * (mtu - 1)
      d.append(contentsOf: b[start..<min(b.count, start + mtu - 1)])
      return d
    }
  }
  public struct Reassembler: Sendable {
    var buffer: Data?
    public init() {}
    public mutating func feed(_ chunk: Data) throws -> Data? {
      guard let h = chunk.first, h & 0xfc == 0 else {
        buffer = nil
        throw RPCError.decoding("Invalid BLE header")
      }
      if h & 1 != 0 { buffer = Data() }
      guard buffer != nil else { throw RPCError.decoding("Missing FIRST chunk") }
      buffer!.append(chunk.dropFirst())
      guard buffer!.count <= 8192 else {
        buffer = nil
        throw RPCError.decoding("BLE RPC exceeds 8 KB")
      }
      if h & 2 != 0 {
        defer { buffer = nil }
        return buffer
      }
      return nil
    }
  }
}
public struct PointerAccelerator: Sendable {
  public var sensitivity: Double
  public var acceleration: Double
  public init(sensitivity: Double = 1, acceleration: Double = 0) {
    self.sensitivity = sensitivity
    self.acceleration = acceleration
  }
  public mutating func apply(dx: Double, dy: Double, dt: TimeInterval) -> (dx: Double, dy: Double) {
    let speed = hypot(dx, dy) / max(dt, 0.001)
    let gain = max(0, sensitivity) * (1 + max(0, acceleration) * log1p(speed / 100))
    return (dx * gain, dy * gain)
  }
}

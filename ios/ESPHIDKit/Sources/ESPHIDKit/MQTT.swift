import Foundation

public enum MQTTCodec {
  public struct Packet: Equatable, Sendable {
    public let header: UInt8
    public let payload: Data
  }
  public static func remainingLength(_ value: Int) throws -> Data {
    guard (0...268_435_455).contains(value) else { throw RPCError.decoding("Invalid MQTT length") }
    var n = value
    var d = Data()
    repeat {
      var b = UInt8(n % 128)
      n /= 128
      if n > 0 { b |= 128 }
      d.append(b)
    } while n > 0
    return d
  }
  static func frame(_ h: UInt8, _ p: Data) -> Data {
    Data([h]) + (try! remainingLength(p.count)) + p
  }
  static func string(_ s: String) -> Data {
    let b = Data(s.utf8)
    var d = Data([UInt8((b.count >> 8) & 255), UInt8(b.count & 255)])
    d.append(b)
    return d
  }
  public static func connect(clientID: String, username: String? = nil, password: String? = nil)
    -> Data
  {
    var p = string("MQTT")
    p.append(4)
    p.append(2 | (username != nil ? 128 : 0) | (password != nil ? 64 : 0))
    p.append(contentsOf: [0, 30])
    p.append(string(clientID))
    if let username { p.append(string(username)) }
    if let password { p.append(string(password)) }
    return frame(0x10, p)
  }
  public static func publish(topic: String, payload: Data) -> Data {
    frame(0x30, string(topic) + payload)
  }
  public static func subscribe(id: UInt16, topics: [String]) -> Data {
    var d = Data([UInt8(id >> 8), UInt8(id & 255)])
    for t in topics {
      d.append(string(t))
      d.append(0)
    }
    return frame(0x82, d)
  }
  public struct Stream: Sendable {
    var buffer = Data()
    public init() {}
    public mutating func feed(_ bytes: Data) throws -> [Packet] {
      buffer.append(bytes)
      var out = [Packet]()
      while buffer.count >= 2 {
        var n = 0
        var multiplier = 1
        var i = 1
        var done = false
        while i < buffer.count && i <= 4 {
          let b = buffer[i]
          n += Int(b & 127) * multiplier
          i += 1
          if b & 128 == 0 {
            done = true
            break
          }
          multiplier *= 128
        }
        if !done {
          if i > 4 { throw RPCError.decoding("Malformed MQTT remaining length") }
          break
        }
        guard n <= 1_048_576 else { throw RPCError.decoding("MQTT frame too large") }
        guard buffer.count >= i + n else { break }
        out.append(Packet(header: buffer[0], payload: buffer.subdata(in: i..<i + n)))
        buffer = Data(buffer.dropFirst(i + n))
      }
      return out
    }
  }
}

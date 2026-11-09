import XCTest

@testable import ESPHIDKit

final class CoreTests: XCTestCase {
  func hex(_ s: String) -> Data { Data(s.split(separator: " ").map { UInt8($0, radix: 16)! }) }
  func testAllCommandVectors() {
    let vectors: [(HIDCommand, String)] = [
      (.mouseMove(dx: 10, dy: -5), "01 0a 00 fb ff"),
      (.mouseMove(dx: -300, dy: 300), "01 d4 fe 2c 01"), (.mouseButtons([.left, .right]), "02 03"),
      (.mouseScroll(wheel: -1, pan: 2), "03 ff 02"), (.mouseClick(.left), "04 01"),
      (.mouseAbs(x: 16384, y: 32767, buttons: []), "05 00 40 ff 7f 00"),
      (.mouseReport(buttons: .left, dx: 1, dy: 2, wheel: -1, pan: 0), "06 01 01 00 02 00 ff 00"),
      (.keyDown(.a), "10 04"), (.keyUp(.a), "11 04"), (.keyTap(.c, modifiers: .ctrl), "12 01 06"),
      (.keyReport(modifiers: .shift, keys: [4, 5]), "13 02 04 05 00 00 00 00"),
      (.modifiers(.lgui), "14 08"), (.text("Hi"), "15 02 48 69"), (.releaseAll, "16"),
      (.consumerTap(.playPause), "1a cd 00"), (.delay(100), "20 64 00"),
      (.ping(0x0102_0304), "30 04 03 02 01"), (.auth("abc"), "40 03 61 62 63"),
    ]
    for (c, s) in vectors { XCTAssertEqual(HIDCommand.encode([c]), hex(s), "\(c)") }
    XCTAssertEqual(
      HIDCommand.encode([.auth("abc"), .text("Hi")]), hex("40 03 61 62 63 15 02 48 69"))
  }
  func testEventVectors() {
    XCTAssertEqual(
      HIDEvent.decode(hex("80 04 03 02 01 e8 03 00 00")),
      [.pong(nonce: 0x0102_0304, uptimeMs: 1000)])
    XCTAssertEqual(HIDEvent.decode(hex("81 02")), [.leds(.capsLock)])
    XCTAssertEqual(HIDEvent.decode(hex("83 03 01")), [.error(code: 3, opcode: 1)])
    XCTAssertEqual(
      HIDEvent.decode(hex("82 05 02 34 12 84 00")),
      [.state(flags: 5, leds: .capsLock, queueDepth: 0x1234), .auth(result: 0)])
    XCTAssertEqual(HIDEvent.decode(hex("80 00")), [])
  }
  func testUTF8Splitting() {
    let text = String(repeating: "a", count: 254) + "😀é" + String(repeating: "中", count: 300)
    let d = HIDCommand.encode([.text(text)])
    var i = 0
    var rebuilt = ""
    while i < d.count {
      XCTAssertEqual(d[i], 0x15)
      let n = Int(d[i + 1])
      let s = String(data: d.subdata(in: i + 2..<i + 2 + n), encoding: .utf8)
      XCTAssertNotNil(s)
      rebuilt += s ?? ""
      i += 2 + n
    }
    XCTAssertEqual(rebuilt, text)
  }
  func testBLEChunking() throws {
    for mtu in [2, 20, 182, 512] {
      let d = Data((0..<8192).map { UInt8($0 % 256) })
      let chunks = try BLERPCCodec.chunks(d, mtu: mtu)
      var r = BLERPCCodec.Reassembler()
      var result: Data?
      for c in chunks {
        XCTAssertLessThanOrEqual(c.count, mtu)
        result = try r.feed(c)
      }
      XCTAssertEqual(result, d)
    }
    var r = BLERPCCodec.Reassembler()
    XCTAssertThrowsError(try r.feed(Data([2, 0])))
    XCTAssertThrowsError(try BLERPCCodec.chunks(Data(count: 8193), mtu: 20))
  }
  func testMQTT() throws {
    XCTAssertEqual(
      MQTTCodec.connect(clientID: "a"), hex("10 0d 00 04 4d 51 54 54 04 02 00 1e 00 01 61"))
    XCTAssertEqual(
      MQTTCodec.publish(topic: "x", payload: Data([1, 2])), hex("30 05 00 01 78 01 02"))
    XCTAssertEqual(MQTTCodec.subscribe(id: 1, topics: ["x"]), hex("82 06 00 01 00 01 78 00"))
    for (n, s) in [(127, "7f"), (128, "80 01"), (16383, "ff 7f"), (16384, "80 80 01")] {
      XCTAssertEqual(try MQTTCodec.remainingLength(n), hex(s))
      let framed = Data([0x30]) + (try MQTTCodec.remainingLength(n)) + Data(count: n)
      var stream = MQTTCodec.Stream()
      var out = [MQTTCodec.Packet]()
      for b in framed { out += try stream.feed(Data([b])) }
      XCTAssertEqual(out.count, 1)
      XCTAssertEqual(out.first?.payload.count, n)
    }
    var stream = MQTTCodec.Stream()
    XCTAssertEqual(try stream.feed(hex("90 03 00 01 00 c0 00")).map(\.header), [0x90, 0xc0])
    XCTAssertThrowsError(try stream.feed(hex("30 ff ff ff ff 01")))
  }
  func testCoalescer() {
    var c = MotionCoalescer()
    c.move(30000, 1)
    c.move(30000, 2)
    c.append(.mouseButtons(.left))
    c.move(1, 1)
    XCTAssertEqual(c.packet(limit: 5), HIDCommand.encode([.mouseMove(dx: 32767, dy: 3)]))
    XCTAssertEqual(
      c.packet(limit: 512),
      HIDCommand.encode([
        .mouseMove(dx: 27233, dy: 0), .mouseButtons(.left), .mouseMove(dx: 1, dy: 1),
      ]))
    c.scroll(200, -200)
    XCTAssertEqual(
      c.packet(limit: 512),
      HIDCommand.encode([.mouseScroll(wheel: 127, pan: -128), .mouseScroll(wheel: 73, pan: -72)]))
  }
  func testJSONRPC() throws {
    let d = try RPCWire.request(id: 1, method: "info", params: nil, token: "abc")
    let v = try JSONDecoder().decode(JSONValue.self, from: d)
    XCTAssertEqual(v["token"], "abc")
    XCTAssertEqual(
      try RPCWire.response(Data("{\"id\":1,\"result\":{\"id\":\"ABC\",\"claimed\":false}}".utf8)).1
        .decoded(DeviceInfo.self).id, "ABC")
    XCTAssertThrowsError(
      try RPCWire.response(
        Data("{\"id\":1,\"error\":{\"code\":401,\"message\":\"unauthorized\"}}".utf8))
    ) { XCTAssertEqual($0.localizedDescription, "Unauthorized") }
    XCTAssertThrowsError(
      try RPCWire.response(Data("{\"id\":1,\"error\":{\"code\":409,\"message\":\"claimed\"}}".utf8))
    )
    XCTAssertThrowsError(try RPCWire.response(Data("{}".utf8)))
  }
  func testAlternatingMotionCoalescesWithoutCrossingBarriers() {
    var c = MotionCoalescer()
    for _ in 0..<100 {
      c.move(1, 2)
      c.scroll(1, -1)
    }
    XCTAssertEqual(c.items.count, 2)
    c.append(.keyDown(.a))
    c.move(3, 4)
    XCTAssertEqual(
      c.packet(limit: 512),
      HIDCommand.encode([
        .mouseMove(dx: 100, dy: 200), .mouseScroll(wheel: 100, pan: -100),
        .keyDown(.a), .mouseMove(dx: 3, dy: 4),
      ]))
  }
  func testFractionalMotionAndInFlightBackpressure() async throws {
    let t = TestTransport(device: .init(id: "test", name: "Test"), token: nil)
    try await t.open()
    for _ in 0..<4 { t.motion(0.25, -0.25) }
    for _ in 0..<10 { t.motion(1, 2) }
    t.send(.keyDown(.a))
    t.motion(3, 4)
    await withCheckedContinuation { (done: CheckedContinuation<Void, Never>) in
      t.queue.async {
        XCTAssertEqual(t.packets, [HIDCommand.encode([.mouseMove(dx: 1, dy: -1)])])
        t.sent(nil)
        XCTAssertEqual(
          t.packets.last,
          HIDCommand.encode([
            .mouseMove(dx: 10, dy: 20), .keyDown(.a), .mouseMove(dx: 3, dy: 4),
          ]))
        done.resume()
      }
    }
    t.close()
  }
  func testVersions() {
    XCTAssertTrue(FirmwareUpdates.isNewer("v1.10.0", than: "1.9.9"))
    XCTAssertTrue(FirmwareUpdates.isNewer("1.0.0", than: "1.0.0-rc.1"))
    XCTAssertFalse(FirmwareUpdates.isNewer("1.0.0-rc.2", than: "1.0.0"))
    XCTAssertTrue(FirmwareUpdates.isNewer("1.0.0-rc.10", than: "1.0.0-rc.2"))
    XCTAssertFalse(FirmwareUpdates.isNewer("v1.2.0+abc", than: "1.2"))
  }
  func testPointer() {
    var a = PointerAccelerator(sensitivity: 1, acceleration: 1)
    let slow = a.apply(dx: 1, dy: 0, dt: 0.1).dx
    let fast = a.apply(dx: 1, dy: 0, dt: 0.01).dx
    XCTAssertGreaterThan(fast, slow)
    XCTAssertEqual(a.apply(dx: 0, dy: 0, dt: 0).dx, 0)
  }
  func testTokens() {
    for n in [0, 16, 32, 64] {
      let token = TokenGenerator.make(length: n)
      XCTAssertEqual(token.count, n)
      XCTAssertTrue(token.allSatisfy { $0.isASCII && ($0.isLetter || $0.isNumber) })
    }
  }
}

private final class TestTransport: Transport, @unchecked Sendable {
  var packets: [Data] = []
  override func writeHID(_ data: Data) { packets.append(data) }
}

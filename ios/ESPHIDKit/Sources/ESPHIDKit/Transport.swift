import CryptoKit
import Foundation
import Network
import Security

/// All mutable transport state belongs to queue. @unchecked bridges delegate APIs.
class Transport: NSObject, RPCChannel, @unchecked Sendable {
  let queue = DispatchQueue(label: "dev.scalexy.esphid.transport", qos: .userInteractive)
  let device: SavedDevice
  let token: String?
  var events: (@Sendable ([HIDEvent]) -> Void)?
  var failure: (@Sendable (String) -> Void)?
  var pending = MotionCoalescer()
  var busy = false
  var connected = false
  var closed = false
  var fractionX = 0.0, fractionY = 0.0, fractionW = 0.0, fractionP = 0.0
  var nextID = 0
  var requests: [(String, JSONValue?, CheckedContinuation<JSONValue, Error>)] = []
  var active: (Int, CheckedContinuation<JSONValue, Error>)?
  var opening: CheckedContinuation<Void, Error>?
  init(device: SavedDevice, token: String?) {
    self.device = device
    self.token = token
  }
  func open() async throws {
    try await withCheckedThrowingContinuation { c in
      queue.async {
        self.opening = c
        self.begin()
        self.queue.asyncAfter(deadline: .now() + 15) {
          if self.opening != nil { self.fail(RPCError.timeout) }
        }
      }
    }
  }
  func begin() { ready() }
  func ready() {
    guard !closed else { return }
    connected = true
    opening?.resume()
    opening = nil
  }
  func fail(_ error: Error) {
    closed = true
    connected = false
    opening?.resume(throwing: error)
    opening = nil
    active?.1.resume(throwing: error)
    active = nil
    requests.forEach { $0.2.resume(throwing: error) }
    requests = []
    failure?(error.localizedDescription)
    closeIO()
  }
  func closeIO() {}
  func close() {
    queue.async {
      self.failure = nil
      self.fail(RPCError.transport("Disconnected"))
      self.pending = MotionCoalescer()
    }
  }
  func send(_ c: HIDCommand) {
    queue.async {
      self.enqueue(c)
      self.flush()
    }
  }
  func enqueue(_ c: HIDCommand) {
    if case .text(let s) = c {  // Fit even the default BLE MTU; split at Unicode scalar boundaries.
      var part = ""
      let maxBytes = max(4, packetLimit - 2)
      for scalar in s.unicodeScalars {
        let ch = String(scalar)
        if part.utf8.count + ch.utf8.count > min(255, maxBytes) {
          pending.append(.text(part))
          part = ""
        }
        part += ch
      }
      if !part.isEmpty { pending.append(.text(part)) }
    } else {
      guard c.bytes.count <= packetLimit else {
        fail(RPCError.transport("Command exceeds negotiated packet size; larger BLE MTU required"))
        return
      }
      pending.append(c)
    }
  }
  func motion(_ x: Double, _ y: Double, scroll: Bool = false) {
    queue.async {
      guard x.isFinite, y.isFinite, abs(x) < 1e12, abs(y) < 1e12 else { return }
      if scroll {
        self.fractionW += x
        self.fractionP += y
        let a = Int(self.fractionW)
        let b = Int(self.fractionP)
        self.fractionW -= Double(a)
        self.fractionP -= Double(b)
        if a != 0 || b != 0 { self.pending.scroll(a, b) }
      } else {
        self.fractionX += x
        self.fractionY += y
        let a = Int(self.fractionX)
        let b = Int(self.fractionY)
        self.fractionX -= Double(a)
        self.fractionY -= Double(b)
        if a != 0 || b != 0 { self.pending.move(a, b) }
      }
      self.flush()
    }
  }
  var packetLimit: Int { 512 }
  func flush() {
    guard connected, !busy else { return }
    let data = pending.packet(limit: packetLimit)
    guard !data.isEmpty else { return }
    busy = true
    writeHID(data)
  }
  func writeHID(_ data: Data) { sent(nil) }
  func sent(_ error: Error?) {
    busy = false
    if let error { fail(error) } else { flush() }
  }
  func call(_ method: String, params: JSONValue?) async throws -> JSONValue {
    try await withCheckedThrowingContinuation { c in
      queue.async {
        guard self.connected else {
          c.resume(throwing: RPCError.transport("Not connected"))
          return
        }
        self.requests.append((method, params, c))
        self.nextRPC()
      }
    }
  }
  func nextRPC() {
    guard active == nil, !requests.isEmpty else { return }
    let (m, p, c) = requests.removeFirst()
    nextID += 1
    let id = nextID
    active = (id, c)
    do { try writeRPC(RPCWire.request(id: id, method: m, params: p, token: token)) } catch {
      finishRPC(.failure(error))
    }
    queue.asyncAfter(deadline: .now() + 10) {
      if self.active?.0 == id { self.fail(RPCError.timeout) }
    }
  }
  func writeRPC(_ data: Data) throws { throw RPCError.transport("Unsupported channel") }
  func receiveRPC(_ data: Data) {
    guard let id = try? JSONDecoder().decode(JSONValue.self, from: data)["id"],
      case .number(let n) = id, let responseID = Int(exactly: n), responseID == active?.0
    else { return }
    do { finishRPC(.success(try RPCWire.response(data).1)) } catch { finishRPC(.failure(error)) }
  }
  func finishRPC(_ result: Result<JSONValue, Error>) {
    let c = active?.1
    active = nil
    c?.resume(with: result)
    nextRPC()
  }
}
func fingerprint(_ cert: SecCertificate) -> String {
  SHA256.hash(data: SecCertificateCopyData(cert) as Data).map { String(format: "%02X", $0) }.joined(
    separator: ":")
}
final class HTTPTransport: Transport, URLSessionDelegate, @unchecked Sendable {
  var session: URLSession!
  override func begin() {
    let c = URLSessionConfiguration.ephemeral
    c.httpMaximumConnectionsPerHost = 4
    c.timeoutIntervalForRequest = 10
    c.requestCachePolicy = .reloadIgnoringLocalCacheData
    session = URLSession(configuration: c, delegate: self, delegateQueue: nil)
    ready()
  }
  func request(_ path: String, _ data: Data, _ mime: String) throws -> URLRequest {
    guard let host = device.host else { throw RPCError.transport("Missing host") }
    var u = URLComponents()
    u.scheme = device.httpsPort == nil ? "http" : "https"
    u.host = host
    u.port = device.httpsPort ?? device.port
    u.path = path
    guard let url = u.url else { throw RPCError.transport("Invalid host") }
    var r = URLRequest(url: url)
    r.httpMethod = "POST"
    r.httpBody = data
    r.setValue(mime, forHTTPHeaderField: "Content-Type")
    r.setValue("keep-alive", forHTTPHeaderField: "Connection")
    if let token { r.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization") }
    return r
  }
  override func writeHID(_ data: Data) {
    do {
      let r = try request("/api/hid", data, "application/octet-stream")
      session.dataTask(with: r) { _, response, error in
        self.queue.async {
          let status = (response as? HTTPURLResponse)?.statusCode ?? 0
          self.sent(
            error ?? ((200..<300).contains(status) ? nil : RPCError.transport("HTTP \(status)")))
        }
      }.resume()
    } catch { sent(error) }
  }
  override func writeRPC(_ data: Data) throws {
    let r = try request("/api/rpc", data, "application/json")
    session.dataTask(with: r) { bytes, _, error in
      self.queue.async {
        if let error {
          self.finishRPC(.failure(error))
        } else if let bytes {
          self.receiveRPC(bytes)
        }
      }
    }.resume()
  }
  override func closeIO() { session?.invalidateAndCancel() }
  func urlSession(
    _ session: URLSession, didReceive challenge: URLAuthenticationChallenge,
    completionHandler: @escaping (URLSession.AuthChallengeDisposition, URLCredential?) -> Void
  ) {
    guard challenge.protectionSpace.authenticationMethod == NSURLAuthenticationMethodServerTrust,
      let pin = device.tlsFingerprint, let trust = challenge.protectionSpace.serverTrust,
      let cert = (SecTrustCopyCertificateChain(trust) as? [SecCertificate])?.first
    else {
      completionHandler(.performDefaultHandling, nil)
      return
    }
    if fingerprint(cert) == pin {
      completionHandler(.useCredential, URLCredential(trust: trust))
    } else {
      completionHandler(.cancelAuthenticationChallenge, nil)
    }
  }
}
final class SocketTransport: Transport, @unchecked Sendable {
  let kind: TransportKind
  let password: String?
  var connection: NWConnection?
  var stream = MQTTCodec.Stream()
  let clientID = "esphid-ios-" + UUID().uuidString
  var prefix = ""
  var reply = ""
  var keepalive: DispatchSourceTimer?
  init(device: SavedDevice, token: String?, kind: TransportKind, password: String?) {
    self.kind = kind
    self.password = password
    super.init(device: device, token: token)
  }
  override var packetLimit: Int {
    kind == .mqtt ? max(1, 512 - HIDCommand.auth(token ?? "").bytes.count) : 512
  }
  override func begin() {
    do {
      let tcp = NWProtocolTCP.Options()
      tcp.noDelay = true
      var host: String
      var port: Int
      var tls: NWProtocolTLS.Options?
      if kind == .mqtt {
        guard let m = device.mqtt, let u = URLComponents(string: m.uri), let h = u.host,
          ["mqtt", "mqtts"].contains(u.scheme ?? "")
        else { throw RPCError.transport("Invalid MQTT URI") }
        host = h
        port = u.port ?? (u.scheme == "mqtts" ? 8883 : 1883)
        if u.scheme == "mqtts" { tls = NWProtocolTLS.Options() }
        prefix = m.topicPrefix
        reply = prefix + "/rpc/ios/" + clientID
      } else {
        guard let h = device.host else { throw RPCError.transport("Missing host") }
        host = h
        port = device.httpsPort ?? device.port
        if device.httpsPort != nil {
          tls = NWProtocolTLS.Options()
          if let pin = device.tlsFingerprint {
            sec_protocol_options_set_verify_block(
              tls!.securityProtocolOptions,
              { _, trust, complete in
                let t = sec_trust_copy_ref(trust).takeRetainedValue()
                let cert = (SecTrustCopyCertificateChain(t) as? [SecCertificate])?.first
                complete(cert.map { fingerprint($0) == pin } ?? false)
              }, queue)
          }
        }
      }
      guard let p = NWEndpoint.Port(rawValue: UInt16(exactly: port) ?? 0), port > 0 else {
        throw RPCError.transport("Invalid port")
      }
      let parameters = NWParameters(tls: tls, tcp: tcp)
      if kind == .websocket {
        let ws = NWProtocolWebSocket.Options()
        ws.autoReplyPing = true
        ws.maximumMessageSize = 8192
        if let token { ws.setAdditionalHeaders([("Authorization", "Bearer \(token)")]) }
        parameters.defaultProtocolStack.applicationProtocols.insert(ws, at: 0)
      }
      let endpoint: NWEndpoint
      if kind == .websocket {
        var u = URLComponents()
        u.scheme = tls == nil ? "ws" : "wss"
        u.host = host
        u.port = port
        u.path = "/ws"
        guard let url = u.url else { throw RPCError.transport("Invalid URL") }
        endpoint = .url(url)
      } else {
        endpoint = .hostPort(host: .init(host), port: p)
      }
      let c = NWConnection(to: endpoint, using: parameters)
      connection = c
      c.stateUpdateHandler = { [weak self] state in
        guard let self else { return }
        switch state {
        case .ready:
          if self.kind == .mqtt {
            self.raw(
              MQTTCodec.connect(
                clientID: self.clientID, username: self.device.mqtt?.username,
                password: self.password))
            self.receiveTCP()
          } else {
            self.ready()
            self.receiveWS()
          }
        case .failed(let e): self.fail(e)
        default: break
        }
      }
      c.start(queue: queue)
    } catch { fail(error) }
  }
  func raw(_ data: Data, completion: (@Sendable (NWError?) -> Void)? = nil) {
    connection?.send(content: data, completion: .contentProcessed { e in completion?(e) })
  }
  override func writeHID(_ data: Data) {
    if kind == .mqtt {
      raw(
        MQTTCodec.publish(
          topic: prefix + "/hid", payload: HIDCommand.auth(token ?? "").bytes + data)
      ) { self.sent($0) }
    } else {
      websocket(data, opcode: .binary) { self.sent($0) }
    }
  }
  func websocket(
    _ data: Data, opcode: NWProtocolWebSocket.Opcode, done: @escaping @Sendable (NWError?) -> Void
  ) {
    let m = NWProtocolWebSocket.Metadata(opcode: opcode)
    connection?.send(
      content: data, contentContext: .init(identifier: UUID().uuidString, metadata: [m]),
      isComplete: true, completion: .contentProcessed(done))
  }
  override func writeRPC(_ data: Data) throws {
    if kind == .mqtt {
      var v = try JSONDecoder().decode(JSONValue.self, from: data)
      if case .object(var o) = v {
        o["reply_to"] = .string(reply)
        v = .object(o)
      }
      raw(MQTTCodec.publish(topic: prefix + "/rpc/req", payload: try JSONEncoder().encode(v))) {
        if let e = $0 { self.fail(e) }
      }
    } else {
      websocket(data, opcode: .text) { if let e = $0 { self.fail(e) } }
    }
  }
  func receiveWS() {
    connection?.receiveMessage { [weak self] data, context, _, error in
      guard let self else { return }
      if let error {
        self.fail(error)
        return
      }
      guard let data else {
        self.fail(RPCError.transport("WebSocket closed"))
        return
      }
      if let m = context?.protocolMetadata(definition: NWProtocolWebSocket.definition)
        as? NWProtocolWebSocket.Metadata
      {
        if m.opcode == .binary {
          self.events?(HIDEvent.decode(data))
        } else if m.opcode == .text {
          self.receiveRPC(data)
        } else if m.opcode == .close {
          self.fail(RPCError.transport("WebSocket closed"))
          return
        }
      }
      self.receiveWS()
    }
  }
  func receiveTCP() {
    connection?.receive(minimumIncompleteLength: 1, maximumLength: 65536) {
      [weak self] data, _, end, error in
      guard let self else { return }
      if let error {
        self.fail(error)
        return
      }
      do {
        if let data { for p in try self.stream.feed(data) { try self.mqtt(p) } }
        if end { self.fail(RPCError.transport("MQTT closed")) } else { self.receiveTCP() }
      } catch { self.fail(error) }
    }
  }
  func mqtt(_ p: MQTTCodec.Packet) throws {
    let b = p.payload
    switch p.header >> 4 {
    case 2:
      guard b.count == 2, b[1] == 0 else { throw RPCError.transport("MQTT connection rejected") }
      raw(MQTTCodec.subscribe(id: 1, topics: [prefix + "/events", reply]))
    case 9:
      guard b.count == 4, b[0] == 0, b[1] == 1, b[2] == 0, b[3] == 0 else {
        throw RPCError.transport("MQTT subscription rejected")
      }
      ready()
      let timer = DispatchSource.makeTimerSource(queue: queue)
      timer.schedule(deadline: .now() + 15, repeating: 15)
      timer.setEventHandler { [weak self] in self?.raw(Data([0xc0, 0])) }
      keepalive = timer
      timer.resume()
    case 3:
      guard b.count >= 2 else { throw RPCError.decoding("Truncated MQTT topic") }
      let n = Int(b[0]) * 256 + Int(b[1])
      guard b.count >= 2 + n, p.header & 6 == 0 else {
        throw RPCError.decoding("Invalid MQTT QoS/topic")
      }
      let topic = String(decoding: b[2..<2 + n], as: UTF8.self)
      let payload = Data(b.dropFirst(2 + n))
      if topic == reply {
        receiveRPC(payload)
      } else if topic == prefix + "/events" {
        events?(HIDEvent.decode(payload))
      }
    default: break
    }
  }
  override func closeIO() {
    keepalive?.cancel()
    keepalive = nil
    if kind == .mqtt { raw(Data([0xe0, 0])) }
    connection?.cancel()
    connection = nil
  }
}

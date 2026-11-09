@preconcurrency import CoreBluetooth
import Foundation
import Network
import Observation

let serviceUUID = CBUUID(string: "705c0001-a217-40f5-b47b-6712ed7d7dac")
let hidUUID = CBUUID(string: "705c0002-a217-40f5-b47b-6712ed7d7dac")
let eventsUUID = CBUUID(string: "705c0003-a217-40f5-b47b-6712ed7d7dac")
let rpcUUID = CBUUID(string: "705c0004-a217-40f5-b47b-6712ed7d7dac")
final class BLETransport: Transport, CBCentralManagerDelegate, CBPeripheralDelegate,
  @unchecked Sendable
{
  var central: CBCentralManager!
  var peripheral: CBPeripheral?
  var hid: CBCharacteristic?
  var rpcChar: CBCharacteristic?
  var subscribed = Set<CBUUID>()
  var assembler = BLERPCCodec.Reassembler()
  var rpcChunks = [Data]()
  override func begin() { central = CBCentralManager(delegate: self, queue: queue) }
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    if central.state == .poweredOn {
      guard let id = device.bleIdentifier else {
        fail(RPCError.transport("Missing BLE identifier"))
        return
      }
      if let p = central.retrievePeripherals(withIdentifiers: [id]).first {
        attach(p)
      } else {
        central.scanForPeripherals(withServices: [serviceUUID])
      }
    } else if central.state == .unauthorized || central.state == .unsupported {
      fail(RPCError.transport("Bluetooth unavailable or unauthorized"))
    }
  }
  func attach(_ p: CBPeripheral) {
    central.stopScan()
    peripheral = p
    p.delegate = self
    central.connect(p)
  }
  func centralManager(
    _ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) { if p.identifier == device.bleIdentifier { attach(p) } }
  func centralManager(_ central: CBCentralManager, didConnect p: CBPeripheral) {
    p.discoverServices([serviceUUID])
  }
  func centralManager(
    _ central: CBCentralManager, didFailToConnect peripheral: CBPeripheral, error: Error?
  ) { fail(error ?? RPCError.transport("BLE connect failed")) }
  func centralManager(
    _ central: CBCentralManager, didDisconnectPeripheral peripheral: CBPeripheral, error: Error?
  ) { fail(error ?? RPCError.transport("BLE disconnected")) }
  func peripheral(_ p: CBPeripheral, didDiscoverServices error: Error?) {
    if let error {
      fail(error)
      return
    }
    guard let s = p.services?.first(where: { $0.uuid == serviceUUID }) else {
      fail(RPCError.transport("BLE service missing"))
      return
    }
    p.discoverCharacteristics([hidUUID, eventsUUID, rpcUUID], for: s)
  }
  func peripheral(
    _ p: CBPeripheral, didDiscoverCharacteristicsFor service: CBService, error: Error?
  ) {
    if let error {
      fail(error)
      return
    }
    for c in service.characteristics ?? [] {
      if c.uuid == hidUUID { hid = c }
      if c.uuid == rpcUUID { rpcChar = c }
      if c.uuid == eventsUUID || c.uuid == rpcUUID { p.setNotifyValue(true, for: c) }
    }
  }
  func peripheral(
    _ p: CBPeripheral, didUpdateNotificationStateFor c: CBCharacteristic, error: Error?
  ) {
    if let error {
      fail(error)
      return
    }
    if c.isNotifying { subscribed.insert(c.uuid) }
    if subscribed.contains(eventsUUID), subscribed.contains(rpcUUID), hid != nil { ready() }
  }
  override var packetLimit: Int {
    min(512, peripheral?.maximumWriteValueLength(for: .withoutResponse) ?? 20)
  }
  override func flush() {
    guard peripheral?.canSendWriteWithoutResponse == true else { return }
    super.flush()
  }
  override func writeHID(_ data: Data) {
    guard let p = peripheral, let hid else {
      sent(RPCError.transport("BLE HID unavailable"))
      return
    }
    p.writeValue(data, for: hid, type: .withoutResponse)
    busy = false
    queue.async { self.flush() }
  }
  func peripheralIsReady(toSendWriteWithoutResponse peripheral: CBPeripheral) { flush() }
  override func writeRPC(_ data: Data) throws {
    guard let p = peripheral else { throw RPCError.transport("BLE disconnected") }
    rpcChunks = try BLERPCCodec.chunks(data, mtu: p.maximumWriteValueLength(for: .withResponse))
    writeNextChunk()
  }
  func writeNextChunk() {
    guard !rpcChunks.isEmpty, let p = peripheral, let c = rpcChar else { return }
    p.writeValue(rpcChunks.removeFirst(), for: c, type: .withResponse)
  }
  func peripheral(_ p: CBPeripheral, didWriteValueFor c: CBCharacteristic, error: Error?) {
    if let error { fail(error) } else if c.uuid == rpcUUID { writeNextChunk() }
  }
  func peripheral(_ p: CBPeripheral, didUpdateValueFor c: CBCharacteristic, error: Error?) {
    if let error {
      fail(error)
      return
    }
    guard let data = c.value else { return }
    if c.uuid == eventsUUID {
      events?(HIDEvent.decode(data))
    } else if c.uuid == rpcUUID {
      do { if let msg = try assembler.feed(data) { receiveRPC(msg) } } catch { fail(error) }
    }
  }
  override func closeIO() {
    central?.stopScan()
    if let peripheral { central?.cancelPeripheralConnection(peripheral) }
    peripheral?.delegate = nil
  }
}
public struct DiscoveredDevice: Identifiable, Hashable, Sendable {
  public enum Source: Hashable, Sendable {
    case ble(peripheral: UUID, rssi: Int)
    case lan(host: String, port: Int)
  }
  public var id: String
  public var name: String
  public var source: Source
  public var deviceId: String?
  public var claimed: Bool?
  public var fwVersion: String?
  public func savedDevice() -> SavedDevice {
    switch source {
    case .ble(let p, _):
      return .init(id: deviceId ?? id, name: name, bleIdentifier: p, preferredTransport: .ble)
    case .lan(let h, let p): return .init(id: deviceId ?? id, name: name, host: h, port: p)
    }
  }
}
private final class DiscoveryWorker: NSObject, CBCentralManagerDelegate, NetServiceDelegate,
  @unchecked Sendable
{
  let queue = DispatchQueue(label: "com.scalexy.esphid.discovery")
  var central: CBCentralManager!
  var browser: NWBrowser?
  var services = [NetService]()
  var running = false
  var report: (@Sendable (DiscoveredDevice) -> Void)?
  func start() {
    queue.async {
      self.running = true
      self.central = CBCentralManager(delegate: self, queue: self.queue)
      let params = NWParameters.tcp
      params.includePeerToPeer = true
      let b = NWBrowser(
        for: .bonjourWithTXTRecord(type: "_esphid._tcp", domain: nil), using: params)
      self.browser = b
      b.browseResultsChangedHandler = { [weak self = self] results, _ in
        for r in results {
          guard let self else { return }
          if case .service(let name, let type, let domain, _) = r.endpoint {
            DispatchQueue.main.async {
              let s = NetService(domain: domain, type: type, name: name)
              s.delegate = self
              self.services.append(s)
              s.resolve(withTimeout: 5)
            }
          }
        }
      }
      b.start(queue: self.queue)
    }
  }
  func stop() {
    queue.async {
      self.running = false
      self.central?.stopScan()
      self.browser?.cancel()
      self.browser = nil
    }
    DispatchQueue.main.async {
      self.services.forEach { $0.stop() }
      self.services = []
    }
  }
  func centralManagerDidUpdateState(_ central: CBCentralManager) {
    if running, central.state == .poweredOn {
      central.scanForPeripherals(
        withServices: [serviceUUID], options: [CBCentralManagerScanOptionAllowDuplicatesKey: true])
    }
  }
  func centralManager(
    _ central: CBCentralManager, didDiscover p: CBPeripheral, advertisementData: [String: Any],
    rssi RSSI: NSNumber
  ) {
    report?(
      .init(
        id: "ble:" + p.identifier.uuidString, name: p.name ?? "ESPHID",
        source: .ble(peripheral: p.identifier, rssi: RSSI.intValue)))
  }
  func netServiceDidResolveAddress(_ sender: NetService) {
    guard let host = sender.hostName else { return }
    let txt = sender.txtRecordData().map(NetService.dictionary(fromTXTRecord:)) ?? [:]
    func str(_ k: String) -> String? { txt[k].flatMap { String(data: $0, encoding: .utf8) } }
    report?(
      .init(
        id: "lan:" + (str("id") ?? host), name: str("name") ?? sender.name,
        source: .lan(host: host, port: sender.port), deviceId: str("id"),
        claimed: str("claimed").map { $0 == "1" }, fwVersion: str("fw")))
  }
}
@MainActor @Observable public final class DeviceDiscovery {
  public private(set) var found = [DiscoveredDevice]()
  public private(set) var isScanning = false
  private let worker = DiscoveryWorker()
  public init() {
    worker.report = { [weak self] d in
      Task { @MainActor in
        guard let self, self.isScanning else { return }
        if let i = self.found.firstIndex(where: { $0.id == d.id }) {
          self.found[i] = d
        } else {
          self.found.append(d)
        }
      }
    }
  }
  public func start() {
    guard !isScanning else { return }
    found = []
    isScanning = true
    worker.start()
  }
  public func stop() {
    isScanning = false
    worker.stop()
  }
}

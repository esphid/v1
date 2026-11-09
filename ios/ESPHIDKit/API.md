# ESPHIDKit

Apple-only native library, iOS 17 / macOS 14. Swift 5 language mode with complete
strict concurrency checking. Delegate objects use `@unchecked Sendable`: mutable
transport state is confined to a dedicated user-interactive serial DispatchQueue.
Observable stores and connections are main-actor isolated. No view code is included.

## Connect and send

```swift
import ESPHIDKit
// Main actor (e.g. a UI model). Keep this connection alive for the session.
let connection = HIDConnection()
let device = SavedDevice(id: "A1B2C3", name: "Desk", host: "esphid-a1b2c3.local")
try await connection.connect(to: device, via: .websocket, token: savedToken)
connection.moveMouse(dx: 0.75, dy: -2.5)
connection.setButtons(.left)
connection.scroll(wheel: 1, pan: 0)
connection.tap(.c, modifiers: .ctrl)
connection.type("Hello\n")
connection.consumerTap(.playPause)
connection.releaseAll()
connection.disconnect()
```

`HIDConnection.State`: `idle`, `connecting`, `authenticating`, `connected`,
`failed(String)`. Observable properties: `state`, `transport: TransportKind?`,
`rttMs: Double?`, `leds: KeyboardLEDs`, `usbMounted: Bool?`, `rpc: RPCClient?`.
`connect(to:via:token:mqttPassword:)` is async/throwing; `disconnect()` is synchronous.
Input methods enqueue work without waiting for I/O: `moveMouse(dx:dy:)`,
`setButtons(_:)`, `click(_:)` (default `.left`), `scroll(wheel:pan:)`, `keyDown(_:)`,
`keyUp(_:)`, `tap(_:modifiers:)`, `setModifiers(_:)`, `type(_:)`,
`consumerTap(_:)`, `releaseAll()`. Fractional motion is carried between calls.
Input is ignored until the connection reaches `.connected`.
Time spent enqueuing is small but no real-device latency benchmark is claimed.

## Setup wizard via BLE RPC

```swift
let setup = SavedDevice.bleSetupDevice(peripheral: peripheralUUID, name: "ESPHID")
let rpc = try await DeviceSession.openRPC(to: setup, via: .ble, token: nil)
defer { rpc.close() }
let publicInfo = try await rpc.info()
let networks = try await rpc.scanWiFi()
let token = TokenGenerator.make()
try await rpc.claim(token: token)
try await rpc.auth(token: token) // Authenticate the newly claimed BLE session.
let change = try await rpc.setConfig([
    "wifi": ["ssid": "Home", "password": "secret"],
    "radio_mode": "wifi"
])
if change.rebootRequired { try await rpc.reboot() }
```

`DeviceSession.openRPC(to:via:token:mqttPassword:)` opens a management connection;
BLE automatically calls `auth` when a non-nil token is supplied. HTTP supports
management without opening an HID session. `RPCClient.close()` closes its channel;
it also closes on deinitialization. Keep the client alive across wizard steps.

`RPCChannel: Sendable` exposes `call(_:params:) async throws -> JSONValue` for mocks
and custom channels. `RPCClient.init(channel:)` supports dependency injection.
All RPCClient methods are async/throwing except `close()`:

| Method | Result |
| --- | --- |
| `info()` | `DeviceInfo` |
| `auth(token:)`, `claim(token:)`, `setToken(_:)` | Void |
| `getConfig()` | `DeviceConfig` |
| `setConfig(_:)` | `(config: DeviceConfig, rebootRequired: Bool)` |
| `scanWiFi()` | `[WiFiNetwork]` |
| `startOTA(url:sha256:)` | Void; URL is a String; hash defaults nil |
| `otaStatus()` | `OTAStatus` |
| `reboot(delayMs:)` | Void; delay defaults 0 |
| `factoryReset()` | Void |
| `regenerateHTTPSCertificate()` | String fingerprint |
| `sendHID(json:)` | Void; pass the entire `{"commands": [...]}` object |

`RPCError`: `remote(code:message:)`, `timeout`, `transport(String)`,
`decoding(String)`, `unauthorized`; conforms to `Error`, `LocalizedError`, Sendable.
All transport RPCs are serialized, correlated by integer ID, and time out after
10 seconds. Timeout closes the channel so stale BLE responses cannot cross requests.
Connection establishment times out after 15 seconds. Reconnect explicitly after failure.

## Discovery

```swift
let discovery = DeviceDiscovery() // retain in a main-actor model
 discovery.start()
// Observe discovery.found. BLE and LAN entries remain separate sources.
if let found = discovery.found.first {
    let saved = found.savedDevice()
    // BLE setup info supplies the real device ID; replace the temporary UUID ID.
}
discovery.stop()
```

`DeviceDiscovery` exposes `found`, `isScanning`, `start()`, `stop()`.
`DiscoveredDevice` has `id`, `name`, `source`, `deviceId`, `claimed`, `fwVersion`;
source is `.ble(peripheral: UUID, rssi: Int)` or `.lan(host: String, port: Int)`.
NWBrowser discovers `_esphid._tcp` with TXT metadata; NetService resolves its
hostname, port, and TXT record. CoreBluetooth scans the protocol service UUID.
No inference of claim status from the BLE name is attempted.

## Models and persistence

`DeviceInfo`, `DeviceConfig`, `WiFiNetwork`, `OTAStatus` are Codable / Sendable.
All wire fields, including nested objects, are optional. Field names match JSON
(e.g. `info.fw_version`, `info.usb?.mounted`, `config.keyboard?.layout`).
`DeviceInfo`, `DeviceConfig`, `OTAStatus` are Equatable; `WiFiNetwork` is Hashable
and Identifiable (SSID/channel/auth identity). Nested public model types are
`USBInfo`, `WiFiInfo`, `BLEInfo`, `HTTPSInfo`, `MQTTInfo`, `TailscaleInfo`, `OTAInfo`,
`WiFiConfig`, `MQTTConfig`, `TailscaleConfig`, `KeyboardConfig`.

`TransportKind`: `.ble`, `.websocket`, `.http`, `.mqtt` (String/Codable/CaseIterable).
`MQTTSettings.init(uri:username:topicPrefix:)` has optional username. Passwords are
not part of this Codable structure.
`SavedDevice.init(id:name:host:port:httpsPort:tlsFingerprint:bleIdentifier:mqtt:
preferredTransport:fwVariant:otaRepo:)` defaults port to 80, preferred transport to
websocket, and other optional fields to nil. It is Codable/Identifiable/Hashable.

```swift
let store = DeviceStore() // defaults to UserDefaults, JSON-encoded device array
store.upsert(device)
store.setToken(TokenGenerator.make(length: 32), for: device.id)
store.setMQTTPassword("broker-secret", for: device.id)
let token = store.token(for: device.id)
let password = store.mqttPassword(for: device.id)
store.remove(id: device.id) // also deletes both Keychain entries
```

`DeviceStore.devices` is read-only observable; `upsert(_:)`, `remove(id:)`,
`token(for:)`, `setToken(_:for:)`, `mqttPassword(for:)`, `setMQTTPassword(_:for:)`.
Secret getters return optional strings and nil deletes a secret. Keychain uses
AfterFirstUnlockThisDeviceOnly; secrets never enter the saved-device JSON.
`DeviceStore.init(defaults:key:)` allows isolated persistence for tests.
`TokenGenerator.make(length:)` uses SecRandomCopyBytes and rejection sampling for
unbiased base62. Default 32 characters; negative length returns empty.

## Wire primitives and input helpers

`MouseButtons`: left/right/middle/back/forward. `Modifiers`: lctrl/lshift/lalt/lgui/
rctrl/rshift/ralt/rgui, plus ctrl/shift/alt/gui aliases. `KeyboardLEDs`:
numLock/capsLock/scrollLock/compose/kana. These are UInt8 OptionSets and Hashable.
`HIDKey: UInt8, CaseIterable`: every protocol §3 usage and `displayName`.
HIDKey also exposes ctrl/shift/alt/gui/cmd/win/meta aliases.
Numeric key names are Swift-safe `digit0` ... `digit9`; `.none` is usage zero
(for modifier-only taps). `nonUSHash` is also exposed. `ConsumerKey: UInt16`:
playPause/next/prev/stop/mute/volumeUp/volumeDown/brightnessUp/brightnessDown.

`HIDCommand` cases (one per opcode):
`mouseMove(dx:dy:)`, `mouseButtons`, `mouseScroll(wheel:pan:)`, `mouseClick`,
`mouseAbs(x:y:buttons:)`, `mouseReport(buttons:dx:dy:wheel:pan:)`, `keyDown`,
`keyUp`, `keyTap(_:modifiers:)`, `keyReport(modifiers:keys:)`, `modifiers`, `text`,
`releaseAll`, `consumerDown`, `consumerUp`, `consumerTap`, `delay`, `ping`,
`getState`, `auth`. `HIDCommand.encode(_:) -> Data` concatenates commands and splits
TEXT at 255 bytes on Unicode scalar boundaries. Encoding does not impose a packet
limit: transports split at command boundaries to their 512-byte/MTU limit.
Raw key reports accept `[UInt8]`, padded/truncated to six keys. Delay clamps to 5 s.

`HIDEvent.decode(_:) -> [HIDEvent]`: `pong(nonce:uptimeMs:)`, `leds`,
`state(flags:leds:queueDepth:)`, `error(code:opcode:)`, `auth(result:)`.
Stops at the first unknown/truncated event, preserving earlier events.
`BLERPCCodec.chunks(_:mtu:)` and `Reassembler.feed(_:)` implement FIRST/LAST,
8 KB bounds, and malformed-header validation. `MQTTCodec` exposes `connect`,
`publish`, `subscribe`, `remainingLength`, `Stream.feed`, and Packet header/payload.

`JSONValue` cases: null/bool/number(Double)/string/array/object. Codable, Equatable,
Sendable; supports Swift nil/bool/integer/float/string/array/dictionary literals,
key/index optional subscripts, `.string`, `.bool`, `.array`, `decoded(_:)`.

```swift
let bytes = HIDCommand.encode([.auth("token"), .keyTap(.a, modifiers: .ctrl)])
let events = HIDEvent.decode(notification)
var accelerator = PointerAccelerator(sensitivity: 1.2, acceleration: 0.5)
let delta = accelerator.apply(dx: 2, dy: -1, dt: 1.0 / 120)
```

`PointerAccelerator` is Sendable, with public sensitivity/acceleration and a
mutating `apply(dx:dy:dt:)`; defaults are 1/0. Gain grows monotonically with speed.

## OTA check

```swift
if let release = try await FirmwareUpdates.latest(repo: "owner/repo", variant: "s3-n8r8"),
   FirmwareUpdates.isNewer(release.version, than: "1.0.0") {
    try await connection.rpc?.startOTA(url: release.assetURL.absoluteString)
    let progress = try await connection.rpc?.otaStatus()
}
```

`FirmwareRelease`: version, tag, notes, publishedAt (optional Date), assetURL (URL).
`FirmwareUpdates.latest(repo:variant:)` calls GitHub releases/latest and selects
exactly `esphid-<variant>.bin`; returns nil for 404 or missing assets. Other HTTP
errors throw. `isNewer(_:than:)` compares numeric version components and SemVer
prerelease identifiers; ignores build metadata and a leading `v`.

## Transport behavior and device validation

- BLE: dedicated CoreBluetooth serial queue. Subscribe to Events and RPC before
  becoming ready. HID uses withoutResponse and checks canSendWriteWithoutResponse;
  resumes from peripheralIsReady. Batches commands up to negotiated MTU (max 512).
  RPC writes withResponse, waiting for each write acknowledgement. AUTH command
  waits for EVT_AUTH. An MTU too small for AUTH fails clearly rather than truncating.
- WebSocket: NWConnection, TCP_NODELAY, `/ws`, upgrade Authorization header,
  binary HID and text RPC. One HID send outstanding; send completion flushes pending
  motion immediately, with no batching timer. HTTPS uses a pinned SHA-256 leaf DER
  fingerprint when supplied; absent pins use normal platform trust.
- HTTP: ephemeral URLSession, keepalive and four connections per host, one HID POST
  outstanding with motion coalescing. RPC can proceed on another connection.
  HTTPS uses a matching pin to trust self-signed certificates. HTTP does not provide
  event notifications, so RTT/LED/USB properties remain unknown unless received on
  an event-capable transport. PING is still sent every two seconds.
- MQTT: minimal 3.1.1 over NWConnection TCP/TLS, clean session, broker credentials,
  keepalive 30 s with PINGREQ every 15 s. Wait for CONNACK and QoS0 SUBACK before
  ready. Unique per-client RPC response topic; per-packet AUTH and request token.
  One HID send outstanding, motion merged until completion; MQTT stream parser
  handles partial and multiple packets, rejecting malformed lengths/oversized frames.
- All transports keep at most one accumulated move and scroll between key/button
  barriers, retain integer overflow, and never merge key/button transitions.
  Event-capable connections send HID PING every two seconds and smooth RTT 80/20.

Hardware validation remains necessary: BLE bonding/MTU/backpressure, actual firmware
RPC and events, mDNS permissions/TXT resolution, self-signed HTTPS/WSS pins,
MQTT broker ACLs and disconnects, physical HID ordering, and latency at 120 Hz.
The protocol specifies a 15-second WS ping; the task explicitly requests 2 seconds,
which this library uses. No protocol document was edited.

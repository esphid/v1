# ESPHID core

All public types live in `com.scalexy.esphid.core`. Android API 26+, Kotlin, JDK 17.
No UI or permission dialogs are provided. Call lifecycle methods from one owner
coroutine; `HidConnection` input methods may be called from the UI thread.

## Connect and send

```kotlin
val store = DeviceStore(context)
val device = store.devices.value.first()
val connection = HidConnection(context)
connection.connect(device, device.preferredTransport, store.token(device.id)!!,
                   store.mqttPassword(device.id))
// connect publishes Failed on errors; observe state before enabling controls.
connection.moveMouse(0.5f, -2f) // fractional remainder retained
connection.setButtons(MouseButtons.LEFT)
connection.tap(HidKey.C, Modifiers.LCTRL)
connection.type("Hello\n")
connection.consumerTap(ConsumerKey.PLAY_PAUSE)
connection.releaseAll()
connection.disconnect() // close when the screen/service no longer owns it
```

`state`, `transport`, `rttMs`, `leds`, `usbMounted` are read-only StateFlows.
States: Idle, Connecting, Authenticating, Connected, Failed(message).
`rpc: RpcClient?` is available while connected. Other immediate input methods:
`click(buttons = LEFT)`, `scroll(wheel, pan)`, `keyDown`, `keyUp`,
`setModifiers`. Calls while disconnected are ignored. Inputs are handed off under
short locks, encoded and sent on a dedicated executor. Consecutive motion merges;
key/button commands are barriers and retain their order. Integer overflow on the
wire is avoided by splitting large accumulated moves/scrolls and carrying remainder.
Packets batch complete commands to the transport limit (512 bytes or MTU−3).

BLE uses a HandlerThread, serialized GATT operations, notifications before RPC,
MTU 517, high priority and preferred 2M PHY. No-response HID writes await the Android
write callback. Status 133 has up to two retries. Unbonded devices are paired before
GATT setup, with up to 30 seconds for Android's system Pair prompt. The app must
remain able to display that prompt. Pairing rejection/failure and timeout report
clear errors. CCCD and characteristic writes that return authentication/encryption
status 5, 15 or 137 wait for bonding and retry once, keeping the GATT queue serialized.
WebSocket uses immediate OkHttp sends and waits for a nonempty socket queue to drain
while input accumulates. HTTP serializes HID POSTs with connection pooling; management
RPC can run independently. MQTT uses a raw TCP/TLS socket, a dedicated IO reader,
synchronized complete packet writes, QoS0, unique reply topic, CONNECT/SUBACK checks,
30-second keepalive and 15-second PINGREQ. All TCP sockets use TCP_NODELAY.
Protocol PINGs run every two seconds on event-capable transports; RTT uses 0.2 EWMA.
HTTP has no event endpoint: RTT and USB state stay null and LEDs have no live updates.

## Discovery and permissions

```kotlin
val discovery = DeviceDiscovery(context)
val required = discovery.missingPermissions()
// Request these through the UI's runtime permission launcher first.
discovery.start() // safe even if permissions were denied; LAN can still work
val saved = discovery.found.value.firstOrNull()?.toSavedDevice()
discovery.stop()
```

`found` and `isScanning` are StateFlows. `Source.Ble(address,rssi)`
and `.Lan(host,port)` identify endpoints; `deviceId`, `claimed`, `fwVersion` are
nullable (the BLE advertisement does not carry these). Discovered keys identify the
endpoint, and `toSavedDevice()` prefers firmware ID when available. mDNS resolves
TXT and address with API 34 ServiceInfoCallback or legacy resolveService.
Start/stop discovery on the main thread. `isScanning` indicates the requested scan
lifecycle; it does not guarantee that Bluetooth is enabled.

## Setup wizard / management-only sessions

```kotlin
val client = DeviceSessions.openRpc(context, discovered.toSavedDevice(),
    TransportKind.BLE, token = null)
try {
    val publicInfo = client.info()
    val token = TokenGenerator.make()
    client.claim(token)
    client.auth(token)
    store.upsert(discovered.toSavedDevice())
    store.setToken(discovered.toSavedDevice().id, token)
    client.setConfig(buildJsonObject {
        put("wifi", buildJsonObject { put("ssid", "Home"); put("password", "secret") })
    })
} finally { DeviceSessions.close(client) }
```

The same API supports HTTP management without creating a HID connection.
`RpcChannel.call(method, params)` permits alternate channels and test fakes.
`RpcClient`: `info`, `auth`, `claim`, `setToken`, `getConfig`, `setConfig`,
`scanWifi`, `startOta(url,sha256?)`, `otaStatus`, `reboot(delayMs?)`, `factoryReset`,
`regenerateHttpsCertificate`, `sendHid(JsonObject)`.
`ConfigSetResult` contains `config` and `rebootRequired`.
`RpcException` subclasses: Remote(code,message), Timeout, Transport(message),
Decoding(message), Unauthorized. RPC requests time out after ten seconds; BLE
serializes complete requests and uses FIRST/LAST chunk framing up to 8 KB.

## Persistence and TLS

`SavedDevice` and `MqttSettings` match the requested contract. `TransportKind` is
serializable. `DeviceStore.devices`, suspend `upsert` and `remove` persist device
JSON. Synchronous `token`, `setToken`, `mqttPassword`, `setMqttPassword` use Android
Keystore AES-GCM with a random IV and storage-key AAD. Call secret accessors from an
IO coroutine: these deliberately provide the specified synchronous API and may do
disk/Keystore work. Removing a device removes both secrets. Backup is disabled.
The AES key is never exported; encrypted preferences cannot be restored onto a
new install/device. Invalidated/corrupt secrets read as null and need re-entry.

HTTPS/WSS requires BOTH `httpsPort` and the previously trusted `tlsFingerprint`.
Fingerprint is SHA-256 of leaf certificate DER, uppercase hex separated by colons.
Pin comparison replaces CA trust and hostname checking only for these pinned
connections. Cleartext LAN/Tailscale HTTP/WS is supported. MQTT TLS instead uses
system CA validation and hostname checking. MQTT supports mqtt/mqtts URLs only.

## Wire and models

`MouseButtons`, `Modifiers`, `KeyboardLeds`, `HidKey` (every protocol key name,
plus NON_US_HASH) and `ConsumerKey` expose numeric usages. Number keys are
`DIGIT_0`…`DIGIT_9`; key display names match protocol strings.
`HidCommand` has MouseMove, MouseButtons, MouseScroll, MouseClick, MouseAbs,
MouseReport, KeyDown, KeyUp, KeyTap, KeyReport, SetModifiers, Text, ReleaseAll,
ConsumerDown, ConsumerUp, ConsumerTap, Delay, Ping, GetState, Auth.
`HidCodec.encode(List<HidCommand>)` concatenates frames and splits UTF-8 TEXT at
255 bytes. It does not impose a packet ceiling: use `frames` and packetize at whole
command boundaries for custom transports. `decodeEvents` returns Pong, Leds, State,
Error, Auth; malformed event frames throw IllegalArgumentException. `State` also
exposes USB mounted/suspended and authenticated flags.

```kotlin
val bytes = HidCodec.encode(listOf(HidCommand.MouseMove(10, -5)))
val event = HidCodec.decodeEvents(byteArrayOf(0x81.toByte(), 2)).single()
```

`ProtocolJson` ignores unknown fields. `DeviceInfo`, `DeviceConfig`, `WifiNetwork`,
`OtaStatus` and every nested model field are nullable/defaulted; their property
names mirror the protocol's snake_case names. Config patches use `JsonObject`.
`BleRpcFraming` and `MqttCodec` expose pure framing helpers for tests/custom channels.

## Firmware updates and pointer helpers

```kotlin
val info = connection.rpc!!.info()
val release = FirmwareUpdates.latest(info.ota!!.repo!!, info.fw_variant!!)
if (release != null && FirmwareUpdates.isNewer(release.version, info.fw_version!!)) {
    connection.rpc!!.startOta(release.assetUrl) // optionally provide verified SHA256
}
val accelerated = PointerAccelerator(sensitivity = 1f, acceleration = 0.5f)
    .apply(dx = 3f, dy = 1f, dtMs = 8f)
```

`latest` returns null for no release or missing exact `esphid-<variant>.bin` asset;
other HTTP errors throw Transport. Version comparison understands numeric core,
prerelease ordering, leading v, and ignores build metadata. Pointer acceleration
uses a monotonic logarithmic speed gain; callers supply elapsed time.

## Deliberate differences and verification limits

The requested API names/signatures exist. Config/info fields retain snake_case;
ConfigSetResult.rebootRequired uses a serialization alias. No UI is implemented.
HTTP cannot expose events in protocol v1. The protocol asks WebSocket PING every
15 seconds; this app follows the task's stricter two-second latency measurement.
MQTT events are broadcast, so nonce matching is used for local RTT only.

Unit tests cover all §2.5 vectors, UTF-8 splitting, packet concatenation, RPC examples,
BLE chunks, MQTT remaining lengths/stream reassembly and input helpers. Real phone
and firmware tests remain necessary for bonding, status 133 recovery, Android vendor
no-response callbacks, negotiated MTU/PHY, discovery across routers, self-signed TLS,
MQTT broker ACLs, USB state feedback and measured input latency. CI release attachment
and Google Play authorization require an actual release and account credentials.

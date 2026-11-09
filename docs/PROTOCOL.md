# ESPHID Protocol — v1

This is the single source of truth shared by the firmware (`firmware/`), the iOS app
(`ios/`), the Android app (`android/`) and the on-device web UI. If code and this
document disagree, this document wins; change it first.

Design goal: **lowest possible input latency**. Every choice below favours that:
persistent connections, binary frames, no per-event request/response, coalescing of
pointer motion, 1 kHz USB polling.

All multi-byte integers are **little-endian**. "u8/i8/u16/i16/u32" have their usual
meaning.

---

## 1. Device identity

| Item | Value |
|---|---|
| `device_id` | last 3 bytes of the base MAC, upper-case hex, e.g. `A1B2C3` |
| default name | `ESPHID-A1B2C3` |
| hostname / mDNS | `esphid-a1b2c3` → `esphid-a1b2c3.local` |
| SoftAP SSID (setup) | `ESPHID-A1B2C3` (open network, captive portal at `http://192.168.4.1/`) |
| BLE advertised name | the device name (truncated to fit) |
| protocol version | `1` (reported as `proto` in `info`) |

---

## 2. HID packet format (binary)

A **packet** is one or more **commands** concatenated. The same packet bytes are used on
every transport: BLE write, WebSocket binary frame, HTTP `POST /api/hid`
(`application/octet-stream`), MQTT `<prefix>/hid` payload.

Each command = `opcode (u8)` + payload. The payload length is fixed per opcode except
where noted. Commands in a packet execute in order. On an unknown opcode or a
truncated payload the device stops parsing that packet (it cannot know the length) and
emits `EVT_ERROR`. Commands before the bad one have already been applied.

Maximum packet size: **512 bytes** (BLE: the negotiated `ATT_MTU - 3`).

### 2.1 Client → device commands

| Op | Name | Payload | Bytes | Notes |
|---|---|---|---|---|
| `0x01` | `MOUSE_MOVE` | `i16 dx, i16 dy` | 4 | Relative. +x right, +y down. Device coalesces consecutive moves. |
| `0x02` | `MOUSE_BUTTONS` | `u8 buttons` | 1 | Absolute button state (bitmask below). |
| `0x03` | `MOUSE_SCROLL` | `i8 wheel, i8 pan` | 2 | +wheel = scroll up (away from user), +pan = right. Coalesced. |
| `0x04` | `MOUSE_CLICK` | `u8 buttons` | 1 | Press then release the given buttons (two reports). |
| `0x05` | `MOUSE_ABS` | `u16 x, u16 y, u8 buttons` | 5 | Absolute pointer, `0..32767` on each axis. |
| `0x06` | `MOUSE_REPORT` | `u8 buttons, i16 dx, i16 dy, i8 wheel, i8 pan` | 7 | Full relative report in one command. |
| `0x10` | `KEY_DOWN` | `u8 usage` | 1 | HID keyboard usage (page 0x07). `0xE0..0xE7` set modifier bits. |
| `0x11` | `KEY_UP` | `u8 usage` | 1 | |
| `0x12` | `KEY_TAP` | `u8 modifiers, u8 usage` | 2 | Press modifiers+key, then release both. `usage = 0` taps modifiers only. |
| `0x13` | `KEY_REPORT` | `u8 modifiers, u8 keys[6]` | 7 | Raw boot-keyboard state; replaces the current state. |
| `0x14` | `MODIFIERS` | `u8 modifiers` | 1 | Absolute modifier state (sticky modifiers from the app). |
| `0x15` | `TEXT` | `u8 len, len × u8 (UTF-8)` | 1+len | Typed by the device using the configured layout (§2.4). |
| `0x16` | `RELEASE_ALL` | — | 0 | Release every key, modifier, mouse button and consumer key. |
| `0x18` | `CONSUMER_DOWN` | `u16 usage` | 2 | Consumer page (0x0C) usage, e.g. `0x00CD` play/pause. |
| `0x19` | `CONSUMER_UP` | — | 0 | Release the consumer key. |
| `0x1A` | `CONSUMER_TAP` | `u16 usage` | 2 | Press then release. |
| `0x20` | `DELAY` | `u16 ms` | 2 | Pause the HID queue (macros). Max 5000; larger values are clamped. |
| `0x30` | `PING` | `u32 nonce` | 4 | Answered immediately by the transport layer with `EVT_PONG` (does not wait for the HID queue). |
| `0x31` | `GET_STATE` | — | 0 | Device answers with `EVT_STATE`. |
| `0x40` | `AUTH` | `u8 len, len × u8 token` | 1+len | Authenticates the session (BLE, WebSocket), or the packet (MQTT, HTTP). See §4. |

**Mouse buttons bitmask:** bit0 left, bit1 right, bit2 middle, bit3 back, bit4 forward.

**Modifier bitmask** (standard HID): bit0 LCtrl, bit1 LShift, bit2 LAlt, bit3 LGUI,
bit4 RCtrl, bit5 RShift, bit6 RAlt, bit7 RGUI.

### 2.2 Device → client events

Sent as BLE notifications on the Events characteristic, WebSocket binary frames, and
MQTT `<prefix>/events` payloads. Same framing: opcode + fixed payload, several events
may be concatenated.

| Op | Name | Payload | Bytes |
|---|---|---|---|
| `0x80` | `EVT_PONG` | `u32 nonce, u32 device_uptime_ms` | 8 |
| `0x81` | `EVT_LEDS` | `u8 leds` (bit0 NumLock, bit1 CapsLock, bit2 ScrollLock, bit3 Compose, bit4 Kana) | 1 |
| `0x82` | `EVT_STATE` | `u8 flags, u8 leds, u16 hid_queue_depth` | 4 |
| `0x83` | `EVT_ERROR` | `u8 code, u8 opcode` | 2 |
| `0x84` | `EVT_AUTH` | `u8 result` (0 = ok, 1 = bad token, 2 = locked out, 3 = device unclaimed) | 1 |

`EVT_STATE.flags`: bit0 USB mounted, bit1 USB suspended, bit2 session authenticated.

`EVT_LEDS` is sent whenever the host changes the keyboard LEDs, and once right after a
session authenticates.

**Error codes:** 1 `UNKNOWN_OPCODE`, 2 `TRUNCATED`, 3 `NOT_AUTHORIZED`, 4 `QUEUE_FULL`,
5 `USB_NOT_READY`, 6 `BAD_ARG`.

### 2.3 Device behaviour (latency rules)

- USB full-speed HID, `bInterval = 1` (1000 Hz) on every IN endpoint.
- Keyboard and mouse are separate USB interfaces with separate endpoints, so a key report
  and a mouse report can go out in the same 1 ms frame.
- `MOUSE_MOVE` / `MOUSE_SCROLL` deltas are **accumulated** while the mouse endpoint is
  busy and flushed the instant it is ready. Motion is never dropped; it is clamped to the
  report range and the remainder carried into the next report.
- Keyboard state changes are **never coalesced**: every intermediate state produces its own
  report, in order, so no keystroke is lost.
- Button changes flush any pending motion first, then send the button change (so a
  drag starts where the user expects).
- If the host is suspended and a command arrives, the device issues a USB remote wakeup.
- If USB is not mounted, commands are dropped and `EVT_ERROR(USB_NOT_READY)` is sent at
  most once per second per session.

### 2.4 `TEXT` typing

The device maps each UTF-8 character through the keyboard layout in
`config.keyboard.layout` (v1 ships `us`; the table design allows more layouts). `\n` →
Enter, `\t` → Tab, `\b` → Backspace. Characters with no mapping are skipped. Each
character is pressed (with Shift/AltGr as needed) then released; a release is inserted
between repeated identical keys. Optional `config.keyboard.type_delay_ms` adds a delay
between characters for slow hosts (default 0 = as fast as USB allows, about 500 chars/s).
The device-side text buffer holds at least 4096 bytes; overflow → `EVT_ERROR(QUEUE_FULL)`.

### 2.5 Encoding examples (test vectors)

Every implementation must have unit tests that reproduce these bytes exactly.

| Command | Bytes (hex) |
|---|---|
| `MOUSE_MOVE dx=10 dy=-5` | `01 0a 00 fb ff` |
| `MOUSE_MOVE dx=-300 dy=300` | `01 d4 fe 2c 01` |
| `MOUSE_BUTTONS left+right` | `02 03` |
| `MOUSE_SCROLL wheel=-1 pan=2` | `03 ff 02` |
| `MOUSE_CLICK left` | `04 01` |
| `MOUSE_ABS x=16384 y=32767 buttons=0` | `05 00 40 ff 7f 00` |
| `MOUSE_REPORT b=1 dx=1 dy=2 wheel=-1 pan=0` | `06 01 01 00 02 00 ff 00` |
| `KEY_DOWN a (0x04)` | `10 04` |
| `KEY_UP a` | `11 04` |
| `KEY_TAP ctrl+c` | `12 01 06` |
| `KEY_REPORT shift, [a, b]` | `13 02 04 05 00 00 00 00` |
| `MODIFIERS lgui` | `14 08` |
| `TEXT "Hi"` | `15 02 48 69` |
| `RELEASE_ALL` | `16` |
| `CONSUMER_TAP play/pause` | `1a cd 00` |
| `DELAY 100` | `20 64 00` |
| `PING 0x01020304` | `30 04 03 02 01` |
| `AUTH "abc"` | `40 03 61 62 63` |
| packet: `AUTH "abc"` + `TEXT "Hi"` | `40 03 61 62 63 15 02 48 69` |
| `EVT_PONG nonce=0x01020304 uptime=1000` | `80 04 03 02 01 e8 03 00 00` |
| `EVT_LEDS caps` | `81 02` |
| `EVT_ERROR NOT_AUTHORIZED op=0x01` | `83 03 01` |

Text longer than 255 bytes is split by the client into several `TEXT` commands, never in
the middle of a UTF-8 sequence.

---

## 3. JSON HID commands (scripting convenience)

For curl, home automation and MQTT users. Accepted by `POST /api/hid` with
`Content-Type: application/json`, MQTT `<prefix>/hid/json`, and the `hid` RPC method.
Apps use the binary format instead.

```json
{"commands": [
  {"t": "move", "dx": 10, "dy": -5},
  {"t": "buttons", "b": 1},
  {"t": "click", "b": 1},
  {"t": "scroll", "v": 1, "h": 0},
  {"t": "abs", "x": 16384, "y": 16384, "b": 0},
  {"t": "down", "k": "a"},
  {"t": "up", "k": "a"},
  {"t": "tap", "k": "c", "mods": ["ctrl"]},
  {"t": "mods", "mods": ["shift"]},
  {"t": "text", "s": "Hello, world\n"},
  {"t": "release"},
  {"t": "consumer", "u": "play_pause"},
  {"t": "delay", "ms": 100}
]}
```

`k` is a key name or a raw usage number. Key names: `a`–`z`, `0`–`9`, `enter`, `esc`,
`backspace`, `tab`, `space`, `minus`, `equal`, `lbracket`, `rbracket`, `backslash`,
`semicolon`, `quote`, `grave`, `comma`, `period`, `slash`, `capslock`, `f1`–`f24`,
`printscreen`, `scrolllock`, `pause`, `insert`, `home`, `pageup`, `delete`, `end`,
`pagedown`, `right`, `left`, `down`, `up`, `numlock`, `menu`, `lctrl`, `lshift`, `lalt`,
`lgui`, `rctrl`, `rshift`, `ralt`, `rgui` (aliases: `ctrl`, `shift`, `alt`,
`gui`/`cmd`/`win`/`meta` → left variants).
Modifier names in `mods`: `ctrl`, `shift`, `alt`, `gui` (aliases `cmd`, `win`, `meta`),
plus `rctrl`, `rshift`, `ralt`, `rgui`.
Consumer names: `play_pause`, `next`, `prev`, `stop`, `mute`, `vol_up`, `vol_down`,
`brightness_up`, `brightness_down`, or a raw usage number.

---

## 4. Authentication and claiming

- A new device is **unclaimed**: it has no access token. While unclaimed, `info`,
  `claim`, `wifi.scan` and `config.set`/`config.get` work without auth (setup), but **all
  HID commands are rejected** (`EVT_ERROR(NOT_AUTHORIZED)` / `EVT_AUTH(3)`).
- `claim {token}` sets the access token once. After that every transport requires it.
- Token: 16–64 printable ASCII characters (`0x21..0x7E`). Apps generate 32 random
  base62 characters by default. Stored in NVS; never returned by any API. Compared in
  constant time.
- **HTTP:** `Authorization: Bearer <token>` header. (Alternatively a binary `/api/hid`
  body may begin with an `AUTH` command.)
- **WebSocket:** authenticated at upgrade via `Authorization: Bearer <token>` header or
  `?token=<token>` query parameter, **or** by sending `AUTH` as the first binary frame.
  Until authenticated only `AUTH`, `PING` and the RPC `info` method are accepted.
- **BLE:** per connection. Write `AUTH` to the HID characteristic, or call RPC `auth`.
  The device answers `EVT_AUTH`. The link itself is encrypted (§5).
- **MQTT:** the broker's own auth/TLS/ACLs are the primary boundary. Additionally, when
  `config.mqtt.require_token` is true (default), every `<prefix>/hid` packet must begin
  with an `AUTH` command and every RPC request must carry `"token"`.
- **Setup transports:** the unclaimed exceptions above apply to HTTP and BLE. An
  unauthenticated WebSocket still only accepts `AUTH`, `PING` and `info`, and MQTT with
  `require_token` (the default) requires a valid token on every message, so a device is
  claimed over BLE, HTTP, or the setup hotspot page.
- **Held input is always released:**
  - when an authenticated BLE or WebSocket session ends for any reason, the device
    immediately releases every key, modifier, mouse button and consumer key, discarding
    any input still queued (including pending `DELAY`/`TEXT`);
  - a WebSocket that receives nothing for 20 s is closed (TCP keepalive also detects
    vanished peers), so a phone that drops off WiFi mid-drag can't leave a button held;
  - input pressed over HTTP or MQTT (stateless) is released if no further HID command
    arrives on that transport for 10 s, and when the MQTT broker connection drops. Only
    the keys and buttons that transport pressed are released; input held by a live BLE or
    WebSocket session is untouched.
  - a WebSocket that has not authenticated within 10 s of opening is closed, and a plain
    `GET /ws` without an upgrade gets `426 Upgrade Required`.
- **Lockout:** after 5 failed attempts on a transport within 60 s, that transport rejects
  auth for 30 s (`EVT_AUTH(2)` / HTTP 429).
- **Factory reset:** hold the BOOT button (GPIO0) for 10 s, or RPC `system.factory_reset`.
  Erases config, token, bonds; device returns to unclaimed setup mode.

---

## 5. Bluetooth LE

NimBLE peripheral. One GATT service:

| Item | UUID | Properties |
|---|---|---|
| Service | `705c0001-a217-40f5-b47b-6712ed7d7dac` | primary, advertised |
| HID characteristic | `705c0002-a217-40f5-b47b-6712ed7d7dac` | Write Without Response, Write. Payload = HID packet (§2). |
| Events characteristic | `705c0003-a217-40f5-b47b-6712ed7d7dac` | Notify. Payload = event frames (§2.2). |
| RPC characteristic | `705c0004-a217-40f5-b47b-6712ed7d7dac` | Write, Notify. Chunked JSON-RPC (below). |

Link settings (device requests; clients should accept):

- LE Secure Connections, Just Works, bonding (up to 8 bonds). Characteristics require an
  encrypted link, so the OS pairs automatically on first access.
- 2M PHY preferred, Data Length Extension on, ATT MTU up to 517.
- Connection interval request 7.5–15 ms, peripheral latency 0, supervision timeout 2 s.
  (iOS will settle at 15 ms; Android clients should call
  `requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`.)
- Advertising interval 20–30 ms (the device is USB powered), advertising data carries the
  service UUID, scan response carries the name.
- Up to 2 simultaneous connections.

Clients send HID packets with **Write Without Response** and must respect flow control
(iOS `canSendWriteWithoutResponse` / `peripheralIsReady(toSendWriteWithoutResponse:)`,
Android `onCharacteristicWrite` callback). While the link is busy, clients accumulate
pending mouse motion and send the sum when it frees, never queueing stale motion.

**RPC chunking** (RPC characteristic, both directions). Each write/notification is
`u8 header` + data. Header bit0 = FIRST chunk, bit1 = LAST chunk (a single-chunk message
has header `0x03`). Data of all chunks concatenated is one UTF-8 JSON message (§7),
max 8 KB. One request in flight per connection; the client waits for the response
before sending the next. Clients must subscribe to RPC notifications before writing.

---

## 6. WiFi: HTTP, WebSocket, HTTPS, mDNS

- WiFi station; when no network is configured (or `wifi.ap_fallback` and the station
  cannot connect for 60 s) the device also runs the setup SoftAP with a captive-portal DNS
  server. All HTTP/WS endpoints work on the SoftAP too, so a phone can control the device
  directly with no router.
- HTTP server on port 80 (always on in WiFi modes). Optional HTTPS server on
  `config.https.port` (default 443) with the same endpoints, using a self-signed EC P-256
  certificate generated on the device. Its SHA-256 fingerprint (of the DER certificate,
  upper-case hex with colons) is in `info.https.fingerprint`; clients pin it
  (trust-on-first-use).
- Sockets use `TCP_NODELAY`.

### 6.1 HTTP endpoints

| Method & path | Auth | Body → Response |
|---|---|---|
| `GET /` | no | Embedded web UI (config, OTA, test pad). |
| `GET /api/info` | no* | `info` result (§7.2). *Full detail only with auth. |
| `POST /api/rpc` | per method | JSON-RPC request (§7) → response. HTTP status mirrors the error code (200 on success). |
| `POST /api/hid` | yes | `application/octet-stream` HID packet, or `application/json` §3 → `204`; or `200 application/octet-stream` with the event frames when the packet produces events (e.g. `PING`, `GET_STATE`). |
| `GET /api/config` | yes** | `config.get` result. |
| `PATCH /api/config` | yes** | partial config → `config.set` result. |
| `POST /api/claim` | no | `{"token": "..."}` → `claim` result. |
| `GET /api/wifi/scan` | yes** | `wifi.scan` result. |
| `POST /api/ota` | yes | `{"url": "...", "sha256": "optional hex"}` → `ota.start` result. |
| `GET /api/ota` | yes | `ota.status` result. |
| `POST /api/ota/upload` | yes | raw firmware `.bin` body (`application/octet-stream`) → flashes and reports like `ota.status`. |
| `POST /api/reboot` | yes | → `{"ok": true}` |
| `POST /api/factory-reset` | yes | → `{"ok": true}` |
| `GET /ws` | see §4 | WebSocket upgrade. |
| `GET /generate_204`, `/hotspot-detect.html`, `/ncsi.txt`, `/connecttest.txt` | no | Captive-portal probes → redirect to `/` (SoftAP only; normal responses otherwise). |

** = no auth needed while unclaimed (setup).

### 6.2 WebSocket `/ws`

- **Binary frames** client → device: HID packets. Device → client: event frames.
- **Text frames**: JSON-RPC requests/responses (§7), same as `/api/rpc`.
- Up to 4 concurrent WebSocket clients (HTTP and HTTPS combined). Clients send a `PING`
  at least every 15 s (the apps send one every 2 s for the live latency readout); an
  authenticated socket silent for 20 s is closed and its held input released.

### 6.3 mDNS / DNS-SD

- Hostname `esphid-a1b2c3.local`.
- Service `_esphid._tcp` on port 80 with TXT records: `id=A1B2C3`, `name=<name>`,
  `fw=<version>`, `proto=1`, `claimed=0|1`, `https=<port or 0>`, `ws=/ws`.
- Also `_http._tcp` on port 80 for browsers.

### 6.4 Tailscale

- Native tailnet node on the device using the MicroLink client
  (component `fugo101/microlink`, MIT). Enabled with `config.tailscale.enabled` + an auth
  key (`config.tailscale.auth_key`, write-only, max 95 bytes; reusable or ephemeral keys
  from the Tailscale admin console). Optional `control_url` for Headscale: an `https://`
  origin with no custom port or path (the library always uses port 443 and its own
  paths), served with a publicly trusted certificate. Hostname max 47 bytes.
- Compiled into the PSRAM variants only (`s3-n8r2`, `s3-n8r8`); on `s3-n8` enabling it
  returns `400`.
- The same HTTP/WS endpoints are reachable on the device's `100.x.y.z` address and its
  MagicDNS name `<hostname>.<tailnet>.ts.net`. Tailscale already encrypts the path, so
  plain `ws://` is fine there.
- Reported in `info.tailscale` (`state`, `ip`, `hostname`).
- Alternative with no firmware involvement: a Tailscale subnet router on the same LAN
  makes the device's LAN IP reachable from the tailnet.

### 6.5 Radio mode (important for latency)

The ESP32-S3 has one 2.4 GHz radio. With WiFi and BLE both active it time-slices them and
WiFi modem sleep is mandatory, which adds latency and jitter on both. So:

| `config.radio_mode` | BLE | WiFi | WiFi power save |
|---|---|---|---|
| `both` (default, best for setup) | on | on | modem sleep (`WIFI_PS_MIN_MODEM`) |
| `wifi` (fastest WiFi) | off | on | none (`WIFI_PS_NONE`) |
| `ble` (fastest BLE) | on | off | — |

Changing it requires a reboot (`config.set` returns `reboot_required: true`). Apps
recommend `wifi` or `ble` once the user has picked a primary transport. Recovery when
locked into `ble` mode or a bad WiFi config: factory reset, or (in `wifi` mode) the SoftAP
fallback.

---

## 7. JSON-RPC (configuration and management)

Same messages over HTTP (`POST /api/rpc`), WebSocket text frames, BLE RPC characteristic
and MQTT `<prefix>/rpc/req`.

Request: `{"id": 1, "method": "config.get", "params": {}, "token": "optional"}`
Success: `{"id": 1, "result": { ... }}`
Error: `{"id": 1, "error": {"code": 401, "message": "unauthorized"}}`

`token` in the body is only needed on MQTT, or as an alternative to the HTTP header.
MQTT requests may include `"reply_to": "<topic>"`; otherwise responses go to
`<prefix>/rpc/res`.

Error codes: `400` bad request, `401` unauthorized, `404` unknown method,
`409` already claimed / conflict, `429` locked out, `500` internal, `503` busy (e.g. OTA in
progress).

### 7.1 Methods

| Method | Auth | Params | Result |
|---|---|---|---|
| `info` | no* | — | Info object (§7.2) |
| `auth` | no | `{token}` | `{"ok": true}` (session transports only) |
| `claim` | no | `{token}` | `{"ok": true}`; `409` if already claimed |
| `token.set` | yes | `{token}` | `{"ok": true}` (other sessions are dropped) |
| `config.get` | yes** | — | Config object (§7.3) |
| `config.set` | yes** | partial config object | `{"config": {...}, "reboot_required": bool}` |
| `wifi.scan` | yes** | — | `{"networks": [{"ssid", "rssi", "auth", "channel"}]}` sorted by RSSI; `auth` ∈ `open`, `wep`, `wpa`, `wpa2`, `wpa3`, `wpa2-enterprise`, `wpa2/wpa3` |
| `ota.start` | yes | `{url, sha256?}` | `{"ok": true}`; progress via `ota.status`; `503` if already running |
| `ota.status` | yes | — | `{"state", "progress", "bytes", "total", "error", "version"}`; `state` ∈ `idle`, `downloading`, `verifying`, `done`, `error`. On `done` the device reboots ~1 s later. |
| `system.reboot` | yes | `{delay_ms?}` | `{"ok": true}` |
| `system.factory_reset` | yes | — | `{"ok": true}` |
| `https.regenerate` | yes | — | `{"fingerprint": "..."}` |
| `hid` | yes | `{"commands": [...]}` (§3) | `{"ok": true}` |

`*` unauthenticated callers get only: `id`, `name`, `model`, `fw_version`, `fw_variant`,
`proto`, `claimed`. `**` no auth needed while unclaimed.

WiFi changes from `config.set` apply live (no reboot). The SoftAP stays up for 60 s after
the station connects so the setup page / app can read the new IP from `info`, then turns
off.

### 7.2 Info object

```json
{
  "id": "A1B2C3",
  "name": "ESPHID-A1B2C3",
  "model": "esp32s3",
  "fw_version": "1.2.0",
  "fw_variant": "s3-n8r8",
  "idf_version": "v6.0.2",
  "proto": 1,
  "claimed": true,
  "uptime_s": 1234,
  "free_heap": 123456,
  "radio_mode": "both",
  "usb": {"mounted": true, "suspended": false, "leds": 2},
  "wifi": {"mode": "sta", "connected": true, "ssid": "Home", "ip": "192.168.1.50",
           "rssi": -55, "mac": "AA:BB:CC:A1:B2:C3", "ap_active": false, "ap_ip": "192.168.4.1"},
  "ble": {"enabled": true, "connections": 1},
  "https": {"enabled": false, "port": 443, "fingerprint": ""},
  "mqtt": {"enabled": false, "connected": false},
  "tailscale": {"enabled": false, "state": "off", "ip": "", "hostname": ""},
  "ota": {"state": "idle", "progress": 0, "repo": "owner/repo", "latest_url": "https://github.com/owner/repo/releases/latest/download/esphid-s3-n8r8.bin"}
}
```

`wifi.mode` ∈ `off`, `sta`, `ap`, `apsta`. `tailscale.state` ∈ `off`, `connecting`,
`connected`, `error`.

### 7.3 Config object

```json
{
  "name": "ESPHID-A1B2C3",
  "radio_mode": "both",
  "wifi": {"ssid": "Home", "password_set": true, "hostname": "esphid-a1b2c3", "ap_fallback": true},
  "https": {"enabled": false, "port": 443},
  "mqtt": {"enabled": false, "uri": "mqtts://broker.example:8883", "username": "",
           "password_set": false, "client_id": "", "topic_prefix": "esphid/A1B2C3",
           "require_token": true},
  "tailscale": {"enabled": false, "auth_key_set": false, "control_url": "", "hostname": "esphid-a1b2c3"},
  "keyboard": {"layout": "us", "type_delay_ms": 0}
}
```

String limits: `name` 1–63; hostnames 1–63 letters, digits and `-` (not at either end);
`wifi.ssid` ≤ 32; `wifi.password` empty (open network), 8–63 characters, or a 64-digit hex
PSK; `mqtt.topic_prefix` 1–128 without `+`/`#`; `mqtt.uri` empty or `mqtt://`, `mqtts://`,
`ws://`, `wss://`; other strings ≤ 255 bytes.

Secrets are **write-only**: send `wifi.password`, `mqtt.password`, `tailscale.auth_key` in
`config.set`; `config.get` returns only the `*_set` booleans. An empty string clears a
secret. Unknown keys are rejected with `400`. Validation errors are `400` with a message
naming the field.

---

## 8. MQTT

The device is an MQTT 3.1.1 client (`mqtt://`, `mqtts://`, `ws://`, `wss://` URIs;
`mqtts` verified against the built-in CA bundle). Prefix `p` = `config.mqtt.topic_prefix`
(default `esphid/<device_id>`).

| Topic | Direction | QoS | Payload |
|---|---|---|---|
| `p/hid` | → device | 0 | Binary HID packet (prefixed with `AUTH` when `require_token`) |
| `p/hid/json` | → device | 0 | JSON commands §3 (with top-level `"token"` when `require_token`) |
| `p/rpc/req` | → device | 0 | JSON-RPC request §7 |
| `p/rpc/res` | ← device | 0 | JSON-RPC response (or the request's `reply_to` topic) |
| `p/events` | ← device | 0 | Binary event frames §2.2 |
| `p/status` | ← device | 1, retained | `{"online": true, ...public info}`; LWT `{"online": false}` retained |

QoS 0 everywhere on the hot path. Keepalive 30 s. Clean session.

---

## 9. OTA

- Dual OTA partitions with bootloader rollback. A new image is marked valid once it has
  booted, brought up USB HID and at least one transport. If it crashes first, the
  bootloader rolls back.
- `ota.start {url}` downloads over HTTP or HTTPS (built-in CA bundle; redirects followed,
  which GitHub release downloads need). Optional `sha256` (hex) is checked before the
  image is activated. The image's chip and project name must match.
- `POST /api/ota/upload` streams a `.bin` from the web UI.
- Release assets (GitHub Releases) per firmware variant `<v>`:
  - `esphid-<v>.bin` — app image for OTA
  - `esphid-<v>-factory.bin` — merged image for a first flash at offset `0x0`
  - `manifest-<v>.json` — ESP Web Tools manifest
  - `SHA256SUMS-firmware.txt` — checksums of all firmware assets
- The device's default update URL is
  `https://github.com/<repo>/releases/latest/download/esphid-<v>.bin` (`info.ota.latest_url`).
  Apps and the web UI compare `info.fw_version` against
  `GET https://api.github.com/repos/<repo>/releases/latest` (`tag_name`, leading `v`
  stripped) to offer updates.

Firmware variants (v1):

| Variant | Flash | PSRAM | Notes |
|---|---|---|---|
| `s3-n8` | 8 MB | none | Works on any S3 with ≥ 8 MB flash. No Tailscale (not enough RAM). |
| `s3-n8r2` | 8 MB | 2 MB quad | e.g. DevKitC-1 N8R2 |
| `s3-n8r8` | 8 MB+ | 8 MB octal | e.g. DevKitC-1 N8R8 / N16R8. Recommended for Tailscale. |

---

## 10. USB

- TinyUSB device, full speed. Interface 0: boot keyboard (8-byte report: modifiers,
  reserved, 6 keys; LED output report). Interface 1: report-ID HID with mouse (ID 1:
  5 buttons, i16 x, i16 y, i8 wheel, i8 AC pan), consumer control (ID 2: u16 usage),
  absolute pointer (ID 3: 5 buttons, u16 x, u16 y in 0..32767, i8 wheel).
- `bInterval = 1` on both IN endpoints.
- Note: on the ESP32-S3 the USB-OTG peripheral shares the PHY with USB-Serial-JTAG. While
  the HID firmware runs, logs go to UART0, and flashing over the native USB port needs the
  ROM download mode (hold BOOT while plugging in).

# ESPHID

Turn an ESP32-S3 board into a USB keyboard and mouse that your phone drives — over
Bluetooth LE, your WiFi network, Tailscale from anywhere, or an MQTT broker. Built for low
latency: binary packets on persistent connections, 1 kHz USB polling, and pointer motion
that is coalesced without ever being dropped.

```
 iPhone / Android app ──BLE GATT (write w/o response)──┐
 web console / curl  ──WebSocket · HTTP(S) · Tailscale─┼──▶ ESP32-S3 ──USB HID (1 kHz)──▶ computer
 home automation     ──MQTT (your broker)──────────────┘   keyboard · mouse · media keys
```

| Part | Path | Stack |
|---|---|---|
| Firmware | [`firmware/`](firmware) | ESP-IDF 6.0.2 (C), TinyUSB, NimBLE, esp_http_server, esp-mqtt, MicroLink (Tailscale) |
| On-device web console | [`firmware/main/web/index.html`](firmware/main/web/index.html) | Single offline page served from flash |
| iOS app | [`ios/`](ios) | Swift, SwiftUI, CoreBluetooth, Network.framework — no third-party packages |
| Android app | [`android/`](android) | Kotlin, Jetpack Compose, OkHttp, kotlinx.serialization |
| Web flasher | [`flasher/index.html`](flasher/index.html) | ESP Web Tools on GitHub Pages |
| Wire protocol | [`docs/PROTOCOL.md`](docs/PROTOCOL.md) | The contract every part implements |

## Hardware

Any ESP32-S3 board with the native USB port broken out (most DevKitC-1 style boards, XIAO
ESP32S3, etc.) and at least 8 MB of flash. Pick the firmware variant by the module marking
on the metal shield:

| Variant | Module marking | Notes |
|---|---|---|
| `s3-n8r8` | N8R8, N16R8, N32R8 | Octal PSRAM. Recommended; required for Tailscale. |
| `s3-n8r2` | N8R2 | Quad PSRAM. Tailscale supported. |
| `s3-n8` | N8, N16 (no `R`) | No PSRAM. Everything except Tailscale. |

The board's **USB** (OTG) port goes to the computer you want to control. While ESPHID runs,
that port is a keyboard/mouse, so logs go to UART0 and re-flashing over that port needs the
ROM download mode (hold **BOOT** while plugging in).

## Quick start

1. **Flash.** Open the web flasher (see *Releases* below) in Chrome or Edge, pick your
   variant and click *Connect and install*. Or with esptool:
   ```sh
   esptool --chip esp32s3 write-flash 0x0 esphid-s3-n8r8-factory.bin
   ```
2. **Set up.** Plug the board into the computer to control, then either
   - open the ESPHID app — the device appears under *Nearby* over Bluetooth; tap **Set up**
     to choose an access key, join WiFi and pick a radio mode, or
   - join the `ESPHID-XXXXXX` WiFi network from any phone or laptop; the setup page opens
     (or browse to `http://192.168.4.1`).
3. **Use it.** The app's trackpad: drag to move, tap to click, two-finger tap for right
   click, two fingers to scroll, press-and-hold then move to drag. Tap *Keyboard* to type;
   a hardware keyboard on an iPad or Android tablet is passed through key-for-key. The web
   console at `http://esphid-xxxxxx.local/` has the same trackpad, plus a *Capture* mode
   that forwards your desktop mouse and keyboard.

**Factory reset:** hold BOOT for 10 seconds, or use *Factory reset* in the app or console.

## Choosing a connection

| Connection | Typical use | Notes |
|---|---|---|
| Bluetooth LE | Phone in the same room | Lowest latency. Encrypted (LE Secure Connections). |
| WiFi (WebSocket) | Same network, or via Tailscale | One persistent socket with `TCP_NODELAY`. |
| HTTP | Scripts, shortcuts, curl | One request per command — fine for macros, slow for a trackpad. |
| MQTT | Home automation, remote control through a broker | Latency depends on your broker. |

**Radio mode.** The ESP32-S3 has one 2.4 GHz radio. With WiFi and Bluetooth both on, it
time-slices between them and WiFi power save is mandatory, which adds latency and jitter to
both. For the fastest feel pick **WiFi only** or **Bluetooth only** (setup asks; change it
later under *Device connections*). If you lock yourself out (e.g. Bluetooth only with no
phone), factory reset.

**Tailscale.** On PSRAM variants the device joins your tailnet itself (MicroLink client):
create an auth key in the Tailscale admin console → *Settings → Keys*, paste it under
*Tailscale* in the app or console, restart. The device is then reachable at its `100.x`
address or MagicDNS name; add it in the app with **+ → Address or Tailscale name**.
Alternatively, any Tailscale subnet router on the same LAN makes the device's LAN address
reachable with no firmware involvement.

## Scripting

Every command needs the access key. Key and consumer names are listed in
[PROTOCOL §3](docs/PROTOCOL.md#3-json-hid-commands-scripting-convenience).

```sh
DEV=http://esphid-a1b2c3.local TOKEN=your-access-key

# Type text, then press Ctrl+Alt+Delete
curl -X POST "$DEV/api/hid" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"commands":[{"t":"text","s":"hello world\n"},{"t":"tap","k":"delete","mods":["ctrl","alt"]}]}'

# Binary packet: move the mouse 10 right, 5 up
printf '\x01\x0a\x00\xfb\xff' | curl -X POST "$DEV/api/hid" -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/octet-stream' --data-binary @-

# Device info and config over JSON-RPC
curl -X POST "$DEV/api/rpc" -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
  -d '{"id":1,"method":"config.get"}'

# MQTT (with require_token on, the default)
mosquitto_pub -h broker.example -t esphid/A1B2C3/hid/json \
  -m '{"token":"your-access-key","commands":[{"t":"consumer","u":"play_pause"}]}'
```

## Security model

- **Claiming.** A new device accepts no HID input until it is claimed with an access key
  (16–64 characters; the apps generate 32 random ones). After that every transport needs
  the key; it is stored in NVS, compared in constant time, never logged or returned.
  Repeated failures lock that transport out for 30 s.
- **Bluetooth** uses LE Secure Connections bonding; the access key is sent per connection.
- **HTTPS** (optional) uses a certificate generated on the device; the apps pin its SHA-256
  fingerprint on first use. Over Tailscale, plain HTTP is already end-to-end encrypted.
- **MQTT**: broker credentials, TLS and ACLs are the first line; additionally every message
  must carry the access key unless you turn *Require access key* off.
- **Held input is released** the moment a Bluetooth or WebSocket client disconnects
  (including a phone that silently drops off WiFi, detected within 20 s), 10 s after the
  last HTTP/MQTT command, and whenever the apps go to the background.
- **App storage**: the access key lives in the iOS Keychain / an Android Keystore–encrypted
  store, never in plain preferences.
- The setup hotspot is open, but setup only works while the device is unclaimed; once
  claimed, every change needs the key.

## Building from source

**Firmware** (ESP-IDF 6.0.2):
```sh
firmware/tools/build.sh s3-n8r8          # → firmware/build/s3-n8r8/dist/
cmake -S firmware/test/host -B firmware/build/host && cmake --build firmware/build/host \
  && ctest --test-dir firmware/build/host --output-on-failure   # protocol host tests
```
`build.sh` activates an installed IDF if needed. `ESPHID_VERSION` and `ESPHID_OTA_REPO`
(e.g. `owner/repo`, enables in-app update checks) are read from the environment.

**iOS** (Xcode 16 or newer, XcodeGen; iOS 17+):
```sh
cd ios/ESPHIDKit && swift test
cd ios && xcodegen generate && open ESPHID.xcodeproj
```

**Android** (JDK 17, Android SDK 36):
```sh
cd android && ./gradlew :core:testDebugUnitTest :app:assembleDebug
```

## CI/CD and releases

Three workflows in [`.github/workflows`](.github/workflows) run on **every push and pull
request**, and on **published releases**:

| Workflow | On push / PR | On release |
|---|---|---|
| `firmware.yml` | Host tests; builds all three variants in `espressif/idf:v6.0.2`; uploads binaries as artifacts | Attaches `esphid-<variant>.bin` (OTA), `esphid-<variant>-factory.bin` (first flash), `manifest-<variant>.json` and `SHA256SUMS-firmware.txt`; optionally deploys the web flasher to GitHub Pages |
| `ios.yml` | Swift package tests; simulator build + tests; uploads the simulator app | Attaches `esphid-ios-simulator-<v>.zip`; with credentials, archives a signed build, attaches `esphid-ios-<v>.ipa`, uploads to TestFlight and optionally submits for review |
| `android.yml` | Unit tests, lint, debug APK | Attaches `esphid-android-<v>.apk` / `.aab` (suffixed `-unsigned` without a keystore); with credentials, uploads to Google Play |

To release: create a GitHub release with a tag like `v1.2.0` and publish it. The version
comes from the tag; build numbers come from the workflow run number. A release marked
**pre-release** (e.g. `v1.2.0-rc.1`) builds and attaches the same files, but goes only to
TestFlight (never review) and the Play *internal* track, skips the web flasher, and is not
offered to devices as an update. On iOS it ships as version `1.2.0`. Devices update from
`https://github.com/<repo>/releases/latest/download/esphid-<variant>.bin`, and the apps
offer the update when the release is newer than the installed firmware.

**Store submission is optional** — each step is skipped (and the job stays green) when its
secrets are missing.

| Name | Kind | Purpose |
|---|---|---|
| `ASC_KEY_ID`, `ASC_ISSUER_ID`, `ASC_KEY_P8_BASE64` | secrets | App Store Connect API key (App Manager role). Used for cloud-managed signing and TestFlight upload. |
| `APPLE_TEAM_ID` | secret or variable | Your Apple developer team ID |
| `IOS_BUNDLE_ID` | variable | Defaults to `dev.scalexy.esphid` |
| `IOS_SUBMIT_FOR_REVIEW` | variable | `true` also submits the build for App Store review |
| `IOS_BUILD_NUMBER_OFFSET` | variable | Added to the run number |
| `ANDROID_KEYSTORE_BASE64`, `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` | secrets | Release signing |
| `PLAY_SERVICE_ACCOUNT_JSON` | secret | Google Play service account with release access |
| `PLAY_TRACK` / `PLAY_RELEASE_STATUS` | variables | Default `internal` / `completed` |
| `ANDROID_VERSION_CODE_OFFSET` | variable | Added to the run number |
| `ENABLE_PAGES_FLASHER` | variable | `true` deploys the web flasher (set *Settings → Pages → Source* to *GitHub Actions*) |

Store prerequisites the workflows can't do for you: create the app record (matching bundle
ID / application ID) in App Store Connect and the Play Console, complete store listings,
and upload the very first Android bundle by hand (Google Play requires it before API
uploads are accepted).

## Repository layout

```
docs/PROTOCOL.md             wire protocol, test vectors, API — start here
firmware/                    ESP-IDF project (main/, components/esphid_proto, variants/, tools/, test/host)
firmware/main/web/           on-device web console
ios/ESPHIDKit                Swift package: protocol, transports, discovery, storage (API.md)
ios/ESPHID                   SwiftUI app
android/core                 Kotlin library: protocol, transports, discovery, storage (API.md)
android/app                  Compose app
flasher/                     GitHub Pages web flasher
.github/workflows/           CI/CD
```

## Status

Everything builds and the protocol test vectors pass in all four implementations
(firmware, Swift, Kotlin, web console). Behaviour on real hardware — USB enumeration across
operating systems, Bluetooth bonding and throughput, measured end-to-end latency, Tailscale
on a live tailnet — has not been verified yet.

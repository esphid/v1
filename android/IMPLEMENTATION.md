# Android implementation handoff

Implemented only `android/**` and `.github/workflows/android.yml`. No state-changing
git command was run. Firmware, iOS, root .gitignore and docs/PROTOCOL.md were not edited.
`android/local.properties` points to the local SDK and is already ignored.

## Versions

Gradle 9.3.1, AGP 9.1.1, built-in Kotlin 2.2.10 (matching serialization and Compose
compiler plugins), Compose BOM 2025.12.01, coroutines 1.10.2, kotlinx.serialization
JSON 1.9.0, OkHttp 4.12.0. JDK 17; compileSdk/targetSdk 36, minSdk 26.
AGP compatibility: https://developer.android.com/build/releases/agp-9-1-0-release-notes
Compose's August 2026 BOM required API 37 during verification, so the pinned set
preserves API 36. Additional app dependencies are AndroidX activity-compose 1.11.0
and Google Material 1.13.0 (required XML Material3 DayNight base theme). JUnit 4.13.2
is test-only. No extra runtime library was added to core beyond the requested set.

## Transport design and API

See [core/API.md](core/API.md) for the full public surface, examples, threading,
flow control, batching, coalescing, persistence, TLS policy and hardware limitations.

- BLE: HandlerThread and coroutine mutex serialize GATT operations. Two notification
  CCCDs precede RPC. HID no-response writes wait for callbacks; pending consecutive
  motion sums while occupied. Requests use MTU 517, high priority and preferred 2M.
  RPC has one in-flight request, 8 KB FIRST/LAST reassembly and ten-second timeout.
- WebSocket: OkHttp binary HID/text correlated RPC, TCP_NODELAY on plain and TLS
  sockets, pinned leaf DER certificate for self-signed HTTPS/WSS, protocol pings.
  Waits for OkHttp's queue to drain while later input coalesces.
- HTTP: pooled persistent OkHttp connections, one HID request at a time; subsequent
  motion coalesces while the request is active. RPC uses the management endpoint.
- MQTT: handwritten 3.1.1 codec and raw sockets; IO reader reassembles fragmented
  packets; synchronized output preserves whole packets; QoS0, clean session,
  broker credentials/TLS, unique reply topic and token-prefixed HID. Local socket
  broker test verifies a full RPC/HID/event round trip.

The requested API names exist. Source is top-level. Model properties mirror wire
snake_case; ConfigSetResult.rebootRequired has the appropriate serialization alias.
No UI exists beyond PlaceholderRoot in MainActivity. No `ui/` directory was created.

## Validation

Nine JVM tests passed, zero skipped/failing. Every §2.5 vector is asserted, with
UTF-8 boundaries, multiple commands, BLE RPC chunks, MQTT codec/stream tests,
protocol JSON fixtures, RPC success/error/request handling, coalescer, acceleration,
versions, token charset/length and certificate formatting. `actionlint` accepted the
workflow (including YAML parsing) with no output. Lint completed with zero errors
and 13 advisory warnings (version updates, cleartext/pinned TLS, platform attributes
and default placeholder resources). The wrapper JAR is present.

The following commands were invoked through the local wrapper with `-p android`
from the repository root and JAVA_HOME set to the installed JDK 17, equivalent to
running them from android/.

`./gradlew :core:testDebugUnitTest :app:assembleDebug lint` — exact final tail:

```text
Wrote HTML report to file:///Users/athul/Workspace/ESPHID/v1/android/app/build/reports/lint-results-debug.html

> Task :app:lintDebug
> Task :app:lint

BUILD SUCCESSFUL in 4s
93 actionable tasks: 28 executed, 65 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
```

`./gradlew :app:assembleRelease` — exact final tail:

```text
> Task :app:optimizeReleaseResources
> Task :app:packageRelease
> Task :app:createReleaseApkListingFileRedirect UP-TO-DATE
> Task :app:assembleRelease

BUILD SUCCESSFUL in 10s
82 actionable tasks: 32 executed, 50 up-to-date
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.3.1/userguide/configuration_cache_enabling.html
```

Release APK is unsigned when signing credentials are absent; R8 and resource
shrinking are enabled. Debug APK: `app/build/outputs/apk/debug/app-debug.apk`.
Release APK: `app/build/outputs/apk/release/app-release-unsigned.apk`.

## CI credentials and variables

Secrets: ANDROID_KEYSTORE_BASE64, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS,
ANDROID_KEY_PASSWORD, PLAY_SERVICE_ACCOUNT_JSON.
Variables: ANDROID_VERSION_CODE_OFFSET (0), PLAY_TRACK (internal),
PLAY_RELEASE_STATUS (completed; draft allowed).
Signing is configured only when the workflow has all four signing secrets. Play
requires the signing keystore plus its service-account secret; missing secrets skip
cleanly. Release assets have canonical versioned names; unsigned builds also attach
an explicitly `-unsigned.apk` copy and report that status in the job summary.
The upload-google-play action avoids adding a publishing plugin to local Gradle
builds. Live publishing and release upload have not been exercised with credentials.

Local manual signing uses ANDROID_KEYSTORE_PATH plus the three password/alias env
vars. Local version overrides use -PversionName=... -PversionCode=....

## Root .gitignore

No additional lines are necessary. Existing rules already cover:

```gitignore
android/.gradle/
android/build/
android/*/build/
android/local.properties
android/.kotlin/
*.keystore
*.jks
play-service-account.json
```

## Protocol observations and hardware follow-up

No protocol edits were needed. HTTP HID returns 204 and has no event subscription,
so live LEDs/RTT cannot be supplied by that transport; RTT/USB state remain null.
The protocol suggests 15-second WebSocket pings; this task explicitly requests two
seconds, which the implementation follows. BLE advertisements carry no identity,
claimed state or firmware TXT equivalents, so those discovery fields are null until
RPC is queried. Unclaimed BLE setup is supported through DeviceSessions.

Physical ESP32-S3 plus Android tests are still required for bonding prompts, status
133 retry behavior, vendor no-response callbacks, negotiated PHY/MTU, actual USB
key/button ordering, mDNS on real routers, pinned certificates, MQTT ACLs/reconnect
behavior, and latency measurement. No automatic reconnect is exposed: callers own
retry policy via connect. MQTT broker connection alone does not prove the target
device is online. No hardware latency claim is made. CI's GitHub release upload and
Google Play publishing need account-authorized live validation.

## Files created

```text
android/app/build.gradle.kts
android/app/src/main/AndroidManifest.xml
android/app/src/main/kotlin/dev/scalexy/esphid/MainActivity.kt
android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml
android/app/src/main/res/values/strings.xml
android/app/src/main/res/values/themes.xml
android/app/src/main/res/xml/network_security_config.xml
android/build.gradle.kts
android/core/API.md
android/core/build.gradle.kts
android/core/consumer-rules.pro
android/core/src/main/AndroidManifest.xml
android/core/src/main/kotlin/dev/scalexy/esphid/core/Ble.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Connection.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/DeviceStore.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Discovery.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Helpers.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Models.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Mqtt.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Network.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Protocol.kt
android/core/src/main/kotlin/dev/scalexy/esphid/core/Rpc.kt
android/core/src/test/kotlin/dev/scalexy/esphid/core/CoreTest.kt
android/core/src/test/kotlin/dev/scalexy/esphid/core/MqttTransportTest.kt
android/core/src/test/resources/config.json
android/core/src/test/resources/info.json
android/gradle/wrapper/gradle-wrapper.jar
android/gradle/wrapper/gradle-wrapper.properties
android/gradle.properties
android/gradlew
android/gradlew.bat
android/settings.gradle.kts
.github/workflows/android.yml
```

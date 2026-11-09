# ESPHID firmware implementation report

ESP-IDF 6.0.2, target ESP32-S3. This report records build and host validation;
there was no attached device, so it does not certify hardware behavior or measured latency.
No commits, pushes, or state-changing Git commands were run.

## Source tree

```text
firmware/
  .clang-format
  IMPLEMENTATION.md
  CMakeLists.txt
  sdkconfig.defaults
  partitions.csv
  dependencies.lock
  main/
    CMakeLists.txt, Kconfig.projbuild, idf_component.yml, esphid.h
    app_main.c          startup, service changes, BOOT/reset/reboot, optional LED
    hid.c               descriptors, keyboard state, core-1 report worker
    config.c            NVS schema, validation, tokens, transport lockout
    rpc.c               common dispatcher, info, command admission, event fanout
    ble.c               encrypted NimBLE GATT, sessions, RPC fragmentation
    net.c               STA/SoftAP, captive DNS, mDNS, SNTP
    http.c              HTTP/HTTPS routes, WebSocket sessions and framing
    mqtt.c              MQTT topics, auth, LWT, status, fragment reassembly
    tailscale.c         MicroLink lifecycle and status
    tls.c               EC P-256 certificate generation and atomic NVS storage
    ota.c               streamed upload, advanced HTTPS OTA, hashing, rollback
    web/index.html      owned externally; embedded without editing it
  components/esphid_proto/
    include/esphid_proto.h
    proto.c, json.c, layout.c, safety.c, CMakeLists.txt
  variants/{s3-n8,s3-n8r2,s3-n8r8}/sdkconfig.defaults
  tools/build.sh
  test/host/{CMakeLists.txt,test.c,README.md}
  build/<variant>/dist/{esphid-<variant>.bin,
                         esphid-<variant>-factory.bin,manifest-<variant>.json}
.github/workflows/firmware.yml
```

The original implementation included `firmware/**` and `.github/workflows/firmware.yml`.
The audit-fix pass described below edits only `firmware/**`; `main/web/index.html`
and all other owners' paths are untouched. Generated managed
components and build outputs live below `firmware/`.

## Execution and latency

| Work | Priority | Core | Stack |
|---|---:|---:|---:|
| TinyUSB device task | 22 | 1 | 4096 B |
| HID command/report worker | 21 | 1 | 4096 B |
| NimBLE host | 21 | 0 | 8192 B |
| HTTP and optional HTTPS server | 16 | 0 | 8192 B each |
| MQTT client | 18 | IDF scheduler | 8192 B |
| BLE RPC worker | 4 | IDF scheduler | 8192 B |
| MQTT slow RPC worker | 4 | IDF scheduler | 8192 B |
| HTTP slow RPC / upload workers (2) | 4 | IDF scheduler | 8192 B each |
| WebSocket idle watchdog | 5 | IDF scheduler | 3072 B |
| Event fanout | 5 | IDF scheduler | 4096 B |
| Management / BOOT / LED | 4 | IDF scheduler | 8192 B |
| Captive DNS / AP fallback | 3 | IDF scheduler | 4096 B |
| OTA download, when running | 3 | IDF scheduler | 12288 B |
| MicroLink network IO | 7 | 0 | 8192 B |
| MicroLink DERP TX | 5 | 0 | 14336 B |
| MicroLink coordinator | 5 | 1 | 12288 B |
| MicroLink WireGuard manager | 7 | 1 | 8192 B |

The transport receive callback decodes directly into a fixed command structure,
authenticates/admit-checks, then makes one bounded `xQueueSend` copy into the HID
queue. BLE must flatten its mbuf chain once; HTTP/WS receive into their bounded
request/frame buffer; an unfragmented MQTT binary payload is parsed directly from
the MQTT library's receive buffer. There is no forwarding task between a receive
callback and the HID worker. Fragmented management messages have their necessary
reassembly buffers.

The queue has 64 entries of 288 B = 18,432 B of command storage. It can hold
16,320 text payload bytes, exceeding the 4096-byte requirement. Queue overflow is
reported, never silently accepted. PING is answered in the receive context and
never enters the HID queue. Keyboard changes wait for the keyboard endpoint and
are individually submitted. Mouse deltas accumulate in signed 64-bit counters;
16/8-bit report clamping carries the remainder. Completion callbacks wake the HID
worker; it flushes pending mouse motion on endpoint readiness and before a button
change. Boot keyboard rollover is represented while more than six keys are held.

Both USB IN endpoints use `bInterval=1`; keyboard has no report ID, the second
interface uses IDs 1/2/3. USB suspend requests remote wakeup when input arrives.
GPIO0 is sampled every 50 ms outside the HID path. UART0 is the only console.
WiFi and lwIP use IRAM optimization, 240 MHz CPU, QIO/80 MHz flash, performance
optimization, and a 1 kHz RTOS tick. Accepted HTTP/TLS/WS sockets and the connected
MQTT transport use TCP_NODELAY. Radio selection is applied at boot, with no BLE
controller initialization in `wifi`, and no WiFi startup in `ble`.

## Confirmed audit fixes (current behavior)

These changes supersede earlier validation output and timeout descriptions below.
Wire formats and method names are unchanged. The 20-second WS timeout is the
requested behavior change relative to the older protocol prose about idle sockets.

- **Finding 1 — disappearing WS peers:** accepted plain/TLS sockets enable
  `SO_KEEPALIVE`, `TCP_KEEPIDLE=5`, `TCP_KEEPINTVL=2`, `TCP_KEEPCNT=3` alongside
  `TCP_NODELAY`. IDF 6.0.2's `components/lwip/port/include/lwipopts.h` explicitly
  defines `LWIP_TCP_KEEPALIVE=1`; its socket header supplies all three options in
  seconds/counts. A dedicated priority-5 watchdog queues checks every second onto
  each owning httpd task. After 20 seconds without a received frame, an authenticated
  WS session requests an immediate HID abort and shuts down its socket; the next
  server receive observes EOF, closes it and runs `ws_free` → `session_ended`.
  This uses socket shutdown on the owning server task, avoiding nested
  `httpd_sess_trigger_close` control work. Binary/text/continuation and WS control
  frames refresh activity; WS PING/PONG/CLOSE handling preserves interleaved fragments.
  Clients' normal 2-second PINGs keep the session alive. A continuously live BLE/WS
  session can hold input indefinitely. Unauthenticated WS sessions must authenticate
  within 10 seconds of opening; incoming PINGs do not extend that deadline. Checks run at a one-second cadence and
  execute when the owning httpd task next services its control queue.
- **Finding 2 — guaranteed preemptive release:** `session_ended` and session/token
  invalidation request an atomic abort generation and notify the core-1 HID worker.
  This path uses no command queue slot. The worker checks cancellation during USB
  endpoint waits, each TEXT stroke, and notification-based DELAY/type-delay waits.
  Unrelated USB-completion or enqueue notifications do not shorten a requested
  delay. Abort resets the shared queue and pending motion, cancels the current
  expansion/wait, clears ownership/state, and sends zero keyboard, relative mouse,
  consumer and (if used) absolute mouse button reports. Absolute release preserves
  the last absolute coordinates. **An abort intentionally drops every client's
  queued commands and releases every client's held input**, favoring host safety
  over preserving concurrent scripts. New requests during release are observed on
  the next worker iteration. USB delivery still requires a mounted host that polls
  its IN endpoints; remote wakeup is requested while suspended.
- **Finding 3 — stateless holds:** each admitted HID command tags the existing
  queue item's padding byte with its transport; parser callbacks are mutable solely
  to add that internal metadata. The queue remains 64 × 288 bytes and admission
  still makes exactly one nonblocking queue copy, with no new mutex. HTTP/MQTT
  admission updates 32-bit millisecond timestamps in explicit internal DRAM only
  after a successful queue send; queue-full failures cannot prolong a hold.
  Abort, source-release requests, timestamps and keyboard caches use C11 atomics in `DRAM_ATTR` storage;
  Timestamp loads/stores compile to direct word access with memory barriers in
  all three builds. The PSRAM abort-increment helper selects its hardware-atomic
  branch for internal addresses, so admission acquires no new spinlock. The HID worker owns
  per-key/modifier/button/consumer provenance and checks it at most every 100 ms,
  including during waits and text. If input owned by HTTP or MQTT remains held for
  10 seconds without another admitted HID command from that same transport, it
  releases only that source's keys, modifiers, buttons and consumer usage. The
  worker rebuilds boot keyboard state (including rollover), mouse buttons and
  consumer reports from the remaining provenance. Relative and previously used
  absolute mouse reports preserve other sources' buttons and absolute coordinates.
  This path preserves the queue and pending motion; another source's in-progress
  TEXT/DELAY continues. A release may cancel the current command of the released
  source, and temporary CLICK state cannot restore an expired source's buttons.
  Endpoint readiness is rechecked after servicing source releases, since those
  reports can consume the endpoint. TEXT/TAP key-up provenance is updated before
  waiting, and source release sends the resulting state even when ownership is
  already clear, covering a cancelled key-up still pending USB submission.
  PING/auth/state RPC traffic does not keep a stateless hold alive.
  Traffic from another transport cannot extend its deadline. A live transport
  overwriting an input takes ownership; its holds never expire through this
  watchdog. MQTT disconnect and client reconfiguration atomically request MQTT-only
  release and notify the HID worker, without queueing a command or taking a new
  hot-path lock. Authenticated BLE/WS session end and session/token invalidation
  retain the global preemptive abort described above.
- **Finding 4 — slow RPC:** BLE GATT only reassembles and queues a completed RPC;
  a dedicated priority-4 task dispatches it. One request remains in flight through
  queueing/execution and the existing one-chunk-at-a-time notify sequence.
  RPC dispatch borrows a session snapshot and releases the BLE mutex throughout
  dispatch, including JSON serialization and configuration/scan/certificate work.
  Short event/admission callbacks check a monotonically assigned connection
  generation under the existing mutex. HID admission commits authentication before
  enqueueing under the same disconnect guard, so either disconnect releases the
  admitted input or stale admission is rejected. Auth state and chunked responses
  are also published only to that generation; session/token changes preserve the
  initiating live session's identity. Disconnect/reconnect frees the old state
  without exposing the replacement connection to stale work. HTTP `/api/rpc` and
  `/api/wifi/scan` offload slow methods using
  `httpd_req_async_handler_begin`/`_complete`; WS text slow RPC uses the same bounded
  worker queue, then queues a generation-checked `httpd_ws_send_frame_async` reply
  on the owning server task. Fast HTTP/WS methods stay inline. Two priority-4 HTTP
  workers share a four-job queue; overflow returns HTTP 503, or closes a WS session
  safely. Concurrent scans return 503 promptly instead of sharing the WiFi driver's
  scan-result buffer. MQTT also queues slow RPC to a dedicated priority-4 worker
  with a two-job queue, broker-generation checks, and nonblocking outbox enqueue
  for its reply. Client reconfiguration serializes worker reply enqueue against
  client destruction. The scan's synchronous driver wait exists only on workers,
  never on NimBLE/httpd/MQTT receive tasks.
- **Finding 5 — streamed OTA:** authenticated uploads immediately reserve an async
  HTTP request and transfer it to an HTTP worker. Reading, flashing, validation and
  the existing OTA-status JSON/status-code response all execute there. Every async
  request completes on success and failure; failed/partial uploads shut down the
  socket so unread image bytes cannot become another HTTP request. HTTPS restart
  is deferred while async requests or queued WS work retain its server handle.
  `CONFIG_HTTPD_QUEUE_WORK_BLOCKING=y` makes control work reliable under mailbox
  load. Only workers, event fanout and the watchdog enqueue such work, outside the
  WS registry mutex; transport receive callbacks never wait for this mailbox.
- **Finding 9 — public authentication:** route/method selection precedes HTTP header
  authentication. `info`, `claim`, and unclaimed `config.get`/`config.set`/`wifi.scan`
  use optional token comparison: invalid/malformed headers leave the request
  unauthenticated without changing lockout counters or rejecting it. Public RPC
  envelope tokens behave the same (MQTT's explicit per-message token policy stays
  enforced). Protected methods and WS upgrade keep their existing rejection and
  lockout behavior, and valid public headers still enable full info.
- **Finding 10 — layout:** configuration initialization and successful updates
  cache a static layout-table pointer and type delay in C11 atomics. TEXT loads the
  pointer once per command, falling back to US when the stored layout is unknown;
  no JSON/NVS/config mutex is used on the HID path. Currently US is the only table.
- **Finding 11 — BLE UTF-8 name:** scan response and GAP name both use a portable
  UTF-8 prefix helper that backs off from a 29-byte limit to a scalar boundary;
  the advertising complete-name flag reflects whether truncation actually occurred.
  The advertising name is cached outside the BLE mutex, so GAP callbacks never
  read configuration JSON or wait for its mutex.

New portable tests cover every UTF-8 cut position around 2/3/4-byte characters,
held-input ownership and takeover across transports, key/modifier/button/consumer
release and report replacement, deadline renewal, the exact 10-second threshold,
32-bit timestamp wrap, source-only release reports (including mixed ownership,
consumer usage, idempotence and recovery from boot rollover), and early cancellation
of TEXT expansion. The host suite
runs with warnings-as-errors, ASan and UBSan.

### Confirmed re-audit fixes

The re-audit additionally fixes these transport/lifetime issues:

- **MQTT handle lifetime (1):** the shared `events` task calls `mqtt_events`, which
  takes `client_mutex` before checking `client`/`connected`, reading the topic prefix
  and publishing. Management's `mqtt_apply` uses the same mutex for stop/destroy.
  In contrast, MQTT-task `message` → `command_error`/AUTH/PING →
  `mqtt_task_send_event` deliberately does not take that mutex: stop waits for the
  MQTT task while holding it, so locking the callback would deadlock. Both explicit
  entry paths share `publish_event_unlocked`; the handle stays live on MQTT-task
  callbacks until stop returns. The slow RPC worker retains its existing guarded
  outbox enqueue.
- **Release scope and admission (2, 5):** the source-only hold expiry/disconnect and
  successful-enqueue timestamp rules above replace the earlier global stateless
  abort behavior. BLE/WS authenticated session end remains global and preemptive.
- **WebSocket slot admission (3):** in IDF 6.0.2, `httpd_parse.c` detects an actual
  WebSocket upgrade and `httpd_uri.c` invokes `ws_pre_handshake_cb` only for it,
  skipping the normal URI handler after the handshake. Only `websocket_open`,
  registered as the pre-handshake callback, allocates a slot. A plain `GET /ws`
  reaches `websocket` and receives HTTP 426 with `Upgrade: websocket`, allocating
  no slot. The watchdog also closes unauthenticated sessions at 10 seconds from
  opening, independently of their received-frame activity.
- **Bounded socket work (4):** HTTP and HTTPS both configure a one-second send
  timeout and two-second receive timeout. The ordinary body reader retries at
  most three timeout results, with an eight-second overall check (one in-progress
  receive can add up to two seconds). Rejected/failed bodies close the connection
  to avoid IDF's post-handler body purge blocking on the same slow socket. WS event
  and async-RPC sends run on the owning httpd task; failure invokes `ws_close`,
  immediately calls `session_ended`, then shuts down the socket for normal cleanup.
  Inline text/control-frame send failures return an error so httpd closes the
  session and calls `ws_free` → `session_ended`. IDF's "async" WS send is synchronous;
  its header and payload can each consume one send timeout.
  OTA remains on an HTTP worker and uses the same two-second receive timeout. It
  tolerates nine consecutive timeout results, resets the count after every
  successful read, and fails on the tenth (about 20 seconds without progress),
  or a ten-minute overall upload deadline. Both body readers recognize plain
  `HTTPD_SOCK_ERR_TIMEOUT` and raw HTTPS ESP-TLS WANT_READ/WANT_WRITE/TIMEOUT errors:
  IDF's HTTPS receive override does not translate these into HTTPD error codes.
- **TLS startup snapshot (6):** management copies cert, key and fingerprint under
  `config_mutex` into a private heap snapshot before `httpd_ssl_start`. IDF's
  `https_server.c` copies the PEM buffers during that call, after which the snapshot
  is cleared/freed. `served_fingerprint` comes from that same snapshot, so an RPC
  worker's concurrent regeneration cannot mix identities or mark the wrong cert
  as served. Restart comparison and RPC/info fingerprint reads also hold the
  config mutex; credential pointer accessors document this requirement.
- **Binary HTTP HID cap (8):** `/api/hid` JSON retains `ESH_RPC_MAX`; binary bodies
  (including `application/octet-stream`) are limited to `ESH_PACKET_MAX` = 512
  bytes before allocation/receive. Oversized requests receive HTTP 413 and close
  without draining the rejected body on the httpd task.

Re-audit validation logs are in `firmware/build/re-audit/`. No device was attached;
source inspection confirms HTTP/HTTPS receive-timeout retry behavior, but real
slow-WiFi OTA and USB release timing still require hardware validation.

Hardware validation remains required for WiFi blackholes during drags, TCP probe
behavior over WiFi/Tailscale/TLS, USB release timing through full queues and long
scripts, legitimate long BLE/WS holds, BLE reconnection/notification congestion,
parallel scan/upload plus live input, and HTTPS restart during async work. No
measured latency or electrical USB/BLE behavior is claimed by host/build validation.

### Re-audit validation output (final sources)

The required host command sequence and all three `firmware/tools/build.sh`
variants exited 0 under ESP-IDF 6.0.2. Final logs contain zero compiler
`warning:` or `error:` lines, including project code. Host compilation uses
`-Wall -Wextra -Werror`, ASan and UBSan. Symbols place `last_command`,
`release_sources` and `abort_generation` in internal DRAM in every variant.
Disassembly of admission shows the timestamp's direct word store and memory
barriers after the successful queue-send branch, with no added lock/helper call.

Exact host output tail:

```text
[100%] Built target proto_test
Test project /Users/athul/Workspace/ESPHID/v1/firmware/build/host
    Start 1: protocol
1/1 Test #1: protocol .........................   Passed    0.01 sec

100% tests passed, 0 tests failed out of 1

Total Test time (real) =   0.02 sec
```

Exact build output tails:

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x185d00 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist/esphid-s3-n8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8: app=1400064 bytes, OTA slot=4063232 bytes, free=2663168 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist
```

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a5e20 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist/esphid-s3-n8r2-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r2: app=1531424 bytes, OTA slot=4063232 bytes, free=2531808 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist
```

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a6cc0 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist/esphid-s3-n8r8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r8: app=1535168 bytes, OTA slot=4063232 bytes, free=2528064 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist
```

### Earlier audit validation output (superseded by re-audit validation)

Commands run successfully:

```sh
cmake -S firmware/test/host -B firmware/build/host
cmake --build firmware/build/host
ctest --test-dir firmware/build/host --output-on-failure
source ~/.espressif/tools/activate_idf_v6.0.2.sh
firmware/tools/build.sh s3-n8
firmware/tools/build.sh s3-n8r2
firmware/tools/build.sh s3-n8r8
```

All three build processes exited 0. Their final logs contain zero project warnings.
Linked symbols place abort generation, command timestamps, layout and delay cache
in internal DRAM in every variant. Disassembly confirms direct timestamp stores
with `memw` barriers and no added admission lock, including PSRAM builds.

Exact host test tail:

```text
Test project /Users/athul/Workspace/ESPHID/v1/firmware/build/host
    Start 1: protocol
1/1 Test #1: protocol .........................   Passed    0.39 sec

100% tests passed out of 1

Total Test time (real) =   0.39 sec
```

Exact firmware build tails (including app size):

`s3-n8`

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x185650 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist/esphid-s3-n8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8: app=1398352 bytes, OTA slot=4063232 bytes, free=2664880 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist
```

`s3-n8r2`

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a5740 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist/esphid-s3-n8r2-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r2: app=1529664 bytes, OTA slot=4063232 bytes, free=2533568 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist
```

`s3-n8r8`

```text
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a65e0 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist/esphid-s3-n8r8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r8: app=1533408 bytes, OTA slot=4063232 bytes, free=2529824 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist
```

Full current logs: [host](build/validation-audit/host.log),
[host cases](build/validation-audit/host-details.log),
[s3-n8](build/validation-audit/build-s3-n8.log),
[s3-n8r2](build/validation-audit/build-s3-n8r2.log),
[s3-n8r8](build/validation-audit/build-s3-n8r8.log),
[atomic placement and disassembly](build/validation-audit/atomic-check.log).

## Implemented surface

- All binary command opcodes and all five events, including ordered prefix
  application on truncated/unknown packets, queue errors, immediate PONG, LED and
  state events. All JSON HID forms, specified names and aliases, numeric usages,
  table-driven US text, and UTF-8-aware TEXT chunk boundaries.
- Shared JSON-RPC dispatcher for every method in protocol section 7.1, used by all
  four transports. Public/full info split, write-only config secrets and flags,
  a stack-bounded 16-level JSON parser, strict recursive unknown-field rejection, atomic config persistence, and live
  WiFi/service updates. Token comparison always processes padded 65-byte values.
  Failure timestamps are tracked separately for HTTP, WS, BLE, and MQTT. Token
  rotation invalidates epochs and disconnects other BLE/WS sessions while keeping
  the initiating session.
- All HTTP routes, gzip web embedding with a source dependency and `gzip -9 -n`,
  pre-upgrade WS authorization, first-frame AUTH, four total WS sessions across
  HTTP/HTTPS, WebSocket continuation reassembly, and AP-interface-only probe
  redirects. HTTPS uses generated EC P-256 credentials, one NVS blob for the
  cert/key pair, and the colon-delimited uppercase DER SHA-256 fingerprint.
- Encrypted BLE GATT UUIDs from the contract, LESC-only Just Works bonding, eight
  stored bonds and 32 persistent CCCDs, two connections, 517-byte MTU preference, 2M PHY/DLE requests,
  7.5–15 ms connection request, and 20–30 ms advertising. RPC reassembles up to
  8192 bytes and sends one notification chunk at a time, continuing on notify
  completion with a callout retry on temporary allocation/congestion failure.
- STA, open setup AP, captive DNS, 60-second fallback and AP handover timers,
  service discovery TXT records, scan results sorted by RSSI, and SNTP so public
  HTTPS certificate validation can use a synchronized clock.
- All MQTT topics, fragmented receive handling, MQTT 3.1.1 clean sessions,
  keepalive 30, QoS 0 input/events/RPC, retained QoS 1 online status, retained
  offline LWT, custom RPC reply topics, token policy, and built-in CA verification.
- Native MicroLink support on PSRAM variants, optional Headscale host selection,
  status, and reset of its stored identity. See the findings below.
- Advanced OTA progress API with redirects and CA bundle, both HTTP and HTTPS
  URLs, optional complete-file SHA-256, matching project and IDF chip validation,
  streaming raw upload, dual 0x3e0000-byte OTA slots, rollback enabled, and
  mark-valid after USB installation and one transport starts. Reboot is scheduled
  about one second after success. Optional WS2812 LED defaults to GPIO -1/off.
- CI host tests, three-variant build matrix, version/repository injection,
  workflow artifacts, release upload/checksums, optional Pages flasher deployment
  that skips missing HTML, and ref-scoped concurrency cancellation.

## Protocol deviations / proposed clarifications

1. **HTTP binary event response ambiguity (sections 2.1, 6.1).** The contract
   requires immediate PONG/STATE/AUTH events for binary commands on every
   transport, but specifies only a 204 response for HTTP HID and does not define
   an HTTP event channel. Ordinary successful HTTP HID commands return 204;
   successful packets producing events return 200 `application/octet-stream`
   containing concatenated event frames. Propose changing the `/api/hid`
   response cell to "204 normally; 200 application/octet-stream when the packet
   produces events". Malformed/unauthorized requests return the appropriate HTTP
   error response. BLE, WS and MQTT use their specified event channels.
2. **MicroLink control URL limits (sections 6.4, 7.3).** The resolved library
   exposes an override for the control-plane hostname, but fixes the port to
   443 and implements its control paths itself. Firmware accepts an empty URL or
   an HTTPS hostname URL with no custom port/path (a trailing slash is allowed).
   Headscale must be served at that origin with a trusted certificate. Propose
   documenting this restriction, or adding a MicroLink upstream API for an
   arbitrary control URL, port, path prefix, and custom trust roots.
3. **MicroLink string limits (section 7.3).** Auth keys are limited to 95 bytes and
   Tailscale hostnames to 47 bytes by the adapter/library persisted structures.
   Values beyond those limits produce 400 naming the field instead of silently
   truncating. Propose recording these maxima in the config schema. Other config
   strings also have explicit bounded validation (SSID 32, name/hostname 63,
   password lengths appropriate to WiFi, prefix 128, general strings 255).
4. **No-PSRAM Tailscale default (section 9/build selection).** `s3-n8` defaults
   `ESPHID_TAILSCALE=n`; enabling `tailscale.enabled` in that image returns 400
   explicitly. Both PSRAM variants default it on. This is the memory exception
   authorized in the implementation brief; propose documenting the default in
   the variant table. The normal LAN/BLE/MQTT transports remain present.

5. **Unclaimed setup precedence (sections 4, 7.1, 8).** The general unclaimed
   setup exception overlaps the stricter WS pre-auth rule and MQTT's "every RPC
   request" token rule. This implementation follows the transport-specific
   restrictions: unauthenticated WS exposes `info`/session `auth`; MQTT with
   `require_token=true` requires a valid token even for RPC setup/public methods.
   Initial claiming is therefore done through HTTP or encrypted BLE (or MQTT
   with `require_token=false` configured during setup). Propose stating this
   precedence explicitly, or relaxing the transport-specific text if setup over
   unauthenticated WS/MQTT is intended.

No required opcode, event, endpoint, RPC method, BLE characteristic or MQTT topic
was intentionally omitted. The above restrictions and hardware checks below are
explicit limitations, rather than silent placeholder implementations.

## Tailscale findings

Resolved **fugo101/microlink 3.2.0** and **fugo101/wireguard_lwip 1.0.3**, with
checksums in `dependencies.lock`. MicroLink's registry package identifies upstream
commit `a42154a39971d13a2c3d67499ab9a68b370cec62`.

**Inbound TCP: yes in the inspected implementation; not tested end to end on a
physical device.** In `managed_components/fugo101__microlink/src/ml_wg_mgr.c`,
`wg_netif_init` registers the WireGuard interface in lwIP's netif list, installs
the 100.x address and /10 mask, and sets `netif->input = tcpip_input`. Its comment
explicitly identifies TCP and `esp_http_server` as the reason for that input
function. The normal HTTP/HTTPS listeners bind to all local interfaces. Therefore
HTTP and WS can share those listeners on the tailnet IP; no synthetic proxy,
UDP-only approximation, or separate TCP server was introduced. DERP and direct
peer paths are enabled. MagicDNS registration/reachability and Headscale
interoperability still require actual tailnet testing.

Sources: [registry](https://components.espressif.com/components/fugo101/microlink/versions/3.2.0/readme),
[resolved netif implementation](https://github.com/fugo101/microlink/blob/a42154a39971d13a2c3d67499ab9a68b370cec62/components/microlink/src/ml_wg_mgr.c).

The adapter uses the library's public `microlink_config_t` lifecycle/status APIs
and its exported `ml_config_settings_t` NVS schema for the Headscale host override.
MicroLink's own management HTTP server is disabled, so it cannot conflict with
port 80 or expose a separate configuration API. Secret-bearing registration log
categories are suppressed. Active peers are bounded to four, NVS cache to 16,
and the control-plane receive window to 64 KiB. A large tailnet map may need a
larger window and more PSRAM; this configuration is optimized for a phone and a
small set of peers.

Debugger type inspection of the linked PSRAM ELF gives `sizeof(microlink_t) =
19,648`, `sizeof(ml_rx_packet_t) = 48`, `sizeof(ml_derp_tx_item_t) = 44`, and
`sizeof(ml_coord_cmd_t) = 4`. The six queue payload allocations total 3344 B,
excluding FreeRTOS queue metadata. Four MicroLink task stacks total 43,008 B
(8+14+12+8 KiB). Together with its 65,536-byte control window and context, these
alone account for **131,536 B**, before TLS, packet payloads, JSON trees, peer
updates, task control blocks, other buffers, and allocator overhead. Its context
and large buffers prefer PSRAM; task stacks consume internal RAM in these builds.
This is the basis for the no-PSRAM default. Static linked RAM is recorded in the
build output below; live free heap, largest block, and peak use cannot be measured
without hardware. `info.free_heap` exposes the runtime total.

## Reproduce and ignore generated files

```sh
cmake -S firmware/test/host -B firmware/build/host && cmake --build firmware/build/host && ctest --test-dir firmware/build/host --output-on-failure
firmware/tools/build.sh s3-n8
firmware/tools/build.sh s3-n8r2
firmware/tools/build.sh s3-n8r8
actionlint .github/workflows/firmware.yml
```

`build.sh` activates the installed IDF environment if needed, regenerates its
variant-specific sdkconfig from the defaults/overlays, and uses `ESPHID_VERSION`
or read-only `git describe` with `0.0.0-dev` fallback. `ESPHID_OTA_REPO` is applied
through a generated last overlay so repeated builds honor the current value.
The release CI provides the repository and strips a leading `v` from release tags.

Suggested additions to the root `.gitignore` (not edited by this task):

```gitignore
/firmware/build/
/firmware/managed_components/
/firmware/sdkconfig
/firmware/sdkconfig.old
```

Track `firmware/dependencies.lock`; do not ignore it. Earlier local integration
outputs are retained in generated `build/*.previous` and `build/*.integration`
directories. They are not release inputs.

## Hardware and external checks still required

- USB enumeration on Windows/macOS/Linux, exact descriptor interpretation, 1 kHz
  polling, simultaneous interfaces, full key rollover, LEDs, remote wakeup
  permission/resume timing, held-key/disconnect recovery, absolute-pointer behavior.
- Measured phone-to-USB latency, sustained motion without loss, maximum text
  buffering, queue saturation, and stack/heap high-water marks in all three
  variants, especially no-PSRAM BLE+WiFi+TLS+MQTT use.
- BLE pairing, bond eviction, encrypted characteristic access, real MTU/PHY/DLE
  negotiation, iOS/Android connection interval acceptance, two simultaneous peers,
  notification backpressure, and long 8 KiB RPC exchange.
- GPIO0 hold/reset including NVS/bond erasure, LED hardware/pin choice, AP fallback,
  captive probes, live WiFi credential handover and DHCP/mDNS behavior.
- Generated HTTPS certificate/fingerprint pinning, regeneration and listener
  restart, broker TLS/WSS reconnect/LWT/ACLs, and device time synchronization.
- Tailnet authorization, direct and DERP TCP/WS, MagicDNS, Headscale at an HTTPS
  origin on port 443, recovery after WiFi loss, larger tailnet maps and PSRAM peaks.
- Real OTA download/upload, redirects, bad chip/project/hash rejection, interrupted
  writes, next-boot validation, crash rollback, and reboot timing under load.
- The actual GitHub Actions/release/Pages jobs were not run against GitHub; the
  workflow was linted locally. Docker's IDF v6.0.2 tag was verified by registry
  manifest inspection, for both linux/amd64 and linux/arm64; local builds used the
  installed IDF toolchain rather than executing the container.

## Final validation results

All three variants were built sequentially from clean variant directories after
`idf.py fullclean`, using IDF 6.0.2 and firmware version `0.0.0-dev` (the repository
has no commits/tags). Every final build and packaging command exited 0. No
firmware-source edits followed this final clean matrix. Each dist directory
contains exactly the app, merged factory image and ESP Web Tools manifest.

Artifact checks verified chipFamily/offset 0, exact app bytes at the factory
image's 0x30000 offset, matching manifest/image version, gzip contents matching
the externally owned HTML, all required generated settings, cJSON's compiled
16-level depth limit, and current source/object timestamps. They also assert no
build error or unknown Kconfig setting appeared in any final log.

Debugger inspection of the linked ELF additionally verified that all three
application GATT characteristics have non-null access callbacks (including the
notify-only Events value, as required by NimBLE), and both endpoint descriptors
contain bInterval=1. This is static verification, not USB/BLE hardware testing.

`actionlint` **1.7.12**: exit 0, no diagnostics (YAML, Actions expressions, embedded
shell). Standalone `shellcheck firmware/tools/build.sh`: exit 0, no diagnostics.
`docker manifest inspect espressif/idf:v6.0.2`: exit 0; linux/amd64 and linux/arm64
manifests exist. Nothing was flashed or published, and Git state was not changed.

Expected upstream warnings remain for unused MicroLink/WireGuard symbols and
IDF Kconfig default/rename notes. There are zero build errors, zero unknown
project Kconfig symbols, and no warnings from authored firmware sources. Static
DIRAM "remain" below is link-time memory, not a measured runtime free heap.

### Host test output tail

```text
[100%] Built target proto_test
Test project /Users/athul/Workspace/ESPHID/v1/firmware/build/host
    Start 1: protocol
1/1 Test #1: protocol .........................   Passed    0.35 sec

100% tests passed out of 1

Total Test time (real) =   0.36 sec
PASS all 22 protocol vectors (command parse / event encode)
PASS truncation, unknown opcode, ordered prefix, AUTH and bounds
PASS JSON commands, key/modifier/consumer names and raw usages
PASS all printable US ASCII, controls, repeated-key releases, UTF-8 and motion remainder
PASS bounded JSON nesting for embedded task stacks
ALL HOST TESTS PASSED
```

### s3-n8: exact build output tail, including idf.py size

```text
                           Memory Type Usage Summary                            
┏━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━┓
┃ Memory            ┃              ┃          ┃                ┃               ┃
┃ Type/Section      ┃ Used [bytes] ┃ Used [%] ┃ Remain [bytes] ┃ Total [bytes] ┃
┡━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━╇━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━┩
│ Flash Code        │       966292 │          │                │               │
│    .text          │       966292 │          │                │               │
│ Flash Data        │       289996 │          │                │               │
│    .rodata        │       289724 │          │                │               │
│    .appdesc       │          256 │          │                │               │
│    .tdata         │           16 │          │                │               │
│ DIRAM             │       146717 │    42.93 │         195043 │        341760 │
│    .text          │        95271 │    27.88 │                │               │
│    .bss           │        29088 │     8.51 │                │               │
│    .data          │        22358 │     6.54 │                │               │
│ IRAM              │        16384 │    100.0 │              0 │         16384 │
│    .text          │        15356 │    93.73 │                │               │
│    .vectors       │         1028 │     6.27 │                │               │
│ RTC SLOW          │           36 │     0.44 │           8156 │          8192 │
│    .force_slow    │           36 │     0.44 │                │               │
│ RTC FAST          │           24 │     0.29 │           8168 │          8192 │
│    .rtc_reserved  │           24 │     0.29 │                │               │
└───────────────────┴──────────────┴──────────┴────────────────┴───────────────┘
Total image size: 1390337 bytes (.bin may be padded larger)
Note: The reported total sizes may be smaller than those in the technical 
reference manual due to reserved memory and application configuration. The total
flash size available for the application is not included by default, as it 
cannot be reliably determined due to the presence of other data like the 
bootloader, partition table, and application partition size.

Project build complete. To flash, run:
 idf.py flash
or
 idf.py -p PORT flash
or
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash --flash-mode dio --flash-size 8MB --flash-freq 80m 0x0 build/s3-n8/bootloader/bootloader.bin 0x8000 build/s3-n8/partition_table/partition-table.bin 0x1f000 build/s3-n8/ota_data_initial.bin 0x30000 build/s3-n8/esphid.bin
or from the "/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8" directory
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash "@flash_args"
esptool v5.3.1
SHA digest in image updated.
Wrote 0x183770 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist/esphid-s3-n8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8: app=1390448 bytes, OTA slot=4063232 bytes, free=2672784 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8/dist
```

### s3-n8r2: exact build output tail, including idf.py size

```text
                           Memory Type Usage Summary                            
┏━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━┓
┃ Memory            ┃              ┃          ┃                ┃               ┃
┃ Type/Section      ┃ Used [bytes] ┃ Used [%] ┃ Remain [bytes] ┃ Total [bytes] ┃
┡━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━╇━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━┩
│ Flash Code        │      1057676 │          │                │               │
│    .text          │      1057676 │          │                │               │
│ Flash Data        │       325180 │          │                │               │
│    .rodata        │       324908 │          │                │               │
│    .appdesc       │          256 │          │                │               │
│    .tdata         │           16 │          │                │               │
│ DIRAM             │       153321 │    44.86 │         188439 │        341760 │
│    .text          │        99403 │    29.09 │                │               │
│    .bss           │        31200 │     9.13 │                │               │
│    .data          │        22718 │     6.65 │                │               │
│ IRAM              │        16384 │    100.0 │              0 │         16384 │
│    .text          │        15356 │    93.73 │                │               │
│    .vectors       │         1028 │     6.27 │                │               │
│ RTC SLOW          │           36 │     0.44 │           8156 │          8192 │
│    .force_slow    │           36 │     0.44 │                │               │
│ RTC FAST          │           24 │     0.29 │           8168 │          8192 │
│    .rtc_reserved  │           24 │     0.29 │                │               │
└───────────────────┴──────────────┴──────────┴────────────────┴───────────────┘
Total image size: 1521397 bytes (.bin may be padded larger)
Note: The reported total sizes may be smaller than those in the technical 
reference manual due to reserved memory and application configuration. The total
flash size available for the application is not included by default, as it 
cannot be reliably determined due to the presence of other data like the 
bootloader, partition table, and application partition size.

Project build complete. To flash, run:
 idf.py flash
or
 idf.py -p PORT flash
or
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash --flash-mode dio --flash-size 8MB --flash-freq 80m 0x0 build/s3-n8r2/bootloader/bootloader.bin 0x8000 build/s3-n8r2/partition_table/partition-table.bin 0x1f000 build/s3-n8r2/ota_data_initial.bin 0x30000 build/s3-n8r2/esphid.bin
or from the "/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2" directory
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash "@flash_args"
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a3770 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist/esphid-s3-n8r2-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r2: app=1521520 bytes, OTA slot=4063232 bytes, free=2541712 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r2/dist
```

### s3-n8r8: exact build output tail, including idf.py size

```text
                           Memory Type Usage Summary                            
┏━━━━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━┳━━━━━━━━━━┳━━━━━━━━━━━━━━━━┳━━━━━━━━━━━━━━━┓
┃ Memory            ┃              ┃          ┃                ┃               ┃
┃ Type/Section      ┃ Used [bytes] ┃ Used [%] ┃ Remain [bytes] ┃ Total [bytes] ┃
┡━━━━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━╇━━━━━━━━━━╇━━━━━━━━━━━━━━━━╇━━━━━━━━━━━━━━━┩
│ Flash Code        │      1057668 │          │                │               │
│    .text          │      1057668 │          │                │               │
│ Flash Data        │       325180 │          │                │               │
│    .rodata        │       324908 │          │                │               │
│    .appdesc       │          256 │          │                │               │
│    .tdata         │           16 │          │                │               │
│ DIRAM             │       157137 │    45.98 │         184623 │        341760 │
│    .text          │       102167 │    29.89 │                │               │
│    .bss           │        31264 │     9.15 │                │               │
│    .data          │        23706 │     6.94 │                │               │
│ IRAM              │        16384 │    100.0 │              0 │         16384 │
│    .text          │        15356 │    93.73 │                │               │
│    .vectors       │         1028 │     6.27 │                │               │
│ RTC SLOW          │           36 │     0.44 │           8156 │          8192 │
│    .force_slow    │           36 │     0.44 │                │               │
│ RTC FAST          │           24 │     0.29 │           8168 │          8192 │
│    .rtc_reserved  │           24 │     0.29 │                │               │
└───────────────────┴──────────────┴──────────┴────────────────┴───────────────┘
Total image size: 1525141 bytes (.bin may be padded larger)
Note: The reported total sizes may be smaller than those in the technical 
reference manual due to reserved memory and application configuration. The total
flash size available for the application is not included by default, as it 
cannot be reliably determined due to the presence of other data like the 
bootloader, partition table, and application partition size.

Project build complete. To flash, run:
 idf.py flash
or
 idf.py -p PORT flash
or
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash --flash-mode dio --flash-size 8MB --flash-freq 80m 0x0 build/s3-n8r8/bootloader/bootloader.bin 0x8000 build/s3-n8r8/partition_table/partition-table.bin 0x1f000 build/s3-n8r8/ota_data_initial.bin 0x30000 build/s3-n8r8/esphid.bin
or from the "/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8" directory
 python -m esptool --chip esp32s3 -b 460800 --before default-reset --after hard-reset write-flash "@flash_args"
esptool v5.3.1
SHA digest in image updated.
Wrote 0x1a4610 bytes to file '/Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist/esphid-s3-n8r8-factory.bin', ready to flash to offset 0x0.
ESPHID s3-n8r8: app=1525264 bytes, OTA slot=4063232 bytes, free=2537968 bytes
Release outputs: /Users/athul/Workspace/ESPHID/v1/firmware/build/s3-n8r8/dist
```

### Artifact verification output

```text
s3-n8 PASS artifacts, offsets, version, gzip, settings, bounded cJSON, and current sources
│ DIRAM             │       146717 │    42.93 │         195043 │        341760 │
Total image size: 1390337 bytes (.bin may be padded larger)
ESPHID s3-n8: app=1390448 bytes, OTA slot=4063232 bytes, free=2672784 bytes
SHA256 05694cd903792588168b09d17682a344ab36ae0a2fc8c12f66e38dd1117bab81
s3-n8r2 PASS artifacts, offsets, version, gzip, settings, bounded cJSON, and current sources
│ DIRAM             │       153321 │    44.86 │         188439 │        341760 │
Total image size: 1521397 bytes (.bin may be padded larger)
ESPHID s3-n8r2: app=1521520 bytes, OTA slot=4063232 bytes, free=2541712 bytes
SHA256 3e011ade424e6ab73ffd860df5910f2848bef722cb7bed231cf569bad2c259a4
s3-n8r8 PASS artifacts, offsets, version, gzip, settings, bounded cJSON, and current sources
│ DIRAM             │       157137 │    45.98 │         184623 │        341760 │
Total image size: 1525141 bytes (.bin may be padded larger)
ESPHID s3-n8r8: app=1525264 bytes, OTA slot=4063232 bytes, free=2537968 bytes
SHA256 ce8e41f0079e325e22f9b3411161eaadbf65d569d04717bf6347e1f3c30e184f
ALL ARTIFACT CHECKS PASSED
```

Full logs: [host tests](build/validation/host.log), [s3-n8](build/validation/build-s3-n8.log), [s3-n8r2](build/validation/build-s3-n8r2.log), [s3-n8r8](build/validation/build-s3-n8r8.log), [artifact checks](build/validation/artifacts.log), [debugger checks](build/validation/ram-types.log), [clean actions](build/validation/clean.log).

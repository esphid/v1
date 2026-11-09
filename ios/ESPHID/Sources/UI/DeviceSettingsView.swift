import ESPHIDKit
import SwiftUI

@MainActor @Observable final class SettingsModel {
  var info: DeviceInfo?
  var config: DeviceConfig?
  var error: String?
  var loading = false
  var rebootNeeded = false
  private var own: RPCClient?

  func client(_ app: AppModel, _ d: SavedDevice) async throws -> RPCClient {
    if app.activeDevice?.id == d.id, app.connection.state == .connected, let r = app.connection.rpc {
      return r
    }
    if let own { return own }
    guard let token = app.store.token(for: d.id) else { throw RPCError.unauthorized }
    let kind: TransportKind = d.host != nil ? .http : (d.mqtt != nil ? .mqtt : .ble)
    let r = try await DeviceSession.openRPC(
      to: d, via: kind, token: token, mqttPassword: app.store.mqttPassword(for: d.id))
    own = r
    return r
  }

  func load(_ app: AppModel, _ d: SavedDevice) async {
    loading = true
    defer { loading = false }
    do {
      let r = try await client(app, d)
      info = try await r.info()
      config = try await r.getConfig()
      error = nil
    } catch {
      self.error = describe(error)
    }
  }

  func refreshInfo(_ app: AppModel, _ d: SavedDevice) async {
    if let r = try? await client(app, d), let i = try? await r.info() { info = i }
  }

  /// Applies a partial config. Returns true on success.
  @discardableResult
  func save(_ app: AppModel, _ d: SavedDevice, _ patch: JSONValue) async -> Bool {
    do {
      let r = try await client(app, d)
      let result = try await r.setConfig(patch)
      config = result.config
      if result.rebootRequired { rebootNeeded = true }
      error = nil
      return true
    } catch {
      self.error = describe(error)
      return false
    }
  }

  func close() {
    own?.close()
    own = nil
  }
}

struct DeviceSettingsView: View {
  let deviceID: String
  @Environment(AppModel.self) private var app
  @Environment(\.dismiss) private var dismiss
  @State private var m = SettingsModel()
  @State private var name = ""
  @State private var confirmReset = false
  @State private var confirmRemove = false
  @State private var confirmNewKey = false
  @State private var newKey: String?
  @State private var busy: String?

  private var device: SavedDevice? { app.device(deviceID) }

  var body: some View {
    NavigationStack {
      Form {
        if let d = device {
          if m.rebootNeeded {
            Section {
              Button("Restart to apply changes", systemImage: "arrow.clockwise") {
                Task { await reboot(d) }
              }
              .foregroundStyle(Palette.tint)
            } footer: {
              Text("Radio, HTTPS and Tailscale changes take effect after a restart.")
            }
          }
          if let e = m.error { Section { ErrorText(e) } }
          statusSection
          connectionSection(d)
          appSection(d)
          deviceSection(d)
          FirmwareSection(device: d, model: m)
          Section("Access key") {
            Button("Change access key…") { confirmNewKey = true }
          }
          Section {
            Button("Restart device") { Task { await reboot(d) } }
            Button("Factory reset…", role: .destructive) { confirmReset = true }
            Button("Remove from this app", role: .destructive) { confirmRemove = true }
          }
        }
      }
      .scrollContentBackground(.hidden)
      .background(Palette.paper)
      .overlay { if m.loading && m.info == nil { ProgressView() } }
      .navigationTitle(device?.name ?? "Settings")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
      .task {
        if let d = device {
          await m.load(app, d)
          name = m.config?.name ?? d.name
        }
      }
      .onDisappear { m.close() }
      .confirmationDialog("Erase this device?", isPresented: $confirmReset, titleVisibility: .visible) {
        Button("Erase and restart", role: .destructive) { Task { await factoryReset() } }
      } message: {
        Text(
          "WiFi, the access key, Bluetooth pairings and every setting are erased. The device restarts into setup mode."
        )
      }
      .confirmationDialog("Remove from this app?", isPresented: $confirmRemove, titleVisibility: .visible) {
        Button("Remove", role: .destructive) {
          app.close()
          app.store.remove(id: deviceID)
          dismiss()
        }
      } message: {
        Text("The device keeps its settings. You’ll need its access key to add it again.")
      }
      .alert("Change access key?", isPresented: $confirmNewKey) {
        Button("Change", role: .destructive) { Task { await rotateKey() } }
        Button("Cancel", role: .cancel) {}
      } message: {
        Text("Other apps and browsers using the current key will be signed out.")
      }
      .sheet(item: Binding(get: { newKey.map { IdentifiedString(value: $0) } }, set: { newKey = $0?.value })) { k in
        NewKeySheet(key: k.value)
      }
    }
  }

  // MARK: sections

  @ViewBuilder private var statusSection: some View {
    if let i = m.info {
      Section("Status") {
        ReadoutRow(label: "Firmware", value: [i.fw_version, i.fw_variant].compactMap { $0 }.joined(separator: " · "))
        ReadoutRow(
          label: "USB host",
          value: i.usb?.mounted == true ? (i.usb?.suspended == true ? "Asleep" : "Connected") : "Not connected",
          tone: i.usb?.mounted == true ? Palette.ok : Palette.bad)
        if let w = i.wifi, w.mode != "off" {
          ReadoutRow(label: "WiFi", value: w.connected == true ? "\(w.ssid ?? "") · \(w.ip ?? "")" : "Not connected")
        }
        if i.tailscale?.enabled == true {
          ReadoutRow(label: "Tailscale", value: [i.tailscale?.state, i.tailscale?.ip].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
        }
        if i.mqtt?.enabled == true {
          ReadoutRow(label: "MQTT", value: i.mqtt?.connected == true ? "Connected" : "Not connected")
        }
        if let up = i.uptime_s { ReadoutRow(label: "Uptime", value: uptime(up)) }
      }
    }
  }

  private func connectionSection(_ d: SavedDevice) -> some View {
    Section {
      Picker("Radio", selection: Binding(
        get: { m.config?.radio_mode ?? "both" },
        set: { v in Task { await m.save(app, d, ["radio_mode": .string(v)]) } }
      )) {
        Text("WiFi + Bluetooth").tag("both")
        Text("WiFi only").tag("wifi")
        Text("Bluetooth only").tag("ble")
      }
      NavigationLink("WiFi") { WiFiForm(device: d, model: m) }
      NavigationLink("Tailscale") { TailscaleForm(device: d, model: m) }
      NavigationLink("MQTT") { MQTTForm(device: d, model: m) }
      NavigationLink("HTTPS") { HTTPSForm(device: d, model: m) }
    } header: {
      Text("Device connections")
    } footer: {
      Text("One radio serves WiFi and Bluetooth. Using only one gives the lowest latency.")
    }
  }

  private func appSection(_ d: SavedDevice) -> some View {
    Section("This app") {
      let kinds = app.transports(for: d)
      if !kinds.isEmpty {
        Picker("Connect over", selection: Binding(
          get: { app.preferredTransport(for: d) ?? d.preferredTransport },
          set: { app.setPreferred($0, for: d) }
        )) {
          ForEach(kinds, id: \.self) { Text($0.title).tag($0) }
        }
      }
      NavigationLink("Address") { AddressForm(device: d) }
      NavigationLink("Pointer and haptics") { PointerPrefsView() }
    }
  }

  private func deviceSection(_ d: SavedDevice) -> some View {
    Section("Device") {
      LabeledContent("Name") {
        TextField("Name", text: $name)
          .multilineTextAlignment(.trailing)
          .submitLabel(.done)
          .onSubmit {
            let n = name.trimmingCharacters(in: .whitespaces)
            guard !n.isEmpty else { return }
            Task {
              if await m.save(app, d, ["name": .string(n)]) {
                var x = d
                x.name = n
                app.store.upsert(x)
              }
            }
          }
      }
      LabeledContent("Keyboard layout", value: "English (US)")
      Stepper(
        "Typing delay: \(m.config?.keyboard?.type_delay_ms ?? 0) ms",
        value: Binding(
          get: { m.config?.keyboard?.type_delay_ms ?? 0 },
          set: { v in
            m.config?.keyboard?.type_delay_ms = v
            Task { await m.save(app, d, ["keyboard": ["type_delay_ms": .number(Double(v))]]) }
          }), in: 0...200, step: 5)
    }
  }

  // MARK: actions

  private func reboot(_ d: SavedDevice) async {
    do {
      let r = try await m.client(app, d)
      try await r.reboot(delayMs: 300)
      m.rebootNeeded = false
      m.close()
      dismiss()
      try? await Task.sleep(for: .seconds(6))
      if app.activeDevice?.id == d.id { await app.open(app.device(d.id) ?? d, via: app.activeTransport) }
    } catch {
      m.error = describe(error)
    }
  }

  private func factoryReset() async {
    guard let d = device else { return }
    do {
      let r = try await m.client(app, d)
      try await r.factoryReset()
      app.close()
      app.store.remove(id: d.id)
      dismiss()
    } catch {
      m.error = describe(error)
    }
  }

  private func rotateKey() async {
    guard let d = device else { return }
    let key = TokenGenerator.make()
    do {
      let r = try await m.client(app, d)
      try await r.setToken(key)
      app.store.setToken(key, for: d.id)
      m.close()
      newKey = key
      if app.activeDevice?.id == d.id { Task { await app.open(d, via: app.activeTransport) } }
    } catch {
      m.error = describe(error)
    }
  }

  private func uptime(_ s: Int) -> String {
    let d = s / 86400, h = (s % 86400) / 3600, mm = (s % 3600) / 60
    return d > 0 ? "\(d)d \(h)h" : h > 0 ? "\(h)h \(mm)m" : "\(mm)m \(s % 60)s"
  }
}

private struct IdentifiedString: Identifiable {
  var value: String
  var id: String { value }
}

private struct NewKeySheet: View {
  var key: String
  @Environment(\.dismiss) private var dismiss
  @State private var copied = false
  var body: some View {
    NavigationStack {
      VStack(alignment: .leading, spacing: 18) {
        Text("This is the new access key. This app already uses it. Update any other app or browser, then store it in your password manager.")
          .foregroundStyle(Palette.ink2)
        Text(key).font(.system(.title3, design: .monospaced)).foregroundStyle(Palette.ink)
          .textSelection(.enabled)
          .padding(14).frame(maxWidth: .infinity, alignment: .leading)
          .background(RoundedRectangle(cornerRadius: 12).fill(Palette.surface))
          .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(Palette.line))
        Button(copied ? "Copied" : "Copy key") {
          UIPasteboard.general.string = key
          copied = true
        }
        .buttonStyle(PrimaryButtonStyle())
        Spacer()
      }
      .padding(20)
      .background(Palette.paper)
      .navigationTitle("New access key")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
    }
    .presentationDetents([.medium])
  }
}

// MARK: - Firmware

private struct FirmwareSection: View {
  let device: SavedDevice
  let model: SettingsModel
  @Environment(AppModel.self) private var app
  @State private var checking = false
  @State private var note: String?
  @State private var available: FirmwareRelease?
  @State private var progress: Double?
  @State private var phase: String?
  @State private var customURL = ""

  var body: some View {
    Section {
      LabeledContent("Installed") { Text(model.info?.fw_version ?? "—").font(.readout) }
      if let phase {
        VStack(alignment: .leading, spacing: 8) {
          HStack {
            Text(phase)
            Spacer()
            if let progress { Text("\(Int(progress))%").font(.readout) }
          }
          ProgressView(value: (progress ?? 0) / 100).tint(Palette.accentStrong)
        }
        .padding(.vertical, 4)
      } else if let r = available {
        Button("Install \(r.version)") { Task { await install(r.assetURL.absoluteString) } }
          .foregroundStyle(Palette.tint)
      } else {
        Button {
          Task { await check() }
        } label: {
          HStack {
            Text("Check for updates")
            Spacer()
            if checking { ProgressView() }
          }
        }
        .disabled(checking)
      }
      if let note { Text(note).font(.footnote).foregroundStyle(Palette.ink2) }
      NavigationLink("Install from a URL") {
        Form {
          Section {
            TextField("https://…/esphid-s3-n8r8.bin", text: $customURL, axis: .vertical)
              .font(.readoutSmall).textInputAutocapitalization(.never).autocorrectionDisabled()
              .keyboardType(.URL)
          } footer: {
            Text("The device downloads the file itself, so the URL must be reachable from its network.")
          }
          Button("Install") { Task { await install(customURL) } }
            .disabled(!(customURL.hasPrefix("http://") || customURL.hasPrefix("https://")) || phase != nil)
        }
        .navigationTitle("Install from URL")
      }
    } header: {
      Text("Firmware")
    } footer: {
      Text("Updates install to the spare slot. If the new version fails to start, the device rolls back by itself.")
    }
  }

  private func check() async {
    checking = true
    defer { checking = false }
    note = nil
    let repo = model.info?.ota?.repo ?? device.otaRepo ?? ""
    let variant = model.info?.fw_variant ?? device.fwVariant ?? ""
    guard !repo.isEmpty, !variant.isEmpty else {
      note = "This firmware build has no release source. Install from a URL instead."
      return
    }
    do {
      guard let r = try await FirmwareUpdates.latest(repo: repo, variant: variant) else {
        note = "No release has firmware for \(variant) yet."
        return
      }
      if FirmwareUpdates.isNewer(r.version, than: model.info?.fw_version ?? "0") {
        available = r
        note = r.notes.isEmpty ? nil : String(r.notes.prefix(280))
      } else {
        note = "You’re on the latest release."
      }
    } catch {
      note = describe(error)
    }
  }

  private func install(_ url: String) async {
    note = nil
    phase = "Starting"
    progress = nil
    do {
      let r = try await model.client(app, device)
      try await r.startOTA(url: url)
      while true {
        try await Task.sleep(for: .milliseconds(700))
        let s = try await r.otaStatus()
        switch s.state {
        case "downloading":
          phase = "Downloading"
          progress = s.progress
        case "verifying":
          phase = "Verifying"
          progress = 100
        case "done":
          phase = nil
          available = nil
          note = "Installed. The device is restarting and will reconnect."
          try? await Task.sleep(for: .seconds(8))
          if app.activeDevice?.id == device.id {
            await app.open(app.device(device.id) ?? device, via: app.activeTransport)
          }
          return
        case "error":
          phase = nil
          note = s.error ?? "The update failed."
          return
        default: break
        }
      }
    } catch {
      phase = nil
      note = describe(error)
    }
  }
}

// MARK: - Sub-forms

private struct WiFiForm: View {
  let device: SavedDevice
  let model: SettingsModel
  @Environment(AppModel.self) private var app
  @State private var ssid = ""
  @State private var password = ""
  @State private var hostname = ""
  @State private var fallback = true
  @State private var networks: [WiFiNetwork] = []
  @State private var scanning = false
  @State private var saved = false

  var body: some View {
    Form {
      Section {
        TextField("Network name", text: $ssid).textInputAutocapitalization(.never).autocorrectionDisabled()
        SecureField(model.config?.wifi?.password_set == true ? "Password (saved — leave blank to keep)" : "Password", text: $password)
      } header: {
        Text("Network")
      } footer: {
        Text(
          app.activeTransport == .websocket || app.activeTransport == .http
            ? "Leave the password empty for an open network. This app is connected over WiFi, so it will reconnect after the device changes networks."
            : "Leave the password empty for an open network.")
      }
      Section {
        Button {
          Task { await scan() }
        } label: {
          HStack { Text("Scan for networks"); Spacer(); if scanning { ProgressView() } }
        }
        ForEach(networks) { n in
          Button {
            ssid = n.ssid ?? ""
          } label: {
            HStack {
              Text(n.ssid ?? "").foregroundStyle(Palette.ink)
              Spacer()
              if (n.auth ?? "open") != "open" { Image(systemName: "lock.fill").font(.caption).foregroundStyle(Palette.ink3) }
              SignalBars(rssi: n.rssi ?? -90)
            }
          }
        }
      }
      Section {
        TextField("Hostname", text: $hostname).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled()
        Toggle("Setup hotspot as fallback", isOn: $fallback)
      } footer: {
        Text("The hotspot starts if WiFi can’t connect for a minute, so you can fix settings from any phone.")
      }
      Button(saved ? "Saved" : "Save") {
        Task {
          var w: [String: JSONValue] = ["ssid": .string(ssid), "hostname": .string(hostname), "ap_fallback": .bool(fallback)]
          // A different network always carries its password; empty means an open network.
          if !password.isEmpty || ssid != (model.config?.wifi?.ssid ?? "") { w["password"] = .string(password) }
          saved = await model.save(app, device, ["wifi": .object(w)])
          if saved { password = "" }
        }
      }
      .disabled(ssid.isEmpty)
    }
    .navigationTitle("WiFi")
    .onAppear {
      ssid = model.config?.wifi?.ssid ?? ""
      hostname = model.config?.wifi?.hostname ?? ""
      fallback = model.config?.wifi?.ap_fallback ?? true
    }
  }

  private func scan() async {
    scanning = true
    defer { scanning = false }
    if let r = try? await model.client(app, device), let list = try? await r.scanWiFi() {
      var seen = Set<String>()
      networks = list.filter { n in
        guard let s = n.ssid, !s.isEmpty, !seen.contains(s) else { return false }
        seen.insert(s)
        return true
      }
    }
  }
}

private struct TailscaleForm: View {
  let device: SavedDevice
  let model: SettingsModel
  @Environment(AppModel.self) private var app
  @State private var enabled = false
  @State private var authKey = ""
  @State private var hostname = ""
  @State private var controlURL = ""
  @State private var saved = false

  var body: some View {
    Form {
      if let t = model.info?.tailscale, t.enabled == true {
        Section("Status") {
          ReadoutRow(label: "State", value: t.state ?? "—", tone: t.state == "connected" ? Palette.ok : nil)
          if let ip = t.ip, !ip.isEmpty { ReadoutRow(label: "Tailnet IP", value: ip) }
          if let ip = t.ip, !ip.isEmpty, device.host != ip {
            Button("Use \(ip) in this app") {
              var d = device
              d.host = ip
              d.port = 80
              app.store.upsert(d)
            }
          }
        }
      }
      Section {
        Toggle("Enable Tailscale", isOn: $enabled)
        SecureField(model.config?.tailscale?.auth_key_set == true ? "Auth key (saved — leave blank to keep)" : "Auth key (tskey-auth-…)", text: $authKey)
          .font(.readout)
        TextField("Tailnet hostname", text: $hostname).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled()
        TextField("Control server (only for Headscale)", text: $controlURL).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
      } footer: {
        Text("Create an auth key in the Tailscale admin console under Settings → Keys. A PSRAM board is recommended. Changes apply after a restart.")
      }
      Button(saved ? "Saved" : "Save") {
        Task {
          var t: [String: JSONValue] = ["enabled": .bool(enabled), "hostname": .string(hostname), "control_url": .string(controlURL)]
          if !authKey.isEmpty { t["auth_key"] = .string(authKey) }
          saved = await model.save(app, device, ["tailscale": .object(t)])
          if saved { authKey = "" }
        }
      }
    }
    .navigationTitle("Tailscale")
    .onAppear {
      enabled = model.config?.tailscale?.enabled ?? false
      hostname = model.config?.tailscale?.hostname ?? ""
      controlURL = model.config?.tailscale?.control_url ?? ""
    }
  }
}

private struct MQTTForm: View {
  let device: SavedDevice
  let model: SettingsModel
  @Environment(AppModel.self) private var app
  @State private var enabled = false
  @State private var uri = ""
  @State private var username = ""
  @State private var password = ""
  @State private var clientID = ""
  @State private var prefix = ""
  @State private var requireToken = true
  @State private var useInApp = true
  @State private var saved = false

  var body: some View {
    Form {
      Section {
        Toggle("Enable MQTT", isOn: $enabled)
        TextField("mqtts://broker.example.com:8883", text: $uri).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        TextField("Username", text: $username).textInputAutocapitalization(.never).autocorrectionDisabled()
        SecureField(model.config?.mqtt?.password_set == true ? "Password (saved — leave blank to keep)" : "Password", text: $password)
      } header: { Text("Broker") }
      Section {
        TextField("Topic prefix", text: $prefix).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled()
        TextField("Client ID (automatic)", text: $clientID).font(.readout).textInputAutocapitalization(.never).autocorrectionDisabled()
        Toggle("Require access key in messages", isOn: $requireToken)
      } footer: {
        Text("With the key required, broker accounts alone can’t type on the computer.")
      }
      Section {
        Toggle("Use MQTT from this app", isOn: $useInApp)
      } footer: {
        Text("Adds MQTT as a connection option for this device in the app.")
      }
      Button(saved ? "Saved" : "Save") { Task { await save() } }
    }
    .navigationTitle("MQTT")
    .onAppear {
      let c = model.config?.mqtt
      enabled = c?.enabled ?? false
      uri = c?.uri ?? ""
      username = c?.username ?? ""
      clientID = c?.client_id ?? ""
      prefix = c?.topic_prefix ?? "esphid/\(device.id)"
      requireToken = c?.require_token ?? true
      useInApp = device.mqtt != nil || !(c?.enabled ?? false)
    }
  }

  private func save() async {
    var q: [String: JSONValue] = [
      "enabled": .bool(enabled), "uri": .string(uri), "username": .string(username),
      "client_id": .string(clientID), "topic_prefix": .string(prefix), "require_token": .bool(requireToken),
    ]
    if !password.isEmpty { q["password"] = .string(password) }
    saved = await model.save(app, device, ["mqtt": .object(q)])
    guard saved else { return }
    var d = app.device(device.id) ?? device
    if useInApp && enabled && !uri.isEmpty {
      d.mqtt = MQTTSettings(uri: uri, username: username.isEmpty ? nil : username, topicPrefix: prefix)
      if !password.isEmpty { app.store.setMQTTPassword(password, for: d.id) }
    } else if !useInApp {
      d.mqtt = nil
      app.store.setMQTTPassword(nil, for: d.id)
    }
    app.store.upsert(d)
    password = ""
  }
}

private struct HTTPSForm: View {
  let device: SavedDevice
  let model: SettingsModel
  @Environment(AppModel.self) private var app
  @State private var enabled = false
  @State private var port = "443"
  @State private var saved = false
  @State private var regenerating = false

  private var fingerprint: String { model.info?.https?.fingerprint ?? "" }

  var body: some View {
    Form {
      Section {
        Toggle("Enable HTTPS", isOn: $enabled)
        LabeledContent("Port") {
          TextField("443", text: $port).keyboardType(.numberPad).multilineTextAlignment(.trailing).font(.readout)
        }
      } footer: {
        Text("Serves the API and device page over TLS with a certificate made on the device. Changes apply after a restart.")
      }
      if !fingerprint.isEmpty {
        Section("Certificate fingerprint (SHA-256)") {
          Text(fingerprint).font(.readoutSmall).textSelection(.enabled)
          Button(regenerating ? "Making a new certificate…" : "New certificate") {
            Task {
              regenerating = true
              defer { regenerating = false }
              if let r = try? await model.client(app, device), let fp = try? await r.regenerateHTTPSCertificate() {
                model.info?.https?.fingerprint = fp
                if var d = app.device(device.id), d.httpsPort != nil {
                  d.tlsFingerprint = fp
                  app.store.upsert(d)
                }
              }
            }
          }
          .disabled(regenerating)
        }
        Section {
          Toggle("Use HTTPS from this app", isOn: Binding(
            get: { app.device(device.id)?.httpsPort != nil },
            set: { on in
              guard var d = app.device(device.id) else { return }
              d.httpsPort = on ? (Int(port) ?? 443) : nil
              d.tlsFingerprint = on ? fingerprint : nil
              app.store.upsert(d)
            }))
        } footer: {
          Text("The app trusts exactly this certificate. Over Tailscale, plain WiFi is already encrypted.")
        }
      }
      Button(saved ? "Saved" : "Save") {
        Task {
          saved = await model.save(app, device, ["https": ["enabled": .bool(enabled), "port": .number(Double(Int(port) ?? 443))]])
        }
      }
    }
    .navigationTitle("HTTPS")
    .onAppear {
      enabled = model.config?.https?.enabled ?? false
      port = String(model.config?.https?.port ?? 443)
    }
  }
}

private struct AddressForm: View {
  let device: SavedDevice
  @Environment(AppModel.self) private var app
  @State private var host = ""
  @State private var port = "80"

  var body: some View {
    Form {
      Section {
        TextField("IP, .local or Tailscale name", text: $host).font(.readout)
          .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
        LabeledContent("Port") {
          TextField("80", text: $port).keyboardType(.numberPad).multilineTextAlignment(.trailing).font(.readout)
        }
      } footer: {
        Text("Leave empty to connect only over Bluetooth or MQTT.")
      }
      Button("Save") {
        var d = app.device(device.id) ?? device
        let h = host.trimmingCharacters(in: .whitespaces)
        d.host = h.isEmpty ? nil : h
        d.port = Int(port) ?? 80
        app.store.upsert(d)
      }
    }
    .navigationTitle("Address")
    .onAppear {
      host = device.host ?? ""
      port = String(device.port)
    }
  }
}

struct PointerPrefsView: View {
  @AppStorage("pointer.sensitivity") private var sensitivity = 1.4
  @AppStorage("pointer.acceleration") private var acceleration = 0.6
  @AppStorage("pointer.natural") private var natural = true
  @AppStorage("haptics") private var haptics = true

  var body: some View {
    Form {
      Section {
        VStack(alignment: .leading) {
          LabeledContent("Speed") { Text(String(format: "%.1f×", sensitivity)).font(.readout) }
          Slider(value: $sensitivity, in: 0.4...4, step: 0.1)
        }
        VStack(alignment: .leading) {
          LabeledContent("Acceleration") { Text(String(format: "%.1f", acceleration)).font(.readout) }
          Slider(value: $acceleration, in: 0...2, step: 0.1)
        }
      } footer: {
        Text("Acceleration makes fast swipes travel further, so you can cross a large screen and still place the pointer precisely.")
      }
      Section {
        Toggle("Natural scrolling", isOn: $natural)
        Toggle("Haptics", isOn: $haptics)
      }
    }
    .navigationTitle("Pointer and haptics")
  }
}

import ESPHIDKit
import SwiftUI

/// Add a device by address (LAN IP, .local, Tailscale IP or MagicDNS name) or by MQTT broker.
struct ManualAddView: View {
  let kind: DevicesView.ManualKind
  var onDone: (SavedDevice) -> Void

  @Environment(AppModel.self) private var model
  @Environment(\.dismiss) private var dismiss

  @State private var host = ""
  @State private var port = "80"
  @State private var useTLS = false
  @State private var tlsPort = "443"
  @State private var fingerprint = ""
  @State private var key = ""
  @State private var brokerURI = "mqtts://"
  @State private var brokerUser = ""
  @State private var brokerPassword = ""
  @State private var deviceID = ""
  @State private var prefix = ""
  @State private var busy = false
  @State private var error: String?

  var body: some View {
    NavigationStack {
      Form {
        if kind == .address {
          Section {
            TextField("192.168.1.50, esphid-a1b2c3.local or desk.tailnet.ts.net", text: $host)
              .textInputAutocapitalization(.never).autocorrectionDisabled()
              .keyboardType(.URL).font(.system(.body, design: .monospaced))
            LabeledContent("Port") {
              TextField("80", text: $port).keyboardType(.numberPad).multilineTextAlignment(.trailing)
                .font(.readout)
            }
          } header: {
            Text("Address")
          } footer: {
            Text(
              "For Tailscale, use the device’s 100.x address or its MagicDNS name. Tailscale already encrypts the connection."
            )
          }
          Section {
            Toggle("Use HTTPS", isOn: $useTLS)
            if useTLS {
              LabeledContent("HTTPS port") {
                TextField("443", text: $tlsPort).keyboardType(.numberPad)
                  .multilineTextAlignment(.trailing).font(.readout)
              }
              TextField("Certificate fingerprint (optional)", text: $fingerprint, axis: .vertical)
                .font(.readoutSmall).textInputAutocapitalization(.characters)
                .autocorrectionDisabled()
            }
          } footer: {
            if useTLS {
              Text(
                "Leave the fingerprint empty to read it from the device over plain HTTP and trust it from now on."
              )
            }
          }
        } else {
          Section {
            TextField("mqtts://broker.example.com:8883", text: $brokerURI)
              .textInputAutocapitalization(.never).autocorrectionDisabled().keyboardType(.URL)
              .font(.system(.body, design: .monospaced))
            TextField("Username", text: $brokerUser)
              .textInputAutocapitalization(.never).autocorrectionDisabled()
            SecureField("Password", text: $brokerPassword)
          } header: {
            Text("Broker")
          }
          Section {
            TextField("Device ID, e.g. A1B2C3", text: $deviceID)
              .textInputAutocapitalization(.characters).autocorrectionDisabled()
              .font(.system(.body, design: .monospaced))
              .onChange(of: deviceID) { _, v in
                if prefix.isEmpty || prefix.hasPrefix("esphid/") {
                  prefix = v.isEmpty ? "" : "esphid/\(v.uppercased())"
                }
              }
            TextField("Topic prefix", text: $prefix)
              .textInputAutocapitalization(.never).autocorrectionDisabled()
              .font(.system(.body, design: .monospaced))
          } header: {
            Text("Device")
          } footer: {
            Text("The device ID and prefix are on the device page under Connect → MQTT.")
          }
        }
        Section {
          KeyField(text: $key)
        } header: {
          Text("Access key")
        }
        if let error {
          Section { ErrorText(error) }
        }
      }
      .scrollContentBackground(.hidden)
      .background(Palette.paper)
      .navigationTitle(kind == .address ? "Add by address" : "Add by MQTT")
      .navigationBarTitleDisplayMode(.inline)
      .toolbar {
        ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
        ToolbarItem(placement: .confirmationAction) {
          if busy {
            ProgressView()
          } else {
            Button("Add") { Task { await add() } }.disabled(!canAdd)
          }
        }
      }
    }
  }

  private var canAdd: Bool {
    guard key.count >= 16 else { return false }
    return kind == .address
      ? !host.trimmingCharacters(in: .whitespaces).isEmpty
      : brokerURI.count > 9 && !prefix.isEmpty
  }

  private func add() async {
    busy = true
    error = nil
    defer { busy = false }
    let token = key.trimmingCharacters(in: .whitespacesAndNewlines)
    do {
      var device: SavedDevice
      let rpc: RPCClient
      if kind == .address {
        let h = cleanHost(host)
        let p = Int(port) ?? 80
        device = SavedDevice(id: "pending", name: h, host: h, port: p)
        if useTLS {
          var pin = fingerprint.trimmingCharacters(in: .whitespacesAndNewlines).uppercased()
          if pin.isEmpty {
            let probe = try await DeviceSession.openRPC(to: device, via: .http, token: token)
            pin = (try await probe.info()).https?.fingerprint ?? ""
            probe.close()
            if pin.isEmpty {
              throw RPCError.transport("HTTPS is off on the device, or it didn’t report a certificate.")
            }
          }
          device.httpsPort = Int(tlsPort) ?? 443
          device.tlsFingerprint = pin
        }
        rpc = try await DeviceSession.openRPC(to: device, via: .http, token: token)
      } else {
        let id = deviceID.trimmingCharacters(in: .whitespaces).uppercased()
        device = SavedDevice(
          id: id.isEmpty ? "pending" : id, name: "ESPHID-\(id)",
          mqtt: MQTTSettings(
            uri: brokerURI.trimmingCharacters(in: .whitespaces),
            username: brokerUser.isEmpty ? nil : brokerUser,
            topicPrefix: prefix.trimmingCharacters(in: .whitespaces)),
          preferredTransport: .mqtt)
        rpc = try await DeviceSession.openRPC(
          to: device, via: .mqtt, token: token,
          mqttPassword: brokerPassword.isEmpty ? nil : brokerPassword)
      }
      defer { rpc.close() }
      _ = try await rpc.getConfig()  // proves the key
      let info = try await rpc.info()
      guard let id = info.id else { throw RPCError.decoding("Missing device ID") }
      let saved = SavedDevice(
        id: id, name: info.name ?? "ESPHID-\(id)", host: device.host, port: device.port,
        httpsPort: device.httpsPort, tlsFingerprint: device.tlsFingerprint,
        bleIdentifier: model.device(id)?.bleIdentifier, mqtt: device.mqtt,
        preferredTransport: kind == .address ? .websocket : .mqtt,
        fwVariant: info.fw_variant, otaRepo: info.ota?.repo)
      model.store.setToken(token, for: id)
      if kind == .mqtt { model.store.setMQTTPassword(brokerPassword.isEmpty ? nil : brokerPassword, for: id) }
      model.store.upsert(saved)
      onDone(saved)
    } catch {
      self.error = describe(error)
    }
  }

  private func cleanHost(_ s: String) -> String {
    var h = s.trimmingCharacters(in: .whitespacesAndNewlines)
    for p in ["http://", "https://", "ws://", "wss://"] where h.lowercased().hasPrefix(p) {
      h = String(h.dropFirst(p.count))
    }
    if let slash = h.firstIndex(of: "/") { h = String(h[..<slash]) }
    return h
  }
}

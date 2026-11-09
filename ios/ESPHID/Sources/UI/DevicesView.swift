import ESPHIDKit
import SwiftUI

struct DevicesView: View {
  @Environment(AppModel.self) private var model
  @Binding var path: NavigationPath
  @State private var onboarding: DiscoveredDevice?
  @State private var manual: ManualKind?

  enum ManualKind: String, Identifiable {
    case address, mqtt
    var id: String { rawValue }
  }

  private var nearby: [DiscoveredDevice] {
    let saved = model.store.devices
    return model.discovery.found.filter { f in
      if let id = f.deviceId, saved.contains(where: { $0.id == id }) { return false }
      if case .ble(let p, _) = f.source, saved.contains(where: { $0.bleIdentifier == p }) {
        return false
      }
      return true
    }
  }

  var body: some View {
    List {
      if !model.store.devices.isEmpty {
        Section("Your devices") {
          ForEach(model.store.devices) { d in
            Button { path.append(d.id) } label: { SavedRow(device: d) }
              .swipeActions {
                Button(role: .destructive) { model.store.remove(id: d.id) } label: {
                  Label("Remove", systemImage: "trash")
                }
              }
          }
        }
      }
      Section {
        if nearby.isEmpty {
          Text(
            model.discovery.isScanning
              ? "Looking for devices over Bluetooth and on this network…"
              : "Nothing nearby."
          )
          .font(.subheadline).foregroundStyle(Palette.ink3)
          .padding(.vertical, 6)
        }
        ForEach(nearby) { f in
          Button { onboarding = f } label: { NearbyRow(device: f) }
        }
      } header: {
        HStack {
          Text("Nearby")
          Spacer()
          if model.discovery.isScanning { ProgressView().controlSize(.mini) }
        }
      } footer: {
        if model.store.devices.isEmpty {
          Text(
            "Plug an ESPHID into a computer and it shows up here. You can also add one by its address, a Tailscale name, or an MQTT broker with the + button."
          )
        }
      }
    }
    .scrollContentBackground(.hidden)
    .background(Palette.paper)
    .navigationTitle("ESPHID")
    .toolbar {
      ToolbarItem(placement: .topBarTrailing) {
        Menu {
          Button("Address or Tailscale name", systemImage: "network") { manual = .address }
          Button("MQTT broker", systemImage: "point.3.connected.trianglepath.dotted") {
            manual = .mqtt
          }
        } label: {
          Image(systemName: "plus").accessibilityLabel("Add device")
        }
      }
    }
    .onAppear { model.discovery.start() }
    .onDisappear { model.discovery.stop() }
    .onChange(of: model.discovery.found) { _, _ in model.syncDiscovery() }
    .refreshable {
      model.discovery.stop()
      model.discovery.start()
      try? await Task.sleep(for: .seconds(1))
    }
    .sheet(item: $onboarding) { f in
      OnboardFlow(discovered: f) { saved in
        onboarding = nil
        path.append(saved.id)
      }
    }
    .sheet(item: $manual) { kind in
      ManualAddView(kind: kind) { saved in
        manual = nil
        path.append(saved.id)
      }
    }
  }
}

private struct SavedRow: View {
  @Environment(AppModel.self) private var model
  var device: SavedDevice
  var body: some View {
    HStack(spacing: 14) {
      Image(systemName: "keyboard")
        .font(.title3).foregroundStyle(Palette.ink2)
        .frame(width: 40, height: 40)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.sunken))
      VStack(alignment: .leading, spacing: 2) {
        Text(device.name).font(.body.weight(.semibold)).foregroundStyle(Palette.ink)
        Text(subtitle).font(.readoutSmall).foregroundStyle(Palette.ink3).lineLimit(1)
      }
      Spacer(minLength: 8)
      if let k = model.preferredTransport(for: device) {
        Image(systemName: k.symbol).foregroundStyle(Palette.ink3).accessibilityLabel(k.title)
      }
    }
    .padding(.vertical, 4)
  }
  private var subtitle: String {
    var parts = [device.id]
    if let h = device.host { parts.append(h) } else if device.mqtt != nil { parts.append("MQTT") }
    return parts.joined(separator: " · ")
  }
}

private struct NearbyRow: View {
  var device: DiscoveredDevice
  var body: some View {
    HStack(spacing: 14) {
      Image(systemName: isBLE ? "dot.radiowaves.left.and.right" : "wifi")
        .font(.title3).foregroundStyle(Palette.accentStrong)
        .frame(width: 40, height: 40)
        .background(RoundedRectangle(cornerRadius: 10, style: .continuous).fill(Palette.accentWash))
      VStack(alignment: .leading, spacing: 2) {
        Text(device.name).font(.body.weight(.semibold)).foregroundStyle(Palette.ink)
        Text(detail).font(.readoutSmall).foregroundStyle(Palette.ink3).lineLimit(1)
      }
      Spacer(minLength: 8)
      Text(device.claimed == false ? "Set up" : "Add")
        .font(.subheadline.weight(.semibold)).foregroundStyle(Palette.tint)
    }
    .padding(.vertical, 4)
  }
  private var isBLE: Bool { if case .ble = device.source { true } else { false } }
  private var detail: String {
    switch device.source {
    case .ble(_, let rssi): "Bluetooth · \(rssi) dBm"
    case .lan(let host, _): host
    }
  }
}

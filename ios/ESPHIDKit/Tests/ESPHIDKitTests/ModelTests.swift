import XCTest

@testable import ESPHIDKit

final class ModelTests: XCTestCase {
  func testFullProtocolExamples() throws {
    let info = #"""
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
      """#
    let config = #"""
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
      """#
    let i = try JSONDecoder().decode(DeviceInfo.self, from: Data(info.utf8))
    XCTAssertEqual(i.wifi?.rssi, -55)
    XCTAssertEqual(i.ota?.repo, "owner/repo")
    XCTAssertEqual(i.usb?.mounted, true)
    let c = try JSONDecoder().decode(DeviceConfig.self, from: Data(config.utf8))
    XCTAssertEqual(c.mqtt?.require_token, true)
    XCTAssertEqual(c.keyboard?.type_delay_ms, 0)
    XCTAssertEqual(c.wifi?.password_set, true)
    let publicInfo = try JSONDecoder().decode(
      DeviceInfo.self,
      from: Data(
        #"{"id":"A1B2C3","name":"ESPHID-A1B2C3","model":"esp32s3","fw_version":"1.2.0","fw_variant":"s3-n8r8","proto":1,"claimed":true}"#
          .utf8))
    XCTAssertNil(publicInfo.wifi)
  }
}

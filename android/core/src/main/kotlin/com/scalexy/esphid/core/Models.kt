package com.scalexy.esphid.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

val ProtocolJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Serializable
data class DeviceInfo(
    val id: String? = null,
    val name: String? = null,
    val model: String? = null,
    val fw_version: String? = null,
    val fw_variant: String? = null,
    val idf_version: String? = null,
    val proto: Int? = null,
    val claimed: Boolean? = null,
    val uptime_s: Long? = null,
    val free_heap: Long? = null,
    val radio_mode: String? = null,
    val usb: UsbInfo? = null,
    val wifi: WifiInfo? = null,
    val ble: BleInfo? = null,
    val https: HttpsInfo? = null,
    val mqtt: MqttInfo? = null,
    val tailscale: TailscaleInfo? = null,
    val ota: OtaInfo? = null,
)

@Serializable
data class UsbInfo(
    val mounted: Boolean? = null,
    val suspended: Boolean? = null,
    val leds: Int? = null,
)

@Serializable
data class WifiInfo(
    val mode: String? = null,
    val connected: Boolean? = null,
    val ssid: String? = null,
    val ip: String? = null,
    val rssi: Int? = null,
    val mac: String? = null,
    val ap_active: Boolean? = null,
    val ap_ip: String? = null,
)

@Serializable data class BleInfo(val enabled: Boolean? = null, val connections: Int? = null)

@Serializable
data class HttpsInfo(
    val enabled: Boolean? = null,
    val port: Int? = null,
    val fingerprint: String? = null,
)

@Serializable data class MqttInfo(val enabled: Boolean? = null, val connected: Boolean? = null)

@Serializable
data class TailscaleInfo(
    val enabled: Boolean? = null,
    val state: String? = null,
    val ip: String? = null,
    val hostname: String? = null,
)

@Serializable
data class OtaInfo(
    val state: String? = null,
    val progress: Int? = null,
    val repo: String? = null,
    val latest_url: String? = null,
)

@Serializable
data class DeviceConfig(
    val name: String? = null,
    val radio_mode: String? = null,
    val wifi: WifiConfig? = null,
    val https: HttpsInfo? = null,
    val mqtt: MqttConfig? = null,
    val tailscale: TailscaleConfig? = null,
    val keyboard: KeyboardConfig? = null,
)

@Serializable
data class WifiConfig(
    val ssid: String? = null,
    val password_set: Boolean? = null,
    val hostname: String? = null,
    val ap_fallback: Boolean? = null,
)

@Serializable
data class MqttConfig(
    val enabled: Boolean? = null,
    val uri: String? = null,
    val username: String? = null,
    val password_set: Boolean? = null,
    val client_id: String? = null,
    val topic_prefix: String? = null,
    val require_token: Boolean? = null,
)

@Serializable
data class TailscaleConfig(
    val enabled: Boolean? = null,
    val auth_key_set: Boolean? = null,
    val control_url: String? = null,
    val hostname: String? = null,
)

@Serializable data class KeyboardConfig(val layout: String? = null, val type_delay_ms: Int? = null)

@Serializable
data class WifiNetwork(
    val ssid: String? = null,
    val rssi: Int? = null,
    val auth: String? = null,
    val channel: Int? = null,
)

@Serializable
data class OtaStatus(
    val state: String? = null,
    val progress: Int? = null,
    val bytes: Long? = null,
    val total: Long? = null,
    val error: String? = null,
    val version: String? = null,
)

@Serializable
data class ConfigSetResult(
    val config: DeviceConfig = DeviceConfig(),
    @kotlinx.serialization.SerialName("reboot_required") val rebootRequired: Boolean = false,
)

@Serializable
enum class TransportKind {
    BLE,
    WEBSOCKET,
    HTTP,
    MQTT,
}

@Serializable
data class MqttSettings(val uri: String, val username: String? = null, val topicPrefix: String)

@Serializable
data class SavedDevice(
    val id: String,
    val name: String,
    val host: String? = null,
    val port: Int = 80,
    val httpsPort: Int? = null,
    val tlsFingerprint: String? = null,
    val bleAddress: String? = null,
    val mqtt: MqttSettings? = null,
    val preferredTransport: TransportKind,
    val fwVariant: String? = null,
    val otaRepo: String? = null,
)

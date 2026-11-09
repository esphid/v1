package dev.scalexy.esphid.ui

import android.app.Application
import android.content.Context
import androidx.core.content.edit
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.scalexy.esphid.core.ConnectionState
import dev.scalexy.esphid.core.DeviceConfig
import dev.scalexy.esphid.core.DeviceDiscovery
import dev.scalexy.esphid.core.DeviceInfo
import dev.scalexy.esphid.core.DeviceSessions
import dev.scalexy.esphid.core.DeviceStore
import dev.scalexy.esphid.core.DiscoveredDevice
import dev.scalexy.esphid.core.HidConnection
import dev.scalexy.esphid.core.RpcClient
import dev.scalexy.esphid.core.RpcException
import dev.scalexy.esphid.core.SavedDevice
import dev.scalexy.esphid.core.Source
import dev.scalexy.esphid.core.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

sealed interface Route {
    data object Devices : Route
    data class Control(val id: String) : Route
    data class Onboard(val device: DiscoveredDevice) : Route
    data class ManualAdd(val mqtt: Boolean) : Route
    data class Settings(val id: String) : Route
    data class SettingsPage(val id: String, val page: Page) : Route
    data object Pointer : Route
}

enum class Page { WIFI, TAILSCALE, MQTT, HTTPS, ADDRESS, OTA_URL }

class AppModel(app: Application) : AndroidViewModel(app) {
    val ctx: Context get() = getApplication()
    val store = DeviceStore(app)
    val discovery = DeviceDiscovery(app)
    val connection = HidConnection(app)

    // navigation
    var stack by mutableStateOf(listOf<Route>(Route.Devices))
        private set
    fun push(r: Route) { stack = stack + r }
    fun pop() { if (stack.size > 1) stack = stack.dropLast(1) }
    fun replaceTop(r: Route) { stack = stack.dropLast(1) + r }
    fun resetTo(vararg r: Route) { stack = listOf(Route.Devices) + r }

    // live connection
    var activeId by mutableStateOf<String?>(null)
        private set
    var activeTransport by mutableStateOf<TransportKind?>(null)
        private set
    var connectError by mutableStateOf<String?>(null)
    private var openJob: Job? = null

    // pointer prefs
    private val prefs = app.getSharedPreferences("esphid.prefs", Context.MODE_PRIVATE)
    var sensitivity by mutableFloatStateOf(prefs.getFloat("sensitivity", 1.4f))
        private set
    var acceleration by mutableFloatStateOf(prefs.getFloat("acceleration", 1.2f))
        private set
    var natural by mutableStateOf(prefs.getBoolean("natural", true))
        private set
    var haptics by mutableStateOf(prefs.getBoolean("haptics", true))
        private set
    fun setPointer(s: Float = sensitivity, a: Float = acceleration, n: Boolean = natural, h: Boolean = haptics) {
        sensitivity = s; acceleration = a; natural = n; haptics = h
        prefs.edit { putFloat("sensitivity", s); putFloat("acceleration", a); putBoolean("natural", n); putBoolean("haptics", h) }
    }

    init {
        viewModelScope.launch { discovery.found.collect { syncDiscovery(it) } }
    }

    fun device(id: String): SavedDevice? = store.devices.value.firstOrNull { it.id == id }

    fun transports(d: SavedDevice): List<TransportKind> = buildList {
        if (d.bleAddress != null) add(TransportKind.BLE)
        if (d.host != null) { add(TransportKind.WEBSOCKET); add(TransportKind.HTTP) }
        if (d.mqtt != null) add(TransportKind.MQTT)
    }

    fun preferred(d: SavedDevice): TransportKind? {
        val all = transports(d)
        return if (d.preferredTransport in all) d.preferredTransport else all.firstOrNull()
    }

    fun open(d: SavedDevice, via: TransportKind? = null) {
        openJob?.cancel()
        activeId = d.id
        connectError = null
        val k = via ?: preferred(d)
        if (k == null) {
            connectError = "This device has no saved way to connect. Add an address in Settings."
            return
        }
        activeTransport = k
        openJob = viewModelScope.launch {
            val (token, pw) = withContext(Dispatchers.IO) { store.token(d.id) to store.mqttPassword(d.id) }
            if (token == null) {
                connectError = "No access key is saved for this device."
                return@launch
            }
            connection.connect(d, k, token, pw)
            val s = connection.state.value
            if (s is ConnectionState.Failed && activeId == d.id) connectError = friendly(s.message)
        }
    }

    fun close() {
        openJob?.cancel()
        if (connection.state.value == ConnectionState.Connected) connection.releaseAll()
        connection.disconnect()
        activeId = null
        activeTransport = null
        connectError = null
    }

    private var resumeOnStart = false

    /** Activity stopped: release keys and drop the link so nothing is left held on the host. */
    fun onAppStop() {
        if (activeId == null || connection.state.value == ConnectionState.Idle) return
        if (connection.state.value == ConnectionState.Connected) connection.releaseAll()
        openJob?.cancel()
        connection.disconnect()
        resumeOnStart = true
    }

    fun onAppStart() {
        if (!resumeOnStart) return
        resumeOnStart = false
        val id = activeId ?: return
        if (stack.none { it is Route.Control && it.id == id }) return
        device(id)?.let { open(it, activeTransport) }
    }

    fun setPreferred(k: TransportKind, d: SavedDevice) {
        viewModelScope.launch { store.upsert(d.copy(preferredTransport = k)) }
    }

    fun upsert(d: SavedDevice) = viewModelScope.launch { store.upsert(d) }

    private fun syncDiscovery(found: List<DiscoveredDevice>) {
        for (f in found) {
            val lan = f.source as? Source.Lan ?: continue
            val id = f.deviceId ?: continue
            val d = device(id) ?: continue
            if (d.host == lan.host && d.port == lan.port) continue
            val h = d.host
            // Never replace a Tailscale or manually entered public address with a LAN one.
            if (h != null && !(h.endsWith(".local") || isPrivateIPv4(h))) continue
            viewModelScope.launch { store.upsert(d.copy(host = lan.host, port = lan.port)) }
        }
    }

    // ---------- management session shared by the settings screens ----------
    var settings by mutableStateOf<SettingsSession?>(null)
        private set

    fun settingsFor(d: SavedDevice): SettingsSession {
        settings?.let { if (it.deviceId == d.id) return it else it.close() }
        return SettingsSession(this, d.id).also { settings = it }
    }

    fun closeSettings() { settings?.close(); settings = null }

    override fun onCleared() {
        closeSettings()
        connection.disconnect()
        discovery.stop()
    }
}

class SettingsSession(private val app: AppModel, val deviceId: String) {
    var info by mutableStateOf<DeviceInfo?>(null)
    var config by mutableStateOf<DeviceConfig?>(null)
    var error by mutableStateOf<String?>(null)
    var loading by mutableStateOf(false)
    var rebootNeeded by mutableStateOf(false)
    private var own: RpcClient? = null

    suspend fun client(): RpcClient {
        val d = app.device(deviceId) ?: throw RpcException.Transport("Device removed")
        if (app.activeId == deviceId && app.connection.state.value == ConnectionState.Connected) app.connection.rpc?.let { return it }
        own?.let { return it }
        val (token, pw) = withContext(Dispatchers.IO) { app.store.token(d.id) to app.store.mqttPassword(d.id) }
        token ?: throw RpcException.Unauthorized()
        val kind = when {
            d.host != null -> TransportKind.HTTP
            d.mqtt != null -> TransportKind.MQTT
            else -> TransportKind.BLE
        }
        return DeviceSessions.openRpc(app.ctx, d, kind, token, pw).also { own = it }
    }

    suspend fun load() {
        loading = true
        try {
            val r = client()
            info = r.info()
            config = r.getConfig()
            error = null
        } catch (e: Exception) {
            error = friendly(e)
        } finally {
            loading = false
        }
    }

    suspend fun refreshInfo() { runCatching { info = client().info() } }

    suspend fun save(patch: JsonObject): Boolean =
        try {
            val r = client().setConfig(patch)
            config = r.config
            if (r.rebootRequired) rebootNeeded = true
            error = null
            true
        } catch (e: Exception) {
            error = friendly(e)
            false
        }

    fun close() {
        own?.let { DeviceSessions.close(it) }
        own = null
    }
}

fun isPrivateIPv4(s: String): Boolean {
    val p = s.split('.').mapNotNull { it.toIntOrNull() }
    if (p.size != 4) return false
    return p[0] == 10 || (p[0] == 192 && p[1] == 168) || (p[0] == 172 && p[1] in 16..31)
}

fun friendly(e: Throwable): String = when (e) {
    is RpcException.Unauthorized -> "The access key was not accepted."
    is RpcException.Timeout -> "The device didn’t answer in time."
    is RpcException.Remote -> when (e.code) {
        401 -> "The access key was not accepted."
        429 -> "Too many attempts. Wait 30 seconds and try again."
        503 -> "The device is busy (an update may be running)."
        else -> e.message ?: "The device returned error ${e.code}."
    }
    is RpcException -> e.message ?: "Connection problem."
    else -> e.message ?: e.javaClass.simpleName
}

fun friendly(message: String): String =
    if (message.contains("Unauthorized", true) || message.contains("401")) "The access key was not accepted." else message

val TransportKind.title: String get() = when (this) {
    TransportKind.BLE -> "Bluetooth"
    TransportKind.WEBSOCKET -> "WiFi"
    TransportKind.HTTP -> "HTTP"
    TransportKind.MQTT -> "MQTT"
}

val TransportKind.detail: String get() = when (this) {
    TransportKind.BLE -> "Direct, lowest latency nearby"
    TransportKind.WEBSOCKET -> "LAN or Tailscale, live socket"
    TransportKind.HTTP -> "One request per input. Slowest"
    TransportKind.MQTT -> "Through your broker"
}

package dev.scalexy.esphid.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.scalexy.esphid.core.DeviceSessions
import dev.scalexy.esphid.core.MqttSettings
import dev.scalexy.esphid.core.RpcClient
import dev.scalexy.esphid.core.RpcException
import dev.scalexy.esphid.core.SavedDevice
import dev.scalexy.esphid.core.TransportKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Add by LAN address / .local / Tailscale IP or MagicDNS name, or by MQTT broker. */
@Composable
fun ManualAddScreen(model: AppModel, mqtt: Boolean) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf("") }
    var port by remember { mutableStateOf("80") }
    var useTls by remember { mutableStateOf(false) }
    var tlsPort by remember { mutableStateOf("443") }
    var fingerprint by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var broker by remember { mutableStateOf("mqtts://") }
    var brokerUser by remember { mutableStateOf("") }
    var brokerPass by remember { mutableStateOf("") }
    var deviceId by remember { mutableStateOf("") }
    var prefix by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val canAdd = key.trim().length >= 16 && if (mqtt) broker.length > 9 && prefix.isNotBlank() else host.isNotBlank()

    fun cleanHost(s: String): String {
        var h = s.trim()
        for (p in listOf("http://", "https://", "ws://", "wss://")) if (h.lowercase().startsWith(p)) h = h.drop(p.length)
        return h.substringBefore('/')
    }

    suspend fun add() {
        busy = true
        error = null
        val token = key.trim()
        var rpc: RpcClient? = null
        try {
            var d: SavedDevice
            if (!mqtt) {
                val h = cleanHost(host)
                d = SavedDevice("pending", h, host = h, port = port.toIntOrNull() ?: 80, preferredTransport = TransportKind.WEBSOCKET)
                if (useTls) {
                    var pin = fingerprint.trim().uppercase()
                    if (pin.isEmpty()) {
                        val probe = DeviceSessions.openRpc(ctx, d, TransportKind.HTTP, token)
                        try { pin = probe.info().https?.fingerprint.orEmpty() } finally { DeviceSessions.close(probe) }
                        if (pin.isEmpty()) throw RpcException.Transport("HTTPS is off on the device, or it didn’t report a certificate.")
                    }
                    d = d.copy(httpsPort = tlsPort.toIntOrNull() ?: 443, tlsFingerprint = pin)
                }
                rpc = DeviceSessions.openRpc(ctx, d, TransportKind.HTTP, token)
            } else {
                val id = deviceId.trim().uppercase()
                d = SavedDevice(
                    id.ifEmpty { "pending" }, "ESPHID-$id",
                    mqtt = MqttSettings(broker.trim(), brokerUser.ifBlank { null }, prefix.trim()),
                    preferredTransport = TransportKind.MQTT,
                )
                rpc = DeviceSessions.openRpc(ctx, d, TransportKind.MQTT, token, brokerPass.ifEmpty { null })
            }
            rpc.getConfig() // proves the key
            val info = rpc.info()
            val id = info.id ?: throw RpcException.Decoding("Missing device ID")
            val saved = d.copy(
                id = id, name = info.name ?: "ESPHID-$id", bleAddress = model.device(id)?.bleAddress,
                fwVariant = info.fw_variant, otaRepo = info.ota?.repo,
            )
            withContext(Dispatchers.IO) {
                model.store.setToken(id, token)
                if (mqtt) model.store.setMqttPassword(id, brokerPass.ifEmpty { null })
            }
            model.store.upsert(saved)
            model.resetTo(Route.Control(id))
        } catch (e: Exception) {
            error = friendly(e)
        } finally {
            rpc?.let { DeviceSessions.close(it) }
            busy = false
        }
    }

    Column(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { model.pop() }) { Text("Cancel") }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            ContentColumn {
                ScreenHeader(if (mqtt) "Add by MQTT" else "Add by address")
                if (!mqtt) {
                    Field(host, { host = it }, "Address", mono = true, placeholder = "192.168.1.50 or desk.tailnet.ts.net", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    Field(port, { port = it.filter(Char::isDigit) }, "Port", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                    Hint("For Tailscale, use the device’s 100.x address or its MagicDNS name. Tailscale already encrypts the connection.")
                    ToggleRow("Use HTTPS", useTls) { useTls = it }
                    if (useTls) {
                        Field(tlsPort, { tlsPort = it.filter(Char::isDigit) }, "HTTPS port", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
                        Field(fingerprint, { fingerprint = it }, "Certificate fingerprint (optional)", mono = true, singleLine = false)
                        Hint("Leave the fingerprint empty to read it from the device over plain HTTP and trust it from now on.")
                    }
                } else {
                    Field(broker, { broker = it }, "Broker URI", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
                    Field(brokerUser, { brokerUser = it }, "Username", keyboard = KeyboardOptions(autoCorrectEnabled = false))
                    Field(brokerPass, { brokerPass = it }, "Password", visual = PasswordVisualTransformation(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                    Field(
                        deviceId,
                        { v -> deviceId = v; if (prefix.isEmpty() || prefix.startsWith("esphid/")) prefix = if (v.isBlank()) "" else "esphid/${v.trim().uppercase()}" },
                        "Device ID", mono = true, placeholder = "A1B2C3",
                    )
                    Field(prefix, { prefix = it }, "Topic prefix", mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
                    Hint("The device ID and prefix are on the device page under Connect → MQTT.")
                }
                Field(key, { key = it }, "Access key", mono = true, visual = PasswordVisualTransformation(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false))
                ErrorLine(error)
                PrimaryButton("Add device", { scope.launch { add() } }, enabled = canAdd, busy = busy)
            }
        }
    }
}

@Composable
fun ToggleRow(title: String, checked: Boolean, detail: String? = null, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, color = P.ink)
            if (detail != null) Hint(detail)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange, enabled = enabled,
            colors = SwitchDefaults.colors(checkedTrackColor = P.accentStrong, checkedThumbColor = P.surface, checkedBorderColor = P.accentStrong),
        )
    }
}

package dev.scalexy.esphid.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.scalexy.esphid.core.DeviceInfo
import dev.scalexy.esphid.core.DeviceSessions
import dev.scalexy.esphid.core.DiscoveredDevice
import dev.scalexy.esphid.core.RpcClient
import dev.scalexy.esphid.core.RpcException
import dev.scalexy.esphid.core.SavedDevice
import dev.scalexy.esphid.core.Source
import dev.scalexy.esphid.core.TokenGenerator
import dev.scalexy.esphid.core.TransportKind
import dev.scalexy.esphid.core.WifiNetwork
import dev.scalexy.esphid.core.toSavedDevice
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private enum class Step { CONNECTING, CLAIM, SIGN_IN, WIFI, RADIO, FINISHING, FAILED }

@Composable
fun OnboardScreen(model: AppModel, discovered: DiscoveredDevice) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val viaBle = discovered.source is Source.Ble
    val kind = if (viaBle) TransportKind.BLE else TransportKind.HTTP

    var step by remember { mutableStateOf(Step.CONNECTING) }
    var rpc by remember { mutableStateOf<RpcClient?>(null) }
    var info by remember { mutableStateOf<DeviceInfo?>(null) }
    var token by remember { mutableStateOf(TokenGenerator.make()) }
    var name by remember { mutableStateOf("") }
    var signInKey by remember { mutableStateOf("") }
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var ssid by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var radio by remember { mutableStateOf("wifi") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var note by remember { mutableStateOf("") }
    var copied by remember { mutableStateOf(false) }

    fun closeRpc() { rpc?.let { DeviceSessions.close(it) }; rpc = null }
    DisposableEffect(Unit) { onDispose { closeRpc() } }

    suspend fun connect() {
        step = Step.CONNECTING
        error = null
        try {
            closeRpc()
            val r = DeviceSessions.openRpc(ctx, discovered.toSavedDevice(), kind, null)
            rpc = r
            val i = r.info()
            info = i
            name = i.name ?: discovered.name
            step = if (i.claimed == false) Step.CLAIM else Step.SIGN_IN
        } catch (e: Exception) {
            error = friendly(e)
            step = Step.FAILED
        }
    }

    suspend fun authorize(key: String): RpcClient {
        val current = rpc
        if (viaBle && current != null) {
            current.auth(key)
            return current
        }
        closeRpc()
        val r = DeviceSessions.openRpc(ctx, discovered.toSavedDevice(), TransportKind.HTTP, key)
        try {
            r.getConfig() // proves the key
        } catch (e: Exception) {
            DeviceSessions.close(r)
            throw e
        }
        rpc = r
        return r
    }

    suspend fun scan() {
        val r = rpc ?: return
        scanning = true
        try {
            networks = r.scanWifi().filter { !it.ssid.isNullOrEmpty() }.distinctBy { it.ssid }
        } catch (_: Exception) {
        } finally {
            scanning = false
        }
    }

    fun makeSaved(i: DeviceInfo, ip: String?, radioMode: String): SavedDevice {
        var host = ip
        var port = 80
        var ble: String? = null
        when (val s = discovered.source) {
            is Source.Ble -> ble = s.address
            is Source.Lan -> if (host == null) { host = s.host; port = s.port }
        }
        if (radioMode == "ble") host = null
        if (radioMode == "wifi") ble = null
        return SavedDevice(
            id = i.id ?: discovered.deviceId ?: discovered.key,
            name = i.name ?: name.ifBlank { discovered.name },
            host = host, port = port, bleAddress = ble,
            preferredTransport = if (radioMode == "ble" || host == null) TransportKind.BLE else TransportKind.WEBSOCKET,
            fwVariant = i.fw_variant, otaRepo = i.ota?.repo,
        )
    }

    suspend fun claim() {
        val r = rpc ?: return
        busy = true
        error = null
        try {
            r.claim(token)
            val a = authorize(token)
            val i = a.info()
            info = i
            i.id?.let { id -> withContext(Dispatchers.IO) { model.store.setToken(id, token) } }
            if (i.wifi?.connected == true) { radio = "wifi"; step = Step.RADIO }
            else { step = Step.WIFI; scope.launch { scan() } }
        } catch (e: Exception) {
            error = friendly(e)
        } finally {
            busy = false
        }
    }

    suspend fun signIn() {
        val key = signInKey.trim()
        busy = true
        error = null
        try {
            val r = authorize(key)
            val i = r.info()
            val id = i.id ?: throw RpcException.Decoding("Missing device ID")
            val d = makeSaved(i, i.wifi?.ip, i.radio_mode ?: "both")
            withContext(Dispatchers.IO) { model.store.setToken(id, key) }
            model.store.upsert(d)
            closeRpc()
            model.resetTo(Route.Control(d.id))
        } catch (e: Exception) {
            error = friendly(e)
        } finally {
            busy = false
        }
    }

    suspend fun finish() {
        val r = rpc ?: return
        error = null
        step = Step.FINISHING
        try {
            var ip: String? = info?.wifi?.ip
            if (ssid.isNotEmpty()) {
                note = "Joining $ssid. This takes up to 30 seconds."
                r.setConfig(buildJsonObject { put("wifi", buildJsonObject { put("ssid", ssid); put("password", password) }) })
                ip = null
                val until = System.currentTimeMillis() + 40_000
                while (System.currentTimeMillis() < until && ip == null) {
                    delay(1500)
                    val i = runCatching { r.info() }.getOrNull() ?: continue
                    if (i.wifi?.connected == true && !i.wifi?.ip.isNullOrEmpty()) ip = i.wifi?.ip
                }
                if (ip == null) {
                    error = "Couldn’t join $ssid. Check the password and that it’s a 2.4 GHz network."
                    step = Step.WIFI
                    return
                }
            }
            note = "Saving settings."
            val trimmed = name.trim()
            val result = r.setConfig(buildJsonObject {
                put("radio_mode", radio)
                if (trimmed.isNotEmpty() && trimmed != info?.name) put("name", trimmed)
            })
            val fresh = runCatching { r.info() }.getOrNull() ?: info ?: DeviceInfo()
            val d = makeSaved(fresh, ip, radio)
            fresh.id?.let { id -> withContext(Dispatchers.IO) { model.store.setToken(id, token) } }
            model.store.upsert(d)
            if (result.rebootRequired) {
                note = "Restarting the device."
                runCatching { r.reboot(300) }
            }
            closeRpc()
            model.resetTo(Route.Control(d.id))
        } catch (e: Exception) {
            error = friendly(e)
            step = Step.RADIO
        }
    }

    LaunchedEffect(Unit) { connect() }

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { closeRpc(); model.pop() }, enabled = step != Step.FINISHING) { Text("Cancel") }
        }
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            ContentColumn {
                if (info?.claimed == false && step in listOf(Step.CLAIM, Step.WIFI, Step.RADIO, Step.FINISHING)) {
                    StepBar(when (step) { Step.CLAIM -> 0; Step.WIFI -> 1; else -> 2 })
                }
                when (step) {
                    Step.CONNECTING -> {
                        ScreenHeader(
                            "Connecting to ${discovered.name}",
                            if (viaBle) "If Android asks to pair, tap Pair. The connection is encrypted." else "Reaching the device on this network.",
                        )
                        Box(Modifier.fillMaxWidth().padding(top = 16.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = P.accentStrong) }
                    }
                    Step.CLAIM -> {
                        ScreenHeader(
                            "Set up ${info?.name ?: discovered.name}",
                            "Choose an access key. The app and the device page use it to control this device, and it can’t be shown again later. This app keeps an encrypted copy.",
                        )
                        Field(name, { name = it }, "Name")
                        Field(
                            token, { token = it }, "Access key", mono = true,
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                            trailing = { IconButton(onClick = { token = TokenGenerator.make() }) { Icon(Ic.Refresh, "Generate a new key", tint = P.ink2) } },
                        )
                        TextButton(onClick = { copy(ctx, token); copied = true }) { Text(if (copied) "Copied" else "Copy key") }
                        ErrorLine(error)
                        PrimaryButton("Save access key", { scope.launch { claim() } }, enabled = validToken(token), busy = busy)
                    }
                    Step.SIGN_IN -> {
                        ScreenHeader(
                            "Add ${info?.name ?: discovered.name}",
                            "This device is already set up. Enter its access key. You can find it in your password manager, or reset it from the device page.",
                        )
                        Field(
                            signInKey, { signInKey = it }, "Access key", mono = true,
                            visual = PasswordVisualTransformation(),
                            keyboard = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Go, autoCorrectEnabled = false),
                        )
                        ErrorLine(error)
                        PrimaryButton("Add device", { scope.launch { signIn() } }, enabled = signInKey.trim().length >= 16, busy = busy)
                    }
                    Step.WIFI -> {
                        ScreenHeader("Join WiFi", "WiFi lets you connect over your network, from anywhere with Tailscale, or through MQTT. The device needs a 2.4 GHz network.")
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Networks", fontWeight = FontWeight.Medium, color = P.ink2, fontSize = 14.sp)
                            Spacer(Modifier.weight(1f))
                            if (scanning) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp, color = P.ink3)
                            else TextButton(onClick = { scope.launch { scan() } }) { Text("Scan again") }
                        }
                        NetworkList(networks, ssid) { ssid = it }
                        Field(ssid, { ssid = it }, "Network name", keyboard = KeyboardOptions(autoCorrectEnabled = false))
                        Field(password, { password = it }, "Password", visual = PasswordVisualTransformation(), keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
                        ErrorLine(error)
                        PrimaryButton("Continue", { error = null; radio = "wifi"; step = Step.RADIO }, enabled = ssid.isNotEmpty())
                        if (viaBle) SecondaryButton("Skip — use Bluetooth only", { ssid = ""; password = ""; radio = "ble"; step = Step.RADIO }, Modifier.fillMaxWidth())
                    }
                    Step.RADIO -> {
                        ScreenHeader(
                            "How will you connect?",
                            "The device has one 2.4 GHz radio. Running WiFi and Bluetooth together adds latency to both, so pick the one you’ll use most. You can change it later.",
                        )
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ChoiceCard(radio == "ble", "Bluetooth only", "Fastest from a phone nearby. WiFi, Tailscale and MQTT turn off.") { radio = "ble" }
                            ChoiceCard(
                                radio == "wifi", "WiFi only", "Fastest over your network, Tailscale and MQTT. Bluetooth turns off.",
                                enabled = ssid.isNotEmpty() || info?.wifi?.connected == true,
                            ) { radio = "wifi" }
                            ChoiceCard(radio == "both", "Both", "Most flexible. Each one gets a little slower.") { radio = "both" }
                        }
                        ErrorLine(error)
                        PrimaryButton("Finish setup", { scope.launch { finish() } })
                    }
                    Step.FINISHING -> {
                        ScreenHeader("Finishing", note)
                        Box(Modifier.fillMaxWidth().padding(top = 8.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = P.accentStrong) }
                    }
                    Step.FAILED -> {
                        ScreenHeader("Couldn’t connect", error)
                        PrimaryButton("Try again", { scope.launch { connect() } })
                    }
                }
            }
        }
    }
}

@Composable
private fun StepBar(index: Int) {
    val labels = listOf("Access key", "WiFi", "Connection", "Ready")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(Modifier.fillMaxWidth().height(3.dp).clip(RoundedCornerShape(2.dp)).background(if (i <= index) P.accentStrong else P.line))
                Text(l, fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (i <= index) P.ink2 else P.ink3, maxLines = 1)
            }
        }
    }
}

@Composable
fun NetworkList(networks: List<WifiNetwork>, selected: String, onPick: (String) -> Unit) {
    if (networks.isEmpty()) {
        Text("No networks found yet.", color = P.ink3, fontSize = 14.sp)
        return
    }
    val shape = RoundedCornerShape(12.dp)
    Column(Modifier.fillMaxWidth().clip(shape).border(1.dp, P.line, shape)) {
        networks.take(12).forEachIndexed { i, n ->
            if (i > 0) HorizontalDivider(color = P.line)
            Row(
                Modifier.fillMaxWidth().background(if (n.ssid == selected) P.accentWash else P.surface)
                    .clickable { onPick(n.ssid ?: "") }.heightIn(min = 48.dp).padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(n.ssid ?: "", color = P.ink, maxLines = 1, modifier = Modifier.weight(1f))
                if ((n.auth ?: "open") != "open") { Icon(Ic.Lock, "Secured", tint = P.ink3, modifier = Modifier.size(14.dp)); Spacer(Modifier.width(10.dp)) }
                SignalBars(n.rssi ?: -90)
            }
        }
    }
}

fun validToken(t: String) = t.length in 16..64 && t.all { it.code in 0x21..0x7E }

fun copy(ctx: Context, text: String) {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("ESPHID access key", text))
}

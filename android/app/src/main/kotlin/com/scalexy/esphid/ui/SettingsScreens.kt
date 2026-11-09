package com.scalexy.esphid.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scalexy.esphid.core.FirmwareRelease
import com.scalexy.esphid.core.FirmwareUpdates
import com.scalexy.esphid.core.MqttSettings
import com.scalexy.esphid.core.SavedDevice
import com.scalexy.esphid.core.TokenGenerator
import com.scalexy.esphid.core.WifiNetwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsScaffold(model: AppModel, title: String, content: @Composable ColumnScope.() -> Unit) {
    Scaffold(
        containerColor = P.paper,
        topBar = {
            TopAppBar(
                title = { Text(title, maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { model.pop() }) { Icon(Ic.Back, "Back", tint = P.ink) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = P.paper),
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding().navigationBarsPadding().verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.fillMaxWidth().padding(16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
        }
    }
}

@Composable
private fun NavRow(title: String, value: String? = null, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, color = P.ink, fontSize = 16.sp)
        Spacer(Modifier.weight(1f))
        if (value != null) Text(value, color = P.ink3, fontSize = 14.sp, modifier = Modifier.padding(end = 6.dp))
        Icon(Ic.ChevronRight, null, tint = P.ink3, modifier = Modifier.size(18.dp))
    }
}

@Composable
fun SettingsScreen(model: AppModel, id: String) {
    val devices by model.store.devices.collectAsState()
    val d = devices.firstOrNull { it.id == id } ?: return
    val s = remember(id) { model.settingsFor(d) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var name by remember(s.config) { mutableStateOf(s.config?.name ?: d.name) }
    var delayMs by remember(s.config) { mutableFloatStateOf((s.config?.keyboard?.type_delay_ms ?: 0).toFloat()) }
    var confirmReset by remember { mutableStateOf(false) }
    var confirmRemove by remember { mutableStateOf(false) }
    var confirmKey by remember { mutableStateOf(false) }
    var newKey by remember { mutableStateOf<String?>(null) }
    var nameSaved by remember { mutableStateOf(false) }

    LaunchedEffect(id) { if (s.info == null) s.load() }

    fun reboot() = scope.launch {
        try {
            s.client().reboot(300)
            s.rebootNeeded = false
            model.closeSettings()
            model.pop()
            delay(6000)
            if (model.activeId == id) model.device(id)?.let { model.open(it, model.activeTransport) }
        } catch (e: Exception) { s.error = friendly(e) }
    }

    SettingsScaffold(model, d.name) {
        if (s.rebootNeeded) {
            Card {
                Text("Restart to apply changes", fontWeight = FontWeight.SemiBold, color = P.ink)
                Hint("Radio, HTTPS and Tailscale changes take effect after a restart.")
                PrimaryButton("Restart now", { reboot() })
            }
        }
        ErrorLine(s.error)
        if (s.loading && s.info == null) LinearProgressIndicator(Modifier.fillMaxWidth(), color = P.accentStrong, trackColor = P.line)

        s.info?.let { i ->
            SectionTitle("Status")
            Card(padding = 14) {
                Column {
                    ReadoutRow("Firmware", listOfNotNull(i.fw_version, i.fw_variant).joinToString(" · "))
                    ReadoutRow("USB host", if (i.usb?.mounted == true) (if (i.usb?.suspended == true) "Asleep" else "Connected") else "Not connected", if (i.usb?.mounted == true) P.ok else P.bad)
                    i.wifi?.takeIf { it.mode != "off" }?.let { w -> ReadoutRow("WiFi", if (w.connected == true) "${w.ssid ?: ""} · ${w.ip ?: ""}" else "Not connected") }
                    if (i.tailscale?.enabled == true) ReadoutRow("Tailscale", listOfNotNull(i.tailscale?.state, i.tailscale?.ip?.ifEmpty { null }).joinToString(" · "))
                    if (i.mqtt?.enabled == true) ReadoutRow("MQTT", if (i.mqtt?.connected == true) "Connected" else "Not connected")
                    i.uptime_s?.let { ReadoutRow("Uptime", uptime(it)) }
                }
            }
        }

        SectionTitle("Device connections")
        Card {
            Hint("One radio serves WiFi and Bluetooth. Using only one gives the lowest latency. Takes effect after a restart.")
            val rm = s.config?.radio_mode ?: "both"
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Triple("both", "WiFi + Bluetooth", "Most flexible"), Triple("wifi", "WiFi only", "Fastest over LAN, Tailscale and MQTT"), Triple("ble", "Bluetooth only", "Fastest from a phone nearby")).forEach { (v, t, dd) ->
                    ChoiceCard(rm == v, t, dd) { scope.launch { s.save(buildJsonObject { put("radio_mode", v) }) } }
                }
            }
            HorizontalDivider(color = P.line)
            Column {
                NavRow("WiFi", s.config?.wifi?.ssid) { model.push(Route.SettingsPage(id, Page.WIFI)) }
                NavRow("Tailscale", if (s.config?.tailscale?.enabled == true) "On" else "Off") { model.push(Route.SettingsPage(id, Page.TAILSCALE)) }
                NavRow("MQTT", if (s.config?.mqtt?.enabled == true) "On" else "Off") { model.push(Route.SettingsPage(id, Page.MQTT)) }
                NavRow("HTTPS", if (s.config?.https?.enabled == true) "On" else "Off") { model.push(Route.SettingsPage(id, Page.HTTPS)) }
            }
        }

        SectionTitle("This app")
        Card {
            val kinds = model.transports(d)
            if (kinds.isNotEmpty()) {
                Text("Connect over", color = P.ink2, fontSize = 14.sp)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    kinds.forEach { k -> ChoiceCard(model.preferred(d) == k, k.title, k.detail) { model.setPreferred(k, d) } }
                }
                HorizontalDivider(color = P.line)
            }
            Column {
                NavRow("Address", d.host ?: "None") { model.push(Route.SettingsPage(id, Page.ADDRESS)) }
                NavRow("Pointer and haptics") { model.push(Route.Pointer) }
            }
        }

        SectionTitle("Device")
        Card {
            Field(name, { name = it; nameSaved = false }, "Name")
            SecondaryButton(if (nameSaved) "Saved" else "Save name", {
                val n = name.trim()
                if (n.isNotEmpty()) scope.launch { if (s.save(buildJsonObject { put("name", n) })) { model.store.upsert(d.copy(name = n)); nameSaved = true } }
            }, Modifier.fillMaxWidth())
            ReadoutRow("Keyboard layout", "English (US)")
            Column {
                Row { Text("Delay between typed characters", color = P.ink, modifier = Modifier.weight(1f)); Text("${delayMs.toInt()} ms", style = ReadoutStyle, color = P.ink2) }
                Slider(
                    value = delayMs, onValueChange = { delayMs = it }, valueRange = 0f..200f, steps = 39,
                    onValueChangeFinished = { scope.launch { s.save(buildJsonObject { put("keyboard", buildJsonObject { put("type_delay_ms", delayMs.toInt()) }) }) } },
                    colors = SliderDefaults.colors(thumbColor = P.accentStrong, activeTrackColor = P.accentStrong, inactiveTrackColor = P.line),
                )
                Hint("0 is fastest. Raise it if a slow computer drops characters.")
            }
        }

        FirmwareCard(model, s, d)

        SectionTitle("Access key")
        Card { SecondaryButton("Change access key…", { confirmKey = true }, Modifier.fillMaxWidth()) }

        SectionTitle("Power")
        Card {
            SecondaryButton("Restart device", { reboot() }, Modifier.fillMaxWidth())
            TextButton(onClick = { confirmReset = true }, Modifier.fillMaxWidth()) { Text("Factory reset…", color = P.bad) }
            TextButton(onClick = { confirmRemove = true }, Modifier.fillMaxWidth()) { Text("Remove from this app", color = P.bad) }
        }
    }

    if (confirmReset) AlertDialog(
        onDismissRequest = { confirmReset = false },
        title = { Text("Erase this device?") },
        text = { Text("WiFi, the access key, Bluetooth pairings and every setting are erased. The device restarts into setup mode.") },
        confirmButton = {
            TextButton(onClick = {
                confirmReset = false
                scope.launch {
                    try {
                        s.client().factoryReset()
                        model.close(); model.closeSettings(); model.store.remove(id); model.resetTo()
                    } catch (e: Exception) { s.error = friendly(e) }
                }
            }) { Text("Erase and restart", color = P.bad) }
        },
        dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
    )
    if (confirmRemove) AlertDialog(
        onDismissRequest = { confirmRemove = false },
        title = { Text("Remove from this app?") },
        text = { Text("The device keeps its settings. You’ll need its access key to add it again.") },
        confirmButton = { TextButton(onClick = { confirmRemove = false; model.close(); model.closeSettings(); scope.launch { model.store.remove(id) }; model.resetTo() }) { Text("Remove", color = P.bad) } },
        dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Cancel") } },
    )
    if (confirmKey) AlertDialog(
        onDismissRequest = { confirmKey = false },
        title = { Text("Change access key?") },
        text = { Text("Other apps and browsers using the current key will be signed out.") },
        confirmButton = {
            TextButton(onClick = {
                confirmKey = false
                val key = TokenGenerator.make()
                scope.launch {
                    try {
                        s.client().setToken(key)
                        withContext(Dispatchers.IO) { model.store.setToken(id, key) }
                        s.close()
                        newKey = key
                        if (model.activeId == id) model.open(d, model.activeTransport)
                    } catch (e: Exception) { s.error = friendly(e) }
                }
            }) { Text("Change") }
        },
        dismissButton = { TextButton(onClick = { confirmKey = false }) { Text("Cancel") } },
    )
    newKey?.let { k ->
        var copied by remember { mutableStateOf(false) }
        AlertDialog(
            onDismissRequest = { newKey = null },
            title = { Text("New access key") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("This app already uses it. Update any other app or browser, then store it in your password manager.")
                    Text(k, style = ReadoutStyle.copy(fontSize = 16.sp), color = P.ink)
                }
            },
            confirmButton = { TextButton(onClick = { copy(ctx, k); copied = true }) { Text(if (copied) "Copied" else "Copy") } },
            dismissButton = { TextButton(onClick = { newKey = null }) { Text("Done") } },
        )
    }
}

@Composable
private fun FirmwareCard(model: AppModel, s: SettingsSession, d: SavedDevice) {
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    var available by remember { mutableStateOf<FirmwareRelease?>(null) }
    var phase by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }

    fun install(url: String) = scope.launch {
        note = null
        phase = "Starting"
        progress = 0f
        try {
            val r = s.client()
            r.startOta(url)
            while (true) {
                delay(700)
                val st = r.otaStatus()
                when (st.state) {
                    "downloading" -> { phase = "Downloading"; progress = (st.progress ?: 0) / 100f }
                    "verifying" -> { phase = "Verifying"; progress = 1f }
                    "done" -> {
                        phase = null; available = null
                        note = "Installed. The device is restarting and will reconnect."
                        delay(8000)
                        if (model.activeId == d.id) model.device(d.id)?.let { model.open(it, model.activeTransport) }
                        s.refreshInfo()
                        return@launch
                    }
                    "error" -> { phase = null; note = st.error ?: "The update failed."; return@launch }
                }
            }
        } catch (e: Exception) {
            phase = null
            note = friendly(e)
        }
    }

    SectionTitle("Firmware")
    Card {
        ReadoutRow("Installed", s.info?.fw_version ?: "—")
        val p = phase
        val a = available
        when {
            p != null -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row { Text(p, color = P.ink, modifier = Modifier.weight(1f)); Text("${(progress * 100).toInt()}%", style = ReadoutStyle, color = P.ink2) }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth(), color = P.accentStrong, trackColor = P.line)
                Hint("Keep the device plugged in. It restarts when the update finishes.")
            }
            a != null -> PrimaryButton("Install ${a.version}", { install(a.assetUrl) })
            else -> SecondaryButton(if (checking) "Checking…" else "Check for updates", {
                scope.launch {
                    checking = true
                    note = null
                    try {
                        val repo = s.info?.ota?.repo ?: d.otaRepo.orEmpty()
                        val variant = s.info?.fw_variant ?: d.fwVariant.orEmpty()
                        if (repo.isEmpty() || variant.isEmpty()) note = "This firmware build has no release source. Install from a URL instead."
                        else {
                            val r = FirmwareUpdates.latest(repo, variant)
                            when {
                                r == null -> note = "No release has firmware for $variant yet."
                                FirmwareUpdates.isNewer(r.version, s.info?.fw_version ?: "0") -> { available = r; note = r.notes.take(280).ifBlank { null } }
                                else -> note = "You’re on the latest release."
                            }
                        }
                    } catch (e: Exception) { note = friendly(e) } finally { checking = false }
                }
            }, Modifier.fillMaxWidth(), enabled = !checking)
        }
        note?.let { Hint(it) }
        NavRowInCard("Install from a URL") { model.push(Route.SettingsPage(d.id, Page.OTA_URL)) }
        Hint("Updates install to the spare slot. If the new version fails to start, the device rolls back by itself.")
    }
}

@Composable
private fun NavRowInCard(title: String, onClick: () -> Unit) = NavRow(title, null, onClick)

@Composable
fun SettingsPageScreen(model: AppModel, id: String, page: Page) {
    val devices by model.store.devices.collectAsState()
    val d = devices.firstOrNull { it.id == id } ?: return
    val s = remember(id) { model.settingsFor(d) }
    LaunchedEffect(id) { if (s.config == null) s.load() }
    val title = when (page) {
        Page.WIFI -> "WiFi"; Page.TAILSCALE -> "Tailscale"; Page.MQTT -> "MQTT"; Page.HTTPS -> "HTTPS"; Page.ADDRESS -> "Address"; Page.OTA_URL -> "Install from URL"
    }
    SettingsScaffold(model, title) {
        ErrorLine(s.error)
        when (page) {
            Page.WIFI -> WifiPage(model, s)
            Page.TAILSCALE -> TailscalePage(model, s, d)
            Page.MQTT -> MqttPage(model, s, d)
            Page.HTTPS -> HttpsPage(model, s, d)
            Page.ADDRESS -> AddressPage(model, d)
            Page.OTA_URL -> OtaUrlPage(model, s)
        }
    }
}

@Composable
private fun SaveButton(saved: Boolean, enabled: Boolean = true, onClick: () -> Unit) =
    PrimaryButton(if (saved) "Saved" else "Save", onClick, enabled = enabled)

@Composable
private fun WifiPage(model: AppModel, s: SettingsSession) {
    val scope = rememberCoroutineScope()
    var ssid by remember(s.config) { mutableStateOf(s.config?.wifi?.ssid.orEmpty()) }
    var password by remember { mutableStateOf("") }
    var hostname by remember(s.config) { mutableStateOf(s.config?.wifi?.hostname.orEmpty()) }
    var fallback by remember(s.config) { mutableStateOf(s.config?.wifi?.ap_fallback ?: true) }
    var networks by remember { mutableStateOf<List<WifiNetwork>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    Card {
        SecondaryButton(if (scanning) "Scanning…" else "Scan for networks", {
            scope.launch {
                scanning = true
                try { networks = s.client().scanWifi().filter { !it.ssid.isNullOrEmpty() }.distinctBy { it.ssid } } catch (e: Exception) { s.error = friendly(e) } finally { scanning = false }
            }
        }, Modifier.fillMaxWidth(), enabled = !scanning)
        if (networks.isNotEmpty()) NetworkList(networks, ssid) { ssid = it }
        Field(ssid, { ssid = it; saved = false }, "Network name", keyboard = KeyboardOptions(autoCorrectEnabled = false))
        Field(password, { password = it; saved = false }, "Password", visual = PasswordVisualTransformation(),
            placeholder = if (s.config?.wifi?.password_set == true) "Saved — leave blank to keep" else null, keyboard = KeyboardOptions(keyboardType = KeyboardType.Password))
        Field(hostname, { hostname = it; saved = false }, "Hostname", mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        ToggleRow("Setup hotspot as fallback", fallback, "Starts if WiFi can’t connect for a minute, so you can fix settings from any phone.") { fallback = it; saved = false }
        SaveButton(saved, enabled = ssid.isNotEmpty()) {
            scope.launch {
                saved = s.save(buildJsonObject {
                    put("wifi", buildJsonObject {
                        put("ssid", ssid); put("hostname", hostname); put("ap_fallback", fallback)
                        // A different network always carries its password; empty means an open network.
                        if (password.isNotEmpty() || ssid != s.config?.wifi?.ssid.orEmpty()) put("password", password)
                    })
                })
                if (saved) password = ""
            }
        }
        Hint("Leave the password empty for an open network. If this app is connected over WiFi, it reconnects after the device changes networks.")
    }
}

@Composable
private fun TailscalePage(model: AppModel, s: SettingsSession, d: SavedDevice) {
    val scope = rememberCoroutineScope()
    val c = s.config?.tailscale
    var enabled by remember(s.config) { mutableStateOf(c?.enabled ?: false) }
    var key by remember { mutableStateOf("") }
    var host by remember(s.config) { mutableStateOf(c?.hostname.orEmpty()) }
    var ctl by remember(s.config) { mutableStateOf(c?.control_url.orEmpty()) }
    var saved by remember { mutableStateOf(false) }
    s.info?.tailscale?.takeIf { it.enabled == true }?.let { t ->
        Card(padding = 14) {
            Column {
                ReadoutRow("State", t.state ?: "—", if (t.state == "connected") P.ok else null)
                if (!t.ip.isNullOrEmpty()) ReadoutRow("Tailnet IP", t.ip!!)
            }
            val ip = t.ip
            if (!ip.isNullOrEmpty() && d.host != ip) SecondaryButton("Use $ip in this app", { scope.launch { model.store.upsert(d.copy(host = ip, port = 80)) } }, Modifier.fillMaxWidth())
        }
    }
    Card {
        ToggleRow("Enable Tailscale", enabled) { enabled = it; saved = false }
        Field(key, { key = it; saved = false }, "Auth key", mono = true, visual = PasswordVisualTransformation(),
            placeholder = if (c?.auth_key_set == true) "Saved — leave blank to keep" else "tskey-auth-…")
        Field(host, { host = it; saved = false }, "Tailnet hostname", mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        Field(ctl, { ctl = it; saved = false }, "Control server (only for Headscale)", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Hint("Create an auth key in the Tailscale admin console under Settings → Keys. A PSRAM board is recommended. Changes apply after a restart.")
        SaveButton(saved) {
            scope.launch {
                saved = s.save(buildJsonObject {
                    put("tailscale", buildJsonObject {
                        put("enabled", enabled); put("hostname", host); put("control_url", ctl)
                        if (key.isNotEmpty()) put("auth_key", key)
                    })
                })
                if (saved) key = ""
            }
        }
    }
}

@Composable
private fun MqttPage(model: AppModel, s: SettingsSession, d: SavedDevice) {
    val scope = rememberCoroutineScope()
    val c = s.config?.mqtt
    var enabled by remember(s.config) { mutableStateOf(c?.enabled ?: false) }
    var uri by remember(s.config) { mutableStateOf(c?.uri.orEmpty()) }
    var user by remember(s.config) { mutableStateOf(c?.username.orEmpty()) }
    var pass by remember { mutableStateOf("") }
    var cid by remember(s.config) { mutableStateOf(c?.client_id.orEmpty()) }
    var prefix by remember(s.config) { mutableStateOf(c?.topic_prefix ?: "esphid/${d.id}") }
    var requireToken by remember(s.config) { mutableStateOf(c?.require_token ?: true) }
    var useInApp by remember { mutableStateOf(d.mqtt != null || c?.enabled != true) }
    var saved by remember { mutableStateOf(false) }
    Card {
        ToggleRow("Enable MQTT", enabled) { enabled = it; saved = false }
        Field(uri, { uri = it; saved = false }, "Broker URI", mono = true, placeholder = "mqtts://broker.example.com:8883", keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Field(user, { user = it; saved = false }, "Username", keyboard = KeyboardOptions(autoCorrectEnabled = false))
        Field(pass, { pass = it; saved = false }, "Password", visual = PasswordVisualTransformation(), placeholder = if (c?.password_set == true) "Saved — leave blank to keep" else null)
        Field(prefix, { prefix = it; saved = false }, "Topic prefix", mono = true, keyboard = KeyboardOptions(autoCorrectEnabled = false))
        Field(cid, { cid = it; saved = false }, "Client ID", mono = true, placeholder = "Automatic", keyboard = KeyboardOptions(autoCorrectEnabled = false))
        ToggleRow("Require access key in messages", requireToken, "Broker accounts alone can’t type on the computer.") { requireToken = it; saved = false }
        ToggleRow("Use MQTT from this app", useInApp, "Adds MQTT as a connection option for this device.") { useInApp = it; saved = false }
        SaveButton(saved) {
            scope.launch {
                saved = s.save(buildJsonObject {
                    put("mqtt", buildJsonObject {
                        put("enabled", enabled); put("uri", uri); put("username", user); put("client_id", cid)
                        put("topic_prefix", prefix); put("require_token", requireToken)
                        if (pass.isNotEmpty()) put("password", pass)
                    })
                })
                if (!saved) return@launch
                val cur = model.device(d.id) ?: d
                val next = when {
                    useInApp && enabled && uri.isNotBlank() -> cur.copy(mqtt = MqttSettings(uri, user.ifBlank { null }, prefix))
                    !useInApp -> cur.copy(mqtt = null)
                    else -> cur
                }
                withContext(Dispatchers.IO) {
                    if (useInApp && pass.isNotEmpty()) model.store.setMqttPassword(d.id, pass)
                    if (!useInApp) model.store.setMqttPassword(d.id, null)
                }
                model.store.upsert(next)
                pass = ""
            }
        }
    }
}

@Composable
private fun HttpsPage(model: AppModel, s: SettingsSession, d: SavedDevice) {
    val scope = rememberCoroutineScope()
    var enabled by remember(s.config) { mutableStateOf(s.config?.https?.enabled ?: false) }
    var port by remember(s.config) { mutableStateOf((s.config?.https?.port ?: 443).toString()) }
    var saved by remember { mutableStateOf(false) }
    var regenerating by remember { mutableStateOf(false) }
    val fp = s.info?.https?.fingerprint.orEmpty()
    Card {
        ToggleRow("Enable HTTPS", enabled) { enabled = it; saved = false }
        Field(port, { port = it.filter(Char::isDigit); saved = false }, "Port", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Hint("Serves the API and device page over TLS with a certificate made on the device. Changes apply after a restart.")
        SaveButton(saved) { scope.launch { saved = s.save(buildJsonObject { put("https", buildJsonObject { put("enabled", enabled); put("port", port.toIntOrNull() ?: 443) }) }) } }
    }
    if (fp.isNotEmpty()) {
        SectionTitle("Certificate fingerprint (SHA-256)")
        Card {
            Text(fp, style = ReadoutSmall, color = P.ink)
            SecondaryButton(if (regenerating) "Making a new certificate…" else "New certificate", {
                scope.launch {
                    regenerating = true
                    try {
                        val newFp = s.client().regenerateHttpsCertificate()
                        s.refreshInfo()
                        val cur = model.device(d.id) ?: d
                        if (cur.httpsPort != null) model.store.upsert(cur.copy(tlsFingerprint = newFp))
                    } catch (e: Exception) { s.error = friendly(e) } finally { regenerating = false }
                }
            }, Modifier.fillMaxWidth(), enabled = !regenerating)
            val using = (model.device(d.id) ?: d).httpsPort != null
            ToggleRow("Use HTTPS from this app", using, "The app trusts exactly this certificate. Over Tailscale, plain WiFi is already encrypted.") { on ->
                val cur = model.device(d.id) ?: d
                scope.launch { model.store.upsert(if (on) cur.copy(httpsPort = port.toIntOrNull() ?: 443, tlsFingerprint = fp) else cur.copy(httpsPort = null, tlsFingerprint = null)) }
            }
        }
    }
}

@Composable
private fun AddressPage(model: AppModel, d: SavedDevice) {
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(d.host.orEmpty()) }
    var port by remember { mutableStateOf(d.port.toString()) }
    var saved by remember { mutableStateOf(false) }
    Card {
        Field(host, { host = it; saved = false }, "IP, .local or Tailscale name", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Field(port, { port = it.filter(Char::isDigit); saved = false }, "Port", mono = true, keyboard = KeyboardOptions(keyboardType = KeyboardType.Number))
        Hint("Leave empty to connect only over Bluetooth or MQTT.")
        SaveButton(saved) {
            val cur = model.device(d.id) ?: d
            scope.launch { model.store.upsert(cur.copy(host = host.trim().ifEmpty { null }, port = port.toIntOrNull() ?: 80)); saved = true }
        }
    }
}

@Composable
private fun OtaUrlPage(model: AppModel, s: SettingsSession) {
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(s.info?.ota?.latest_url.orEmpty()) }
    var status by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    Card {
        Field(url, { url = it }, "Firmware URL (.bin)", mono = true, singleLine = false, keyboard = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false))
        Hint("The device downloads the file itself, so the URL must be reachable from its network.")
        PrimaryButton("Install", {
            scope.launch {
                busy = true
                try {
                    val r = s.client()
                    r.startOta(url.trim())
                    while (true) {
                        delay(700)
                        val st = r.otaStatus()
                        status = when (st.state) {
                            "downloading" -> "Downloading ${st.progress ?: 0}%"
                            "verifying" -> "Verifying"
                            "done" -> "Installed. The device is restarting."
                            "error" -> st.error ?: "The update failed."
                            else -> status
                        }
                        if (st.state == "done" || st.state == "error") break
                    }
                } catch (e: Exception) { status = friendly(e) } finally { busy = false }
            }
        }, enabled = url.startsWith("http://") || url.startsWith("https://"), busy = busy)
        status?.let { Hint(it) }
    }
}

@Composable
fun PointerScreen(model: AppModel) {
    SettingsScaffold(model, "Pointer and haptics") {
        Card {
            Column {
                Row { Text("Speed", color = P.ink, modifier = Modifier.weight(1f)); Text("%.1f×".format(model.sensitivity), style = ReadoutStyle, color = P.ink2) }
                Slider(model.sensitivity, { model.setPointer(s = it) }, valueRange = 0.4f..4f, colors = SliderDefaults.colors(thumbColor = P.accentStrong, activeTrackColor = P.accentStrong, inactiveTrackColor = P.line))
            }
            Column {
                Row { Text("Acceleration", color = P.ink, modifier = Modifier.weight(1f)); Text("%.1f".format(model.acceleration), style = ReadoutStyle, color = P.ink2) }
                Slider(model.acceleration, { model.setPointer(a = it) }, valueRange = 0f..3f, colors = SliderDefaults.colors(thumbColor = P.accentStrong, activeTrackColor = P.accentStrong, inactiveTrackColor = P.line))
                Hint("Acceleration makes fast swipes travel further, so you can cross a large screen and still place the pointer precisely.")
            }
        }
        Card {
            ToggleRow("Natural scrolling", model.natural, "Content follows your fingers.") { model.setPointer(n = it) }
            ToggleRow("Haptics", model.haptics) { model.setPointer(h = it) }
        }
    }
}

private fun uptime(s: Long): String {
    val d = s / 86400; val h = (s % 86400) / 3600; val m = (s % 3600) / 60
    return if (d > 0) "${d}d ${h}h" else if (h > 0) "${h}h ${m}m" else "${m}m ${s % 60}s"
}

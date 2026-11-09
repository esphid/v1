package com.scalexy.esphid.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.scalexy.esphid.core.DiscoveredDevice
import com.scalexy.esphid.core.SavedDevice
import com.scalexy.esphid.core.Source
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DevicesScreen(model: AppModel) {
    val saved by model.store.devices.collectAsState()
    val found by model.discovery.found.collectAsState()
    val scanning by model.discovery.isScanning.collectAsState()
    var menu by remember { mutableStateOf(false) }
    var removing by remember { mutableStateOf<SavedDevice?>(null) }
    val scope = rememberCoroutineScope()
    var permTick by remember { mutableIntStateOf(0) }
    val missing = remember(permTick) { model.discovery.missingPermissions() }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        permTick++
        model.discovery.stop()
        model.discovery.start()
    }

    DisposableEffect(Unit) {
        model.discovery.start()
        onDispose { model.discovery.stop() }
    }

    val nearby = found.filter { f ->
        val byId = f.deviceId != null && saved.any { it.id == f.deviceId }
        val byBle = (f.source as? Source.Ble)?.let { b -> saved.any { it.bleAddress == b.address } } ?: false
        !byId && !byBle
    }

    Scaffold(
        containerColor = P.paper,
        topBar = {
            TopAppBar(
                title = { Text("ESPHID", fontWeight = FontWeight.SemiBold) },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = P.paper),
                actions = {
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Ic.Add, "Add device", tint = P.ink) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text("Address or Tailscale name") },
                                leadingIcon = { Icon(Ic.Globe, null) },
                                onClick = { menu = false; model.push(Route.ManualAdd(mqtt = false)) },
                            )
                            DropdownMenuItem(
                                text = { Text("MQTT broker") },
                                leadingIcon = { Icon(Ic.Nodes, null) },
                                onClick = { menu = false; model.push(Route.ManualAdd(mqtt = true)) },
                            )
                        }
                    }
                },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = pad.calculateTopPadding() + 4.dp, bottom = pad.calculateBottomPadding() + 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (missing.isNotEmpty()) {
                item {
                    Card {
                        Text("Find devices over Bluetooth", fontWeight = FontWeight.SemiBold, color = P.ink)
                        Hint("Android needs the Nearby devices permission to see ESPHID over Bluetooth. The app never uses your location.")
                        PrimaryButton("Allow", onClick = { launcher.launch(missing.toTypedArray()) })
                    }
                }
            }
            if (saved.isNotEmpty()) {
                item { SectionTitle("Your devices") }
                items(saved, key = { "s:" + it.id }) { d ->
                    DeviceRow(
                        icon = Ic.Keyboard, accent = false, title = d.name,
                        subtitle = listOfNotNull(d.id, d.host ?: d.mqtt?.let { "MQTT" }).joinToString(" · "),
                        trailing = model.preferred(d)?.title,
                        modifier = Modifier.combinedClickable(
                            onClick = { model.push(Route.Control(d.id)) },
                            onLongClick = { removing = d },
                            onLongClickLabel = "Remove",
                        ),
                    )
                }
            }
            item {
                Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("Nearby")
                    Spacer(Modifier.weight(1f))
                    if (scanning) CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = P.ink3)
                }
            }
            if (nearby.isEmpty()) {
                item {
                    Text(
                        if (scanning) "Looking for devices over Bluetooth and on this network…" else "Nothing nearby.",
                        color = P.ink3, fontSize = 14.sp, modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp),
                    )
                }
            }
            items(nearby, key = { "n:" + it.key }) { f -> NearbyRow(f) { model.push(Route.Onboard(f)) } }
            if (saved.isEmpty()) {
                item {
                    Hint(
                        "Plug an ESPHID into a computer and it shows up here. You can also add one by its address, a Tailscale name, or an MQTT broker with the + button.",
                        Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                    )
                }
            }
        }
    }

    removing?.let { d ->
        AlertDialog(
            onDismissRequest = { removing = null },
            title = { Text("Remove ${d.name}?") },
            text = { Text("The device keeps its settings. You’ll need its access key to add it again.") },
            confirmButton = {
                TextButton(onClick = { removing = null; scope.launch { model.store.remove(d.id) } }) {
                    Text("Remove", color = P.bad)
                }
            },
            dismissButton = { TextButton(onClick = { removing = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun NearbyRow(f: DiscoveredDevice, onClick: () -> Unit) {
    val ble = f.source is Source.Ble
    DeviceRow(
        icon = if (ble) Ic.Bluetooth else Ic.Wifi, accent = true, title = f.name,
        subtitle = when (val s = f.source) {
            is Source.Ble -> "Bluetooth · ${s.rssi} dBm"
            is Source.Lan -> s.host
        },
        trailing = if (f.claimed == false) "Set up" else "Add",
        trailingTint = true,
        modifier = Modifier.combinedClickableCompat(onClick),
    )
}

@OptIn(ExperimentalFoundationApi::class)
private fun Modifier.combinedClickableCompat(onClick: () -> Unit) = this.combinedClickable(onClick = onClick)

@Composable
private fun DeviceRow(
    icon: ImageVector,
    accent: Boolean,
    title: String,
    subtitle: String,
    trailing: String?,
    modifier: Modifier,
    trailingTint: Boolean = false,
) {
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(P.surface).then(modifier).heightIn(min = 68.dp).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(if (accent) P.accentWash else P.sunken),
            contentAlignment = Alignment.Center,
        ) { Icon(icon, null, tint = if (accent) P.accentStrong else P.ink2, modifier = Modifier.size(22.dp)) }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = P.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = ReadoutSmall, color = P.ink3, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            Text(trailing, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (trailingTint) P.tint else P.ink3)
        }
    }
}

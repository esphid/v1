package dev.scalexy.esphid.ui

import android.content.Context
import android.text.InputType
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import dev.scalexy.esphid.core.ConnectionState
import dev.scalexy.esphid.core.ConsumerKey
import dev.scalexy.esphid.core.HidKey
import dev.scalexy.esphid.core.Modifiers
import dev.scalexy.esphid.core.MouseButtons
import dev.scalexy.esphid.core.PointerAccelerator
import dev.scalexy.esphid.core.SavedDevice
import dev.scalexy.esphid.core.TransportKind
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ControlScreen(model: AppModel, id: String) {
    val conn = model.connection
    val devices by model.store.devices.collectAsState()
    val device = devices.firstOrNull { it.id == id }
    val state by conn.state.collectAsState()
    val rtt by conn.rttMs.collectAsState()
    val leds by conn.leds.collectAsState()
    val usb by conn.usbMounted.collectAsState()
    val live = state == ConnectionState.Connected

    var keyboardUp by remember { mutableStateOf(false) }
    var sticky by remember { mutableIntStateOf(0) }
    var held by remember { mutableIntStateOf(0) }
    var showKeys by remember { mutableStateOf(false) }
    var linkMenu by remember { mutableStateOf(false) }
    var padActive by remember { mutableStateOf(false) }

    fun tapKey(k: HidKey) { conn.tap(k, sticky); sticky = 0 }
    fun typed(text: String) {
        if (!live) return
        val ch = text.singleOrNull()
        val mapped = if (sticky != 0 && ch != null) UsLayout.key(ch) else null
        if (mapped != null) conn.tap(mapped.first, sticky or (if (mapped.second) Modifiers.LSHIFT else 0))
        else conn.type(text)
        sticky = 0
    }

    Scaffold(
        containerColor = P.paper,
        topBar = {
            TopAppBar(
                title = { Text(device?.name ?: "Device", maxLines = 1, fontWeight = FontWeight.SemiBold) },
                navigationIcon = { IconButton(onClick = { model.pop() }) { Icon(Ic.Back, "Back", tint = P.ink) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = P.paper),
                actions = {
                    Box {
                        LinkChip(state, model.activeTransport, rtt, model.connectError != null) { linkMenu = true }
                        DropdownMenu(expanded = linkMenu, onDismissRequest = { linkMenu = false }) {
                            if (device != null) {
                                model.transports(device).forEach { k ->
                                    DropdownMenuItem(
                                        text = { Column { Text(k.title); Text(k.detail, fontSize = 12.sp, color = P.ink3) } },
                                        trailingIcon = { if (model.activeTransport == k) Icon(Ic.Check, null, tint = P.tint) },
                                        onClick = { linkMenu = false; model.setPreferred(k, device); model.open(device.copy(preferredTransport = k), k) },
                                    )
                                }
                                HorizontalDivider(color = P.line)
                                DropdownMenuItem(text = { Text("Reconnect") }, leadingIcon = { Icon(Ic.Refresh, null) }, onClick = { linkMenu = false; model.open(device, model.activeTransport) })
                            }
                        }
                    }
                    IconButton(onClick = { model.push(Route.Settings(id)) }) { Icon(Ic.Sliders, "Device settings", tint = P.ink) }
                },
            )
        },
    ) { pad ->
        Column(
            Modifier.fillMaxSize().padding(top = pad.calculateTopPadding()).imePadding().navigationBarsPadding()
                .padding(horizontal = 16.dp).padding(bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // status strip
            Row(verticalAlignment = Alignment.CenterVertically) {
                val (tone, text) = when {
                    !live -> Tone.Off to "Not connected"
                    usb == true -> Tone.Live to "Host connected"
                    usb == false -> Tone.Bad to "No USB host"
                    else -> Tone.Off to "Host status unknown"
                }
                StatusChip(tone, text)
                Spacer(Modifier.weight(1f))
                Led("NUM", leds.numLock); Spacer(Modifier.width(10.dp))
                Led("CAPS", leds.capsLock); Spacer(Modifier.width(10.dp))
                Led("SCRL", leds.scrollLock)
            }

            // trackpad + buttons as one unit
            val shape = RoundedCornerShape(16.dp)
            Column(Modifier.fillMaxWidth().weight(1f).clip(shape).background(P.lineStrong).border(1.dp, P.lineStrong, shape)) {
                Box(Modifier.fillMaxWidth().weight(1f).background(P.sunken), contentAlignment = Alignment.Center) {
                    DotGrid()
                    Trackpad(
                        model = model, enabled = live,
                        onDrag = { down -> held = if (down) held or MouseButtons.LEFT else held and MouseButtons.LEFT.inv(); conn.setButtons(held) },
                        onActive = { padActive = it },
                        modifier = Modifier.fillMaxSize(),
                    )
                    PadOverlay(model, device, state, padActive)
                }
                Spacer(Modifier.height(1.dp))
                Row(Modifier.fillMaxWidth().height(54.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
                    MouseButton("LEFT", MouseButtons.LEFT, live, Modifier.weight(1f)) { bit, down -> held = if (down) held or bit else held and bit.inv(); conn.setButtons(held) }
                    MouseButton("MID", MouseButtons.MIDDLE, live, Modifier.width(76.dp)) { bit, down -> held = if (down) held or bit else held and bit.inv(); conn.setButtons(held) }
                    MouseButton("RIGHT", MouseButtons.RIGHT, live, Modifier.weight(1f)) { bit, down -> held = if (down) held or bit else held and bit.inv(); conn.setButtons(held) }
                }
            }

            if (keyboardUp) KeyBar(sticky, { sticky = sticky xor it }, ::tapKey) { keyboardUp = false }
            else Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SecondaryButton("Keyboard", { keyboardUp = true }, Modifier.weight(1f), enabled = live, icon = { Icon(Ic.Keyboard, null, Modifier.size(20.dp)) })
                SecondaryButton("Keys & media", { showKeys = true }, Modifier.weight(1f), enabled = live, icon = { Icon(Ic.Command, null, Modifier.size(18.dp)) })
            }
        }
    }

    KeyCapture(
        active = keyboardUp,
        onClosed = { keyboardUp = false; sticky = 0 },
        onText = ::typed,
        onBackspace = { if (live) { conn.tap(HidKey.BACKSPACE, sticky); sticky = 0 } },
        onSoftKey = { k -> if (live) tapKey(k) },
        onHardware = { k, down -> if (live) { if (down) conn.keyDown(k) else conn.keyUp(k) } },
    )

    if (showKeys) {
        ModalBottomSheet(onDismissRequest = { showKeys = false }, containerColor = P.paper) {
            KeysSheet(
                onKey = { tapKey(it) },
                onConsumer = { conn.consumerTap(it) },
                onRelease = { sticky = 0; held = 0; conn.releaseAll(); showKeys = false },
            )
        }
    }
}

// ---------- pieces ----------

@Composable
private fun LinkChip(state: ConnectionState, kind: TransportKind?, rtt: Double?, error: Boolean, onClick: () -> Unit) {
    val tone = when (state) {
        ConnectionState.Connected -> Tone.Live
        ConnectionState.Connecting, ConnectionState.Authenticating -> Tone.Wait
        is ConnectionState.Failed -> Tone.Bad
        ConnectionState.Idle -> if (error) Tone.Bad else Tone.Off
    }
    val name = kind?.title ?: "—"
    val text = when (state) {
        ConnectionState.Connected -> rtt?.let { "$name · ${if (it < 10) "%.1f".format(it) else it.toInt().toString()} ms" } ?: name
        ConnectionState.Connecting, ConnectionState.Authenticating -> name
        else -> "Offline"
    }
    Row(
        Modifier.padding(end = 4.dp).height(32.dp).clip(RoundedCornerShape(50)).background(P.sunken)
            .semantics { role = Role.Button; contentDescription = "Connection: $text"; onClick { onClick(); true } }
            .pointerInput(Unit) { awaitEachGesture { awaitFirstDown(); if (waitForUpOrCancellation() != null) onClick() } }
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        StatusDot(tone)
        Text(text, style = ReadoutSmall, color = P.ink2)
    }
}

@Composable
private fun Led(label: String, on: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.semantics { contentDescription = "$label ${if (on) "on" else "off"}" }) {
        Box(Modifier.size(6.dp).clip(RoundedCornerShape(50)).background(if (on) P.accentStrong else P.lineStrong))
        Text(label, style = ReadoutSmall, color = if (on) P.ink else P.ink3)
    }
}

@Composable
private fun DotGrid() {
    val c = P.dot
    Canvas(Modifier.fillMaxSize()) {
        val step = 16.dp.toPx()
        val r = 0.8.dp.toPx()
        var y = step / 2
        while (y < size.height) {
            var x = step / 2
            while (x < size.width) { drawCircle(c, r, Offset(x, y)); x += step }
            y += step
        }
    }
}

@Composable
private fun PadOverlay(model: AppModel, device: SavedDevice?, state: ConnectionState, active: Boolean) {
    when (state) {
        ConnectionState.Connected -> {
            val a by animateFloatAsState(if (active) 0f else 1f, label = "hint")
            Text(
                "Drag to move · tap to click\nTwo fingers to scroll · hold, then move to drag",
                Modifier.alpha(a).padding(16.dp), color = P.ink3, fontSize = 13.sp, textAlign = TextAlign.Center, lineHeight = 18.sp,
            )
        }
        ConnectionState.Connecting, ConnectionState.Authenticating -> Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            CircularProgressIndicator(Modifier.size(28.dp), color = P.accentStrong, strokeWidth = 2.5.dp)
            Text(if (state == ConnectionState.Authenticating) "Checking access key…" else "Connecting over ${model.activeTransport?.title ?: ""}…", color = P.ink2, fontSize = 14.sp)
            if (model.activeTransport == TransportKind.BLE) Text("If Android asks to pair, tap Pair.", color = P.ink3, fontSize = 13.sp)
        }
        else -> if (device != null) {
            val msg = model.connectError ?: (state as? ConnectionState.Failed)?.message?.let(::friendly) ?: "Not connected."
            Column(
                Modifier.widthIn(max = 360.dp).padding(20.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("Can’t reach ${device.name}", fontWeight = FontWeight.SemiBold, color = P.ink, fontSize = 17.sp)
                Text(msg, color = P.ink2, fontSize = 14.sp, textAlign = TextAlign.Center)
                PrimaryButton("Try again", { model.open(device, model.activeTransport) }, Modifier.widthIn(max = 240.dp))
                model.transports(device).filter { it != model.activeTransport }.forEach { k ->
                    TextButton(onClick = { model.setPreferred(k, device); model.open(device.copy(preferredTransport = k), k) }) { Text("Use ${k.title} instead") }
                }
            }
        }
    }
}

/**
 * Multi-touch trackpad. Reads every historical sample Android batches between frames.
 * 1 finger: move · tap: left · 2-finger tap: right · 3-finger tap: middle · 2 fingers: scroll · hold then move: drag
 */
@Composable
private fun Trackpad(model: AppModel, enabled: Boolean, onDrag: (Boolean) -> Unit, onActive: (Boolean) -> Unit, modifier: Modifier) {
    val conn = model.connection
    val view = LocalView.current
    val density = LocalDensity.current.density
    val drag by rememberUpdatedState(onDrag)
    val active by rememberUpdatedState(onActive)
    val accel = remember { PointerAccelerator() }

    Box(
        modifier
            .semantics { contentDescription = "Trackpad" }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                coroutineScope {
                    awaitPointerEventScope {
                        while (true) {
                            val down = awaitFirstDown(requireUnconsumed = false)
                            active(true)
                            val start = down.uptimeMillis
                            var lastUptime = start
                            var travel = 0f
                            var maxPointers = 1
                            var scrolling = false
                            var dragging = false
                            val tol = 10f * density
                            val hold: Job = launch {
                                delay(420)
                                if (travel < tol && !scrolling) {
                                    dragging = true
                                    if (model.haptics) view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    drag(true)
                                }
                            }
                            fun move(d: Offset, dtMs: Long) {
                                travel += abs(d.x) + abs(d.y)
                                if (travel >= tol) hold.cancel()
                                accel.sensitivity = model.sensitivity
                                accel.acceleration = model.acceleration
                                val (x, y) = accel.apply(d.x / density, d.y / density, dtMs.coerceAtLeast(1).toFloat())
                                conn.moveMouse(x, y)
                            }
                            while (true) {
                                val ev = awaitPointerEvent()
                                val pressed = ev.changes.filter { it.pressed }
                                ev.changes.firstOrNull()?.let { lastUptime = it.uptimeMillis }
                                if (pressed.isEmpty()) break
                                maxPointers = max(maxPointers, pressed.size)
                                if (pressed.size >= 2) { scrolling = true; hold.cancel() }
                                if (pressed.size == 1 && !scrolling) {
                                    val c = pressed[0]
                                    var prev = c.previousPosition
                                    var prevT = c.previousUptimeMillis
                                    for (h in c.historical) { move(h.position - prev, h.uptimeMillis - prevT); prev = h.position; prevT = h.uptimeMillis }
                                    move(c.position - prev, c.uptimeMillis - prevT)
                                } else if (pressed.size >= 2) {
                                    val moving = pressed.filter { it.previousPressed }
                                    if (moving.isNotEmpty()) {
                                        var sx = 0f
                                        var sy = 0f
                                        for (c in moving) { sx += c.position.x - c.previousPosition.x; sy += c.position.y - c.previousPosition.y }
                                        sx /= moving.size * density
                                        sy /= moving.size * density
                                        travel += abs(sx) + abs(sy)
                                        val sign = if (model.natural) 1f else -1f
                                        conn.scroll(sy * sign / 14f, -sx * sign / 14f)
                                    }
                                }
                                ev.changes.forEach { it.consume() }
                            }
                            hold.cancel()
                            active(false)
                            if (dragging) {
                                drag(false)
                            } else if (lastUptime - start < 250 && travel < tol * maxPointers) {
                                if (model.haptics) view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                                conn.click(when { maxPointers >= 3 -> MouseButtons.MIDDLE; maxPointers == 2 -> MouseButtons.RIGHT; else -> MouseButtons.LEFT })
                            }
                        }
                    }
                }
            },
    )
}

@Composable
private fun MouseButton(label: String, bit: Int, enabled: Boolean, modifier: Modifier, onChange: (Int, Boolean) -> Unit) {
    var down by remember { mutableStateOf(false) }
    val change by rememberUpdatedState(onChange)
    Box(
        modifier.fillMaxHeight().background(if (down) P.accentWash else P.surface)
            .semantics {
                role = Role.Button
                contentDescription = "${label.lowercase()} mouse button"
                onClick { change(bit, true); change(bit, false); true }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false).consume()
                    down = true
                    change(bit, true)
                    waitForUpOrCancellation()
                    down = false
                    change(bit, false)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.6.sp, color = if (!enabled) P.ink3 else if (down) P.ink else P.ink2)
    }
}

@Composable
private fun KeyBar(sticky: Int, onToggle: (Int) -> Unit, onKey: (HidKey) -> Unit, onHide: () -> Unit) {
    val mods = listOf("Ctrl" to Modifiers.LCTRL, "Alt" to Modifiers.LALT, "Shift" to Modifiers.LSHIFT, "Cmd" to Modifiers.LGUI)
    val keys = listOf(
        "Esc" to HidKey.ESC, "Tab" to HidKey.TAB, "←" to HidKey.LEFT, "↑" to HidKey.UP, "↓" to HidKey.DOWN, "→" to HidKey.RIGHT,
        "Home" to HidKey.HOME, "End" to HidKey.END, "PgUp" to HidKey.PAGEUP, "PgDn" to HidKey.PAGEDOWN, "Del" to HidKey.DELETE,
    )
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            mods.forEach { (l, m) -> Keycap(on = sticky and m != 0, onClick = { onToggle(m) }) { KeyText(l) } }
            Box(Modifier.width(1.dp).height(28.dp).background(P.line))
            keys.forEach { (l, k) -> Keycap(onClick = { onKey(k) }) { KeyText(l) } }
        }
        Keycap(minWidth = 44, label = "Hide keyboard", onClick = onHide) { Icon(Ic.ChevronDown, "Hide keyboard", Modifier.size(20.dp)) }
    }
}

@Composable
private fun KeysSheet(onKey: (HidKey) -> Unit, onConsumer: (ConsumerKey) -> Unit, onRelease: () -> Unit) {
    val media = listOf(
        Triple(Ic.Prev, ConsumerKey.PREV, "Previous"), Triple(Ic.PlayPause, ConsumerKey.PLAY_PAUSE, "Play or pause"),
        Triple(Ic.Next, ConsumerKey.NEXT, "Next"), Triple(Ic.VolDown, ConsumerKey.VOLUME_DOWN, "Volume down"),
        Triple(Ic.Mute, ConsumerKey.MUTE, "Mute"), Triple(Ic.VolUp, ConsumerKey.VOLUME_UP, "Volume up"),
        Triple(Ic.SunDim, ConsumerKey.BRIGHTNESS_DOWN, "Brightness down"), Triple(Ic.SunBright, ConsumerKey.BRIGHTNESS_UP, "Brightness up"),
    )
    val fkeys = listOf(HidKey.F1, HidKey.F2, HidKey.F3, HidKey.F4, HidKey.F5, HidKey.F6, HidKey.F7, HidKey.F8, HidKey.F9, HidKey.F10, HidKey.F11, HidKey.F12)
    val nav = listOf(
        "Esc" to HidKey.ESC, "Tab" to HidKey.TAB, "Enter" to HidKey.ENTER, "Space" to HidKey.SPACE, "Insert" to HidKey.INSERT, "Del" to HidKey.DELETE,
        "Home" to HidKey.HOME, "End" to HidKey.END, "PgUp" to HidKey.PAGEUP, "PgDn" to HidKey.PAGEDOWN, "PrtSc" to HidKey.PRINTSCREEN, "Menu" to HidKey.MENU,
    )
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 28.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Text("Keys & media", fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = P.ink)
        Group("Media") {
            media.chunked(4).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { (icon, key, label) -> Keycap(modifier = Modifier.weight(1f), label = label, onClick = { onConsumer(key) }) { Icon(icon, label, Modifier.size(20.dp)) } }
                }
            }
        }
        Group("Function keys") {
            fkeys.chunked(6).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { k -> Keycap(modifier = Modifier.weight(1f), minWidth = 40, onClick = { onKey(k) }) { KeyText(k.name) } } } }
        }
        Group("Navigation") {
            nav.chunked(4).forEach { row -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { row.forEach { (l, k) -> Keycap(modifier = Modifier.weight(1f), onClick = { onKey(k) }) { KeyText(l) } } } }
        }
        SecondaryButton("Release all keys", onRelease, Modifier.fillMaxWidth())
        Hint("Use this if a key or button seems stuck on the computer.")
    }
}

@Composable
private fun Group(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = P.ink2)
        content()
    }
}

// ---------- keyboard capture ----------

@Composable
private fun KeyCapture(
    active: Boolean,
    onClosed: () -> Unit,
    onText: (String) -> Unit,
    onBackspace: () -> Unit,
    onSoftKey: (HidKey) -> Unit,
    onHardware: (HidKey, Boolean) -> Unit,
) {
    val text by rememberUpdatedState(onText)
    val back by rememberUpdatedState(onBackspace)
    val soft by rememberUpdatedState(onSoftKey)
    val hard by rememberUpdatedState(onHardware)
    val closed by rememberUpdatedState(onClosed)
    var viewRef by remember { mutableStateOf<KeyCaptureView?>(null) }
    AndroidView(
        factory = { ctx ->
            KeyCaptureView(ctx).also { v ->
                v.onText = { text(it) }
                v.onBackspace = { back() }
                v.onSoftKey = { soft(it) }
                v.onHardware = { k, d -> hard(k, d) }
                viewRef = v
            }
        },
        modifier = Modifier.size(1.dp).alpha(0f),
    )
    val imeBottom = WindowInsets.ime.getBottom(LocalDensity.current)
    var sawIme by remember { mutableStateOf(false) }
    LaunchedEffect(active, viewRef) {
        val v = viewRef ?: return@LaunchedEffect
        val imm = v.context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
        if (active) {
            sawIme = false
            v.requestFocus()
            imm.restartInput(v)
            imm.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
        } else {
            imm.hideSoftInputFromWindow(v.windowToken, 0)
            v.clearFocus()
        }
    }
    // The user dismissed the system keyboard (back gesture): leave keyboard mode.
    LaunchedEffect(imeBottom, active) {
        if (!active) return@LaunchedEffect
        if (imeBottom > 0) sawIme = true
        else if (sawIme) { delay(150); closed() }
    }
}

private class KeyCaptureView(ctx: Context) : View(ctx) {
    var onText: (String) -> Unit = {}
    var onBackspace: () -> Unit = {}
    var onSoftKey: (HidKey) -> Unit = {}
    var onHardware: (HidKey, Boolean) -> Unit = { _, _ -> }

    init {
        isFocusable = true
        isFocusableInTouchMode = true
    }

    override fun onCheckIsTextEditor() = true

    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
        // Visible-password variation keeps most keyboards from composing/autocorrecting.
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_ACTION_NONE
        outAttrs.initialSelStart = 0
        outAttrs.initialSelEnd = 0
        return Conn()
    }

    private inner class Conn : BaseInputConnection(this@KeyCaptureView, false) {
        private var composing = ""

        /** Send the difference between what the host already has and the new text. */
        private fun apply(old: String, new: String) {
            var i = 0
            while (i < old.length && i < new.length && old[i] == new[i]) i++
            repeat(old.length - i) { onBackspace() }
            if (i < new.length) onText(new.substring(i))
        }

        override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
            val t = text.toString()
            apply(composing, t)
            composing = t
            return true
        }

        override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
            apply(composing, text.toString())
            composing = ""
            return true
        }

        override fun finishComposingText(): Boolean { composing = ""; return true }

        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
            repeat(beforeLength.coerceAtMost(64)) { onBackspace() }
            return true
        }

        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (event.action != KeyEvent.ACTION_DOWN) return true
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> onBackspace()
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> onSoftKey(HidKey.ENTER)
                else -> {
                    val k = KeyMap.fromKeyCode(event.keyCode)
                    val ch = event.unicodeChar
                    if (ch != 0 && !Character.isISOControl(ch)) onText(String(Character.toChars(ch)))
                    else if (k != null) onSoftKey(k)
                }
            }
            return true
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val k = KeyMap.fromKeyCode(keyCode) ?: return super.onKeyDown(keyCode, event)
        if (event.repeatCount == 0) onHardware(k, true)
        return true
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        val k = KeyMap.fromKeyCode(keyCode) ?: return super.onKeyUp(keyCode, event)
        onHardware(k, false)
        return true
    }
}

/** Android KeyEvent codes → HID keys (hardware keyboards). */
private object KeyMap {
    private val table: Map<Int, HidKey> = buildMap {
        val letters = HidKey.entries.filter { it.name.length == 1 && it.name[0] in 'A'..'Z' }
        letters.forEach { put(KeyEvent.KEYCODE_A + (it.name[0] - 'A'), it) }
        for (d in 0..9) put(KeyEvent.KEYCODE_0 + d, HidKey.valueOf("DIGIT_$d"))
        for (f in 1..12) put(KeyEvent.KEYCODE_F1 + f - 1, HidKey.valueOf("F$f"))
        putAll(
            mapOf(
                KeyEvent.KEYCODE_ENTER to HidKey.ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER to HidKey.ENTER, KeyEvent.KEYCODE_ESCAPE to HidKey.ESC,
                KeyEvent.KEYCODE_DEL to HidKey.BACKSPACE, KeyEvent.KEYCODE_TAB to HidKey.TAB, KeyEvent.KEYCODE_SPACE to HidKey.SPACE,
                KeyEvent.KEYCODE_MINUS to HidKey.MINUS, KeyEvent.KEYCODE_EQUALS to HidKey.EQUAL, KeyEvent.KEYCODE_LEFT_BRACKET to HidKey.LBRACKET,
                KeyEvent.KEYCODE_RIGHT_BRACKET to HidKey.RBRACKET, KeyEvent.KEYCODE_BACKSLASH to HidKey.BACKSLASH, KeyEvent.KEYCODE_SEMICOLON to HidKey.SEMICOLON,
                KeyEvent.KEYCODE_APOSTROPHE to HidKey.QUOTE, KeyEvent.KEYCODE_GRAVE to HidKey.GRAVE, KeyEvent.KEYCODE_COMMA to HidKey.COMMA,
                KeyEvent.KEYCODE_PERIOD to HidKey.PERIOD, KeyEvent.KEYCODE_SLASH to HidKey.SLASH, KeyEvent.KEYCODE_CAPS_LOCK to HidKey.CAPSLOCK,
                KeyEvent.KEYCODE_SYSRQ to HidKey.PRINTSCREEN, KeyEvent.KEYCODE_SCROLL_LOCK to HidKey.SCROLLLOCK, KeyEvent.KEYCODE_BREAK to HidKey.PAUSE,
                KeyEvent.KEYCODE_INSERT to HidKey.INSERT, KeyEvent.KEYCODE_MOVE_HOME to HidKey.HOME, KeyEvent.KEYCODE_PAGE_UP to HidKey.PAGEUP,
                KeyEvent.KEYCODE_FORWARD_DEL to HidKey.DELETE, KeyEvent.KEYCODE_MOVE_END to HidKey.END, KeyEvent.KEYCODE_PAGE_DOWN to HidKey.PAGEDOWN,
                KeyEvent.KEYCODE_DPAD_RIGHT to HidKey.RIGHT, KeyEvent.KEYCODE_DPAD_LEFT to HidKey.LEFT, KeyEvent.KEYCODE_DPAD_DOWN to HidKey.DOWN,
                KeyEvent.KEYCODE_DPAD_UP to HidKey.UP, KeyEvent.KEYCODE_NUM_LOCK to HidKey.NUMLOCK, KeyEvent.KEYCODE_MENU to HidKey.MENU,
                KeyEvent.KEYCODE_CTRL_LEFT to HidKey.LCTRL, KeyEvent.KEYCODE_SHIFT_LEFT to HidKey.LSHIFT, KeyEvent.KEYCODE_ALT_LEFT to HidKey.LALT,
                KeyEvent.KEYCODE_META_LEFT to HidKey.LGUI, KeyEvent.KEYCODE_CTRL_RIGHT to HidKey.RCTRL, KeyEvent.KEYCODE_SHIFT_RIGHT to HidKey.RSHIFT,
                KeyEvent.KEYCODE_ALT_RIGHT to HidKey.RALT, KeyEvent.KEYCODE_META_RIGHT to HidKey.RGUI,
            ),
        )
    }

    fun fromKeyCode(code: Int): HidKey? = table[code]
}

/** US layout character → (key, needs shift); used when a sticky modifier is latched. */
private object UsLayout {
    private val lower = buildMap {
        for (c in 'a'..'z') put(c, HidKey.valueOf(c.uppercase()))
        "1234567890".forEachIndexed { i, c -> put(c, HidKey.valueOf("DIGIT_${(i + 1) % 10}")) }
        putAll(mapOf(' ' to HidKey.SPACE, '-' to HidKey.MINUS, '=' to HidKey.EQUAL, '[' to HidKey.LBRACKET, ']' to HidKey.RBRACKET, '\\' to HidKey.BACKSLASH,
            ';' to HidKey.SEMICOLON, '\'' to HidKey.QUOTE, '`' to HidKey.GRAVE, ',' to HidKey.COMMA, '.' to HidKey.PERIOD, '/' to HidKey.SLASH, '\n' to HidKey.ENTER, '\t' to HidKey.TAB))
    }
    private val upper = buildMap {
        for (c in 'A'..'Z') put(c, HidKey.valueOf(c.toString()))
        "!@#$%^&*()".forEachIndexed { i, c -> put(c, HidKey.valueOf("DIGIT_${(i + 1) % 10}")) }
        putAll(mapOf('_' to HidKey.MINUS, '+' to HidKey.EQUAL, '{' to HidKey.LBRACKET, '}' to HidKey.RBRACKET, '|' to HidKey.BACKSLASH,
            ':' to HidKey.SEMICOLON, '"' to HidKey.QUOTE, '~' to HidKey.GRAVE, '<' to HidKey.COMMA, '>' to HidKey.PERIOD, '?' to HidKey.SLASH))
    }

    fun key(c: Char): Pair<HidKey, Boolean>? = lower[c]?.let { it to false } ?: upper[c]?.let { it to true }
}

package com.scalexy.esphid.core

import android.content.Context
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*

sealed interface ConnectionState {
    data object Idle : ConnectionState

    data object Connecting : ConnectionState

    data object Authenticating : ConnectionState

    data object Connected : ConnectionState

    data class Failed(val message: String) : ConnectionState
}

/** Adjacent motion merges only; barriers preserve key/button ordering. */
internal class MotionCoalescer {
    private val q = ArrayDeque<HidCommand>()

    @Synchronized
    fun offer(c: HidCommand) {
        val last = q.peekLast()
        when {
            c is HidCommand.MouseMove &&
                last is HidCommand.MouseMove &&
                kotlin.math.abs(last.dx.toLong() + c.dx) <= Int.MAX_VALUE &&
                kotlin.math.abs(last.dy.toLong() + c.dy) <= Int.MAX_VALUE -> {
                q.removeLast()
                q.add(HidCommand.MouseMove(last.dx + c.dx, last.dy + c.dy))
            }
            c is HidCommand.MouseScroll &&
                last is HidCommand.MouseScroll &&
                kotlin.math.abs(last.wheel.toLong() + c.wheel) <= Int.MAX_VALUE &&
                kotlin.math.abs(last.pan.toLong() + c.pan) <= Int.MAX_VALUE -> {
                q.removeLast()
                q.add(HidCommand.MouseScroll(last.wheel + c.wheel, last.pan + c.pan))
            }
            else -> q.add(c)
        }
    }

    @Synchronized fun poll(): HidCommand? = q.pollFirst()

    @Synchronized fun clear() = q.clear()
}

internal fun wire(
    context: Context,
    d: SavedDevice,
    k: TransportKind,
    t: String?,
    pw: String?,
    events: (ByteArray) -> Unit = {},
    fail: (Throwable) -> Unit = {},
): Wire =
    when (k) {
        TransportKind.BLE ->
            BleWire(context.applicationContext, requireNotNull(d.bleAddress), events, fail)
        TransportKind.MQTT -> MqttWire(d, t, pw, events, fail)
        else -> NetworkWire(d, k, t, events, fail)
    }

class HidConnection(context: Context) {
    private val context = context.applicationContext
    private val s = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    val state = s.asStateFlow()
    private val tr = MutableStateFlow<TransportKind?>(null)
    val transport = tr.asStateFlow()
    private val rt = MutableStateFlow<Double?>(null)
    val rttMs = rt.asStateFlow()
    private val le = MutableStateFlow(KeyboardLeds(0))
    val leds = le.asStateFlow()
    private val usb = MutableStateFlow<Boolean?>(null)
    val usbMounted = usb.asStateFlow()
    @Volatile private var active: Wire? = null
    @Volatile
    var rpc: RpcClient? = null
        private set

    private var scope: CoroutineScope? = null
    private var dispatcher: ExecutorCoroutineDispatcher? = null
    private val q = MotionCoalescer()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    private var auth = CompletableDeferred<Int>()
    private val pings = ConcurrentHashMap<Long, Long>()
    @Volatile private var generation = 0
    private var rx = 0f
    private var ry = 0f
    private var rw = 0f
    private var rp = 0f

    private fun event(b: ByteArray) {
        runCatching {
            for (e in HidCodec.decodeEvents(b)) when (e) {
                is HidEvent.Auth -> auth.complete(e.result)
                is HidEvent.Leds -> le.value = e.leds
                is HidEvent.State -> {
                    le.value = e.leds
                    usb.value = e.usbMounted
                }
                is HidEvent.Pong ->
                    pings.remove(e.nonce)?.let {
                        val sample = (System.nanoTime() - it) / 1e6
                        rt.value = rt.value?.let { old -> old * 0.8 + sample * 0.2 } ?: sample
                    }
                else -> Unit
            }
        }
    }

    suspend fun connect(
        device: SavedDevice,
        via: TransportKind,
        token: String,
        mqttPassword: String? = null,
    ) {
        disconnect()
        val gen = generation
        s.value = ConnectionState.Connecting
        auth = CompletableDeferred()
        val disp =
            Executors.newSingleThreadExecutor { r -> Thread(r, "ESPHID-input") }
                .asCoroutineDispatcher()
        dispatcher = disp
        val work = CoroutineScope(SupervisorJob() + disp)
        scope = work
        try {
            val w =
                wire(
                    context,
                    device,
                    via,
                    token,
                    mqttPassword,
                    { if (gen == generation) event(it) },
                    { e ->
                        if (gen == generation) {
                            s.value = ConnectionState.Failed(e.message ?: "Disconnected")
                            disconnectResources()
                        }
                    },
                )
            active = w
            w.open()
            if (gen != generation) {
                w.close()
                return
            }
            s.value = ConnectionState.Authenticating
            if (via == TransportKind.BLE || via == TransportKind.WEBSOCKET) {
                w.send(HidCodec.encode(listOf(HidCommand.Auth(token))))
                if (withTimeout(10000) { auth.await() } != 0) throw RpcException.Unauthorized()
            } else {
                // Stateless transports carry the token per request; prove it before going live.
                RpcClient(w.rpc).getConfig()
            }
            if (gen != generation) {
                w.close()
                return
            }
            rpc = RpcClient(w.rpc)
            tr.value = via
            s.value = ConnectionState.Connected
            work.launch {
                try {
                    for (u in wake) {
                        val c = q.poll()
                        if (c != null) sendCommand(w, c)
                    }
                } catch (e: Exception) {
                    if (gen == generation) {
                        s.value = ConnectionState.Failed(e.message ?: "Send failed")
                        disconnectResources()
                    }
                }
            }
            if (via != TransportKind.HTTP)
                work.launch {
                    var nonce = java.security.SecureRandom().nextInt().toLong() and 0xffffffffL
                    while (isActive) {
                        delay(2000)
                        nonce = (nonce + 1) and 0xffffffffL
                        pings.clear()
                        pings[nonce] = System.nanoTime()
                        offer(HidCommand.Ping(nonce))
                        offer(HidCommand.GetState)
                    }
                }
        } catch (e: Exception) {
            if (gen == generation) {
                disconnectResources()
                s.value = ConnectionState.Failed(e.message ?: "Connection failed")
            }
            if (e is CancellationException) throw e
        }
    }

    private suspend fun sendCommand(w: Wire, c: HidCommand) {
        fun expand(c: HidCommand): List<HidCommand> =
            when (c) {
                is HidCommand.MouseMove -> {
                    var x = c.dx
                    var y = c.dy
                    buildList {
                        do {
                            val a = x.coerceIn(-32768, 32767)
                            val b = y.coerceIn(-32768, 32767)
                            add(HidCommand.MouseMove(a, b))
                            x -= a
                            y -= b
                        } while (x != 0 || y != 0)
                    }
                }
                is HidCommand.MouseScroll -> {
                    var x = c.wheel
                    var y = c.pan
                    buildList {
                        do {
                            val a = x.coerceIn(-128, 127)
                            val b = y.coerceIn(-128, 127)
                            add(HidCommand.MouseScroll(a, b))
                            x -= a
                            y -= b
                        } while (x != 0 || y != 0)
                    }
                }
                else -> listOf(c)
            }
        // MQTT AUTH consumes up to 66 bytes from the protocol packet ceiling.
        val limit = w.maxPacket - (if (tr.value == TransportKind.MQTT) 66 else 0)
        var batch = byteArrayOf()
        var next: HidCommand? = c
        while (next != null) {
            for (cmd in expand(next)) for (f in HidCodec.frames(cmd, minOf(255, limit - 2))) {
                require(f.size <= limit)
                if (batch.size + f.size > limit) {
                    w.send(batch)
                    batch = byteArrayOf()
                }
                batch += f
            }
            next = q.poll()
            if (next == null && batch.isNotEmpty()) {
                w.send(batch)
                batch = byteArrayOf()
                next = q.poll()
            }
        }
    }

    private fun disconnectResources() {
        val old = active
        active = null
        runCatching { old?.close() }
        rpc = null
        scope?.cancel()
        scope = null
        dispatcher?.close()
        dispatcher = null
        q.clear()
        pings.clear()
        tr.value = null
        rt.value = null
        usb.value = null
    }

    fun disconnect() {
        generation++
        disconnectResources()
        s.value = ConnectionState.Idle
        synchronized(this) {
            rx = 0f
            ry = 0f
            rw = 0f
            rp = 0f
        }
    }

    private fun offer(c: HidCommand) {
        if (s.value != ConnectionState.Connected) return
        q.offer(c)
        wake.trySend(Unit)
    }

    @Synchronized
    fun moveMouse(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        rx += dx
        ry += dy
        val x = rx.toInt()
        val y = ry.toInt()
        rx -= x
        ry -= y
        if (x != 0 || y != 0) offer(HidCommand.MouseMove(x, y))
    }

    fun setButtons(buttons: Int) = offer(HidCommand.MouseButtons(buttons))

    fun click(buttons: Int = MouseButtons.LEFT) = offer(HidCommand.MouseClick(buttons))

    @Synchronized
    fun scroll(wheel: Float, pan: Float) {
        if (!wheel.isFinite() || !pan.isFinite()) return
        rw += wheel
        rp += pan
        val w = rw.toInt()
        val p = rp.toInt()
        rw -= w
        rp -= p
        if (w != 0 || p != 0) offer(HidCommand.MouseScroll(w, p))
    }

    fun keyDown(k: HidKey) = offer(HidCommand.KeyDown(k.usage))

    fun keyUp(k: HidKey) = offer(HidCommand.KeyUp(k.usage))

    fun tap(k: HidKey, modifiers: Int = 0) = offer(HidCommand.KeyTap(modifiers, k.usage))

    fun setModifiers(m: Int) = offer(HidCommand.SetModifiers(m))

    fun type(text: String) = offer(HidCommand.Text(text))

    fun consumerTap(k: ConsumerKey) = offer(HidCommand.ConsumerTap(k.usage))

    fun releaseAll() = offer(HidCommand.ReleaseAll)
}

object DeviceSessions {
    private val sessions = ConcurrentHashMap<RpcClient, Wire>()

    suspend fun openRpc(
        context: Context,
        device: SavedDevice,
        via: TransportKind,
        token: String?,
        mqttPassword: String? = null,
    ): RpcClient {
        val w = wire(context, device, via, token, mqttPassword)
        try {
            w.open()
            val client = RpcClient(w.rpc)
            if (token != null && via == TransportKind.BLE) client.auth(token)
            sessions[client] = w
            return client
        } catch (e: Exception) {
            w.close()
            throw e
        }
    }

    fun close(client: RpcClient) {
        sessions.remove(client)?.close()
    }
}

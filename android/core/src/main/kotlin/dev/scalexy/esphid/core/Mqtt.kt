package dev.scalexy.esphid.core

import java.io.*
import java.net.*
import java.util.UUID
import javax.net.ssl.SSLSocketFactory
import kotlinx.coroutines.*

object MqttCodec {
    data class Packet(val header: Int, val payload: ByteArray)

    fun remainingLength(n: Int): ByteArray {
        require(n in 0..268435455)
        var v = n
        val o = ByteArrayOutputStream()
        do {
            var d = v % 128
            v /= 128
            if (v > 0) d = d or 128
            o.write(d)
        } while (v > 0)
        return o.toByteArray()
    }

    private fun utf(s: String): ByteArray {
        val b = s.toByteArray(Charsets.UTF_8)
        require(b.size <= 65535)
        return byteArrayOf((b.size shr 8).toByte(), b.size.toByte()) + b
    }

    fun packet(h: Int, b: ByteArray) = byteArrayOf(h.toByte()) + remainingLength(b.size) + b

    fun connect(id: String, username: String? = null, password: String? = null): ByteArray {
        require(password == null || username != null)
        val flags = 2 or (if (username != null) 128 else 0) or (if (password != null) 64 else 0)
        return packet(
            0x10,
            utf("MQTT") +
                byteArrayOf(4, flags.toByte(), 0, 30) +
                utf(id) +
                (username?.let { utf(it) } ?: byteArrayOf()) +
                (password?.let { utf(it) } ?: byteArrayOf()),
        )
    }

    fun publish(topic: String, payload: ByteArray) = packet(0x30, utf(topic) + payload)

    fun subscribe(id: Int, topics: List<String>) =
        packet(
            0x82,
            byteArrayOf((id shr 8).toByte(), id.toByte()) +
                topics.fold(byteArrayOf()) { a, t -> a + utf(t) + byteArrayOf(0) },
        )

    class StreamDecoder {
        private var pending = byteArrayOf()

        fun feed(bytes: ByteArray): List<Packet> {
            pending += bytes
            val out = mutableListOf<Packet>()
            var start = 0
            while (start < pending.size) {
                var p = start + 1
                var n = 0
                var mul = 1
                var count = 0
                var complete = false
                while (p < pending.size) {
                    val d = pending[p++].toInt() and 255
                    n += (d and 127) * mul
                    count++
                    require(count <= 4)
                    if (d and 128 == 0) {
                        complete = true
                        break
                    }
                    require(count < 4)
                    mul *= 128
                }
                if (!complete || pending.size - p < n) break
                require(n <= 1048576) { "MQTT packet too large" }
                out += Packet(pending[start].toInt() and 255, pending.copyOfRange(p, p + n))
                start = p + n
            }
            pending = pending.copyOfRange(start, pending.size)
            require(pending.size <= 1048581)
            return out
        }
    }
}

internal class MqttWire(
    private val d: SavedDevice,
    private val token: String?,
    private val password: String?,
    private val event: (ByteArray) -> Unit,
    private val failure: (Throwable) -> Unit,
) : Wire {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: Socket? = null
    private val outputLock = Any()
    private val connected = CompletableDeferred<Unit>()
    private val subscribed = CompletableDeferred<Unit>()
    private val settings = requireNotNull(d.mqtt)
    private val prefix = settings.topicPrefix // used verbatim, exactly as the device does
    private val reply = "$prefix/rpc/android-${UUID.randomUUID()}"
    private val mux =
        RpcMux({ sendPacket(MqttCodec.publish("$prefix/rpc/req", it.toByteArray())) }, token, reply)
    override val rpc: RpcChannel
        get() = mux

    private fun sendPacket(b: ByteArray) {
        synchronized(outputLock) {
            val o = socket?.getOutputStream() ?: throw RpcException.Transport("MQTT closed")
            o.write(b)
            o.flush()
        }
    }

    override suspend fun open() =
        withContext(Dispatchers.IO) {
            val u = URI(settings.uri)
            require(u.scheme in listOf("mqtt", "mqtts"))
            val tls = u.scheme == "mqtts"
            socket =
                (if (tls) SSLSocketFactory.getDefault().createSocket() else Socket()).apply {
                    tcpNoDelay = true
                    connect(
                        InetSocketAddress(
                            u.host,
                            if (u.port > 0) u.port else if (tls) 8883 else 1883,
                        ),
                        10000,
                    )
                    if (this is javax.net.ssl.SSLSocket) {
                        sslParameters =
                            sslParameters.apply { endpointIdentificationAlgorithm = "HTTPS" }
                        startHandshake()
                    }
                }
            scope.launch {
                try {
                    val decoder = MqttCodec.StreamDecoder()
                    val b = ByteArray(8192)
                    val input = socket!!.getInputStream()
                    while (isActive) {
                        val n = input.read(b)
                        if (n < 0) throw EOFException("MQTT EOF")
                        for (p in decoder.feed(b.copyOf(n))) when (p.header shr 4) {
                            2 -> {
                                require(p.payload.size == 2 && p.payload[1].toInt() == 0) {
                                    "MQTT CONNECT rejected"
                                }
                                connected.complete(Unit)
                            }
                            9 -> {
                                require(
                                    p.payload.size >= 3 &&
                                        p.payload[0].toInt() == 0 &&
                                        p.payload[1].toInt() == 1 &&
                                        p.payload.drop(2).all { it.toInt() == 0 }
                                ) {
                                    "MQTT subscription rejected"
                                }
                                subscribed.complete(Unit)
                            }
                            3 -> {
                                require(p.header and 6 == 0) { "Unexpected MQTT QoS" }
                                val a = p.payload
                                require(a.size >= 2)
                                val len = ((a[0].toInt() and 255) shl 8) or (a[1].toInt() and 255)
                                require(len + 2 <= a.size)
                                val topic = String(a, 2, len, Charsets.UTF_8)
                                val data = a.copyOfRange(2 + len, a.size)
                                if (topic == reply) mux.receive(String(data, Charsets.UTF_8))
                                else if (topic == "$prefix/events") event(data)
                            }
                        }
                    }
                } catch (e: Exception) {
                    connected.completeExceptionally(e)
                    subscribed.completeExceptionally(e)
                    mux.close()
                    failure(e)
                }
            }
            sendPacket(
                MqttCodec.connect("android-${UUID.randomUUID()}", settings.username, password)
            )
            withTimeout(10000) { connected.await() }
            sendPacket(MqttCodec.subscribe(1, listOf(reply, "$prefix/events")))
            withTimeout(10000) { subscribed.await() }
            scope.launch {
                while (isActive) {
                    delay(15000)
                    try {
                        sendPacket(byteArrayOf(0xc0.toByte(), 0))
                    } catch (e: Exception) {
                        failure(e)
                        break
                    }
                }
            }
            Unit
        }

    override suspend fun send(bytes: ByteArray) {
        sendPacket(
            MqttCodec.publish(
                "$prefix/hid",
                HidCodec.encode(listOf(HidCommand.Auth(token ?: ""))) + bytes,
            )
        )
    }

    override fun close() {
        mux.close()
        runCatching { sendPacket(byteArrayOf(0xe0.toByte(), 0)) }
        runCatching { socket?.close() }
        scope.cancel()
    }
}

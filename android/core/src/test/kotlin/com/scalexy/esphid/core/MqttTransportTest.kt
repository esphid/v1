package com.scalexy.esphid.core

import java.net.ServerSocket
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class MqttTransportTest {
    @Test
    fun brokerRoundtrip(): Unit = runBlocking {
        val server = ServerSocket(0)
        val checked = CompletableFuture<Unit>()
        val event = CompletableDeferred<ByteArray>()
        val broker =
            thread(isDaemon = true) {
                try {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val input = socket.getInputStream()
                        val output = socket.getOutputStream()
                        fun read(): MqttCodec.Packet {
                            val decoder = MqttCodec.StreamDecoder()
                            while (true) {
                                val b = input.read()
                                check(b >= 0)
                                val packets = decoder.feed(byteArrayOf(b.toByte()))
                                if (packets.isNotEmpty()) return packets.single()
                            }
                        }
                        assertEquals(0x10, read().header)
                        output.write(byteArrayOf(0x20, 2, 0, 0))
                        val sub = read()
                        assertEquals(0x82, sub.header)
                        val n =
                            ((sub.payload[2].toInt() and 255) shl 8) or
                                (sub.payload[3].toInt() and 255)
                        val reply = String(sub.payload, 4, n, Charsets.UTF_8)
                        output.write(byteArrayOf(0x90.toByte(), 4, 0, 1, 0, 0))
                        val rpc = read()
                        assertEquals(0x30, rpc.header)
                        val bodyOffset =
                            2 +
                                (((rpc.payload[0].toInt() and 255) shl 8) or
                                    (rpc.payload[1].toInt() and 255))
                        val req =
                            ProtocolJson.parseToJsonElement(
                                    String(rpc.payload.copyOfRange(bodyOffset, rpc.payload.size))
                                )
                                .jsonObject
                        assertEquals("device-token", req["token"]!!.jsonPrimitive.content)
                        assertEquals(reply, req["reply_to"]!!.jsonPrimitive.content)
                        output.write(
                            MqttCodec.publish(
                                reply,
                                """{"id":${req["id"]},"result":{"id":"ABCDEF"}}""".toByteArray(),
                            )
                        )
                        val hid = read()
                        val topicLen =
                            ((hid.payload[0].toInt() and 255) shl 8) or
                                (hid.payload[1].toInt() and 255)
                        assertArrayEquals(
                            HidCodec.encode(
                                listOf(HidCommand.Auth("device-token"), HidCommand.MouseClick(1))
                            ),
                            hid.payload.copyOfRange(topicLen + 2, hid.payload.size),
                        )
                        output.write(MqttCodec.publish("p/events", byteArrayOf(0x81.toByte(), 2)))
                        checked.complete(Unit)
                        read() // DISCONNECT
                    }
                } catch (e: Throwable) {
                    checked.completeExceptionally(e)
                }
            }
        val d =
            SavedDevice(
                "x",
                "x",
                mqtt = MqttSettings("mqtt://127.0.0.1:${server.localPort}", topicPrefix = "p"),
                preferredTransport = TransportKind.MQTT,
            )
        val wire =
            MqttWire(
                d,
                "device-token",
                null,
                { event.complete(it) },
                { event.completeExceptionally(it) },
            )
        try {
            wire.open()
            assertEquals("ABCDEF", RpcClient(wire.rpc).info().id)
            wire.send(HidCodec.encode(listOf(HidCommand.MouseClick(1))))
            assertArrayEquals(byteArrayOf(0x81.toByte(), 2), withTimeout(5000) { event.await() })
            checked.get(5, TimeUnit.SECONDS)
        } finally {
            wire.close()
            server.close()
            broker.join(1000)
        }
    }
}

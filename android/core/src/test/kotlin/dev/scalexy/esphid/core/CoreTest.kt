package dev.scalexy.esphid.core

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Test

class CoreTest {
    private fun hex(s: String) = s.split(' ').map { it.toInt(16).toByte() }.toByteArray()

    @Test
    fun allCommandVectors() {
        val v =
            listOf(
                HidCommand.MouseMove(10, -5) to "01 0a 00 fb ff",
                HidCommand.MouseMove(-300, 300) to "01 d4 fe 2c 01",
                HidCommand.MouseButtons(3) to "02 03",
                HidCommand.MouseScroll(-1, 2) to "03 ff 02",
                HidCommand.MouseClick(1) to "04 01",
                HidCommand.MouseAbs(16384, 32767, 0) to "05 00 40 ff 7f 00",
                HidCommand.MouseReport(1, 1, 2, -1, 0) to "06 01 01 00 02 00 ff 00",
                HidCommand.KeyDown(4) to "10 04",
                HidCommand.KeyUp(4) to "11 04",
                HidCommand.KeyTap(1, 6) to "12 01 06",
                HidCommand.KeyReport(2, listOf(4, 5)) to "13 02 04 05 00 00 00 00",
                HidCommand.SetModifiers(8) to "14 08",
                HidCommand.Text("Hi") to "15 02 48 69",
                HidCommand.ReleaseAll to "16",
                HidCommand.ConsumerTap(0xcd) to "1a cd 00",
                HidCommand.Delay(100) to "20 64 00",
                HidCommand.Ping(0x01020304) to "30 04 03 02 01",
                HidCommand.Auth("abc") to "40 03 61 62 63",
            )
        for ((cmd, expected) in v) assertArrayEquals(
            cmd.toString(),
            hex(expected),
            HidCodec.encode(listOf(cmd)),
        )
        assertArrayEquals(
            hex("40 03 61 62 63 15 02 48 69"),
            HidCodec.encode(listOf(HidCommand.Auth("abc"), HidCommand.Text("Hi"))),
        )
    }

    @Test
    fun eventVectors() {
        assertEquals(
            listOf(HidEvent.Pong(0x01020304, 1000)),
            HidCodec.decodeEvents(hex("80 04 03 02 01 e8 03 00 00")),
        )
        assertEquals(listOf(HidEvent.Leds(KeyboardLeds(2))), HidCodec.decodeEvents(hex("81 02")))
        assertEquals(listOf(HidEvent.Error(3, 1)), HidCodec.decodeEvents(hex("83 03 01")))
        assertEquals(
            listOf(HidEvent.Auth(0), HidEvent.State(5, KeyboardLeds(2), 256)),
            HidCodec.decodeEvents(hex("84 00 82 05 02 00 01")),
        )
    }

    @Test
    fun utf8() {
        val text = "a".repeat(254) + "😀é中".repeat(200)
        val frames = HidCodec.frames(HidCommand.Text(text))
        assertTrue(frames.all { it.size <= 257 })
        assertEquals(
            text,
            frames.joinToString("") {
                val b = it.copyOfRange(2, it.size)
                val s = String(b, Charsets.UTF_8)
                assertArrayEquals(b, s.toByteArray())
                s
            },
        )
    }

    @Test
    fun bleChunks() {
        val b = "😀".repeat(2048).toByteArray()
        val chunks = BleRpcFraming.chunks(b, 20)
        assertEquals(1, chunks.first()[0].toInt())
        assertEquals(2, chunks.last()[0].toInt())
        val r = BleRpcFraming.Reassembler()
        chunks.dropLast(1).forEach { assertNull(r.accept(it)) }
        assertArrayEquals(b, r.accept(chunks.last()))
        assertArrayEquals(hex("03 61"), BleRpcFraming.chunks(byteArrayOf(97), 20).single())
    }

    @Test
    fun mqtt() {
        assertArrayEquals(
            hex("10 0d 00 04 4d 51 54 54 04 02 00 1e 00 01 78"),
            MqttCodec.connect("x"),
        )
        assertArrayEquals(hex("30 04 00 01 74 61"), MqttCodec.publish("t", byteArrayOf(97)))
        listOf(127 to "7f", 128 to "80 01", 16383 to "ff 7f", 16384 to "80 80 01").forEach { (n, s)
            ->
            assertArrayEquals(hex(s), MqttCodec.remainingLength(n))
        }
        val d = MqttCodec.StreamDecoder()
        val bytes = hex("90 03 00 01 00") + MqttCodec.publish("t", ByteArray(16384) { 42 })
        val out = bytes.flatMap { d.feed(byteArrayOf(it)) }
        assertEquals(2, out.size)
        assertEquals(0x90, out[0].header)
        assertArrayEquals(hex("00 01 00"), out[0].payload)
        assertEquals(16387, out[1].payload.size)
    }

    @Test
    fun modelsAndRpc() = runBlocking {
        val info =
            ProtocolJson.decodeFromString<DeviceInfo>(
                javaClass.getResource("/info.json")!!.readText()
            )
        assertEquals("A1B2C3", info.id)
        assertEquals(true, info.usb?.mounted)
        assertEquals("Home", info.wifi?.ssid)
        val subset =
            ProtocolJson.decodeFromString<DeviceInfo>(
                """{"id":"A1B2C3","claimed":false,"future":4}"""
            )
        assertNull(subset.usb)
        val config =
            ProtocolJson.decodeFromString<DeviceConfig>(
                javaClass.getResource("/config.json")!!.readText()
            )
        assertEquals("us", config.keyboard?.layout)
        assertEquals(true, config.mqtt?.require_token)
        assertEquals(JsonPrimitive(42), rpcResult("""{"id":1,"result":42}"""))
        try {
            rpcResult("""{"id":1,"error":{"code":401,"message":"unauthorized"}}""")
            fail()
        } catch (_: RpcException.Unauthorized) {}
        try {
            rpcResult("""{"error":{"code":409,"message":"claimed"}}""")
            fail()
        } catch (e: RpcException.Remote) {
            assertEquals(409, e.code)
        }
        var request = ""
        lateinit var mux: RpcMux
        mux =
            RpcMux(
                {
                    request = it
                    val id = ProtocolJson.parseToJsonElement(it).jsonObject.getValue("id")
                    mux.receive("""{"id":$id,"result":{}}""")
                },
                "secret",
                "reply",
            )
        mux.call("config.get", null)
        val req = ProtocolJson.parseToJsonElement(request).jsonObject
        assertEquals("config.get", req["method"]!!.jsonPrimitive.content)
        assertEquals("secret", req["token"]!!.jsonPrimitive.content)
    }

    @Test
    fun coalescer() {
        val q = MotionCoalescer()
        q.offer(HidCommand.MouseMove(1, 2))
        q.offer(HidCommand.MouseMove(3, 4))
        q.offer(HidCommand.MouseButtons(1))
        q.offer(HidCommand.MouseMove(5, 6))
        assertEquals(HidCommand.MouseMove(4, 6), q.poll())
        assertEquals(HidCommand.MouseButtons(1), q.poll())
        assertEquals(HidCommand.MouseMove(5, 6), q.poll())
    }

    @Test
    fun helpers() {
        val p = PointerAccelerator()
        var last = 0f
        for (i in 0..100) {
            val v = p.apply(i.toFloat(), 0f, 8f).first
            assertTrue(v >= last)
            last = v
        }
        assertTrue(FirmwareUpdates.isNewer("v1.10.0", "1.9.0"))
        assertTrue(FirmwareUpdates.isNewer("1.0.0", "1.0.0-rc.9"))
        assertFalse(FirmwareUpdates.isNewer("1.0.0+abc", "1.0.0"))
        assertTrue(FirmwareUpdates.isNewer("1.0.0-rc.10", "1.0.0-rc.2"))
        val token = TokenGenerator.make()
        assertTrue(token.matches(Regex("[A-Za-z0-9]{32}")))
        assertEquals(64, TokenGenerator.make(64).length)
        assertEquals(
            "E3:B0:C4:42:98:FC:1C:14:9A:FB:F4:C8:99:6F:B9:24:27:AE:41:E4:64:9B:93:4C:A4:95:99:1B:78:52:B8:55",
            certificateFingerprint(byteArrayOf()),
        )
    }
}

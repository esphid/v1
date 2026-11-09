package dev.scalexy.esphid.core

import java.io.ByteArrayOutputStream

object MouseButtons {
    const val LEFT = 1
    const val RIGHT = 2
    const val MIDDLE = 4
    const val BACK = 8
    const val FORWARD = 16
}

object Modifiers {
    const val LCTRL = 1
    const val LSHIFT = 2
    const val LALT = 4
    const val LGUI = 8
    const val RCTRL = 16
    const val RSHIFT = 32
    const val RALT = 64
    const val RGUI = 128
}

data class KeyboardLeds(val raw: Int) {
    val numLock
        get() = raw and 1 != 0

    val capsLock
        get() = raw and 2 != 0

    val scrollLock
        get() = raw and 4 != 0

    val compose
        get() = raw and 8 != 0

    val kana
        get() = raw and 16 != 0
}

enum class HidKey(val usage: Int, val displayName: String) {
    A(4, "a"),
    B(5, "b"),
    C(6, "c"),
    D(7, "d"),
    E(8, "e"),
    F(9, "f"),
    G(10, "g"),
    H(11, "h"),
    I(12, "i"),
    J(13, "j"),
    K(14, "k"),
    L(15, "l"),
    M(16, "m"),
    N(17, "n"),
    O(18, "o"),
    P(19, "p"),
    Q(20, "q"),
    R(21, "r"),
    S(22, "s"),
    T(23, "t"),
    U(24, "u"),
    V(25, "v"),
    W(26, "w"),
    X(27, "x"),
    Y(28, "y"),
    Z(29, "z"),
    DIGIT_0(39, "0"),
    DIGIT_1(30, "1"),
    DIGIT_2(31, "2"),
    DIGIT_3(32, "3"),
    DIGIT_4(33, "4"),
    DIGIT_5(34, "5"),
    DIGIT_6(35, "6"),
    DIGIT_7(36, "7"),
    DIGIT_8(37, "8"),
    DIGIT_9(38, "9"),
    ENTER(40, "enter"),
    ESC(41, "esc"),
    BACKSPACE(42, "backspace"),
    TAB(43, "tab"),
    SPACE(44, "space"),
    MINUS(45, "minus"),
    EQUAL(46, "equal"),
    LBRACKET(47, "lbracket"),
    RBRACKET(48, "rbracket"),
    BACKSLASH(49, "backslash"),
    NON_US_HASH(50, "non_us_hash"),
    SEMICOLON(51, "semicolon"),
    QUOTE(52, "quote"),
    GRAVE(53, "grave"),
    COMMA(54, "comma"),
    PERIOD(55, "period"),
    SLASH(56, "slash"),
    CAPSLOCK(57, "capslock"),
    F1(58, "f1"),
    F2(59, "f2"),
    F3(60, "f3"),
    F4(61, "f4"),
    F5(62, "f5"),
    F6(63, "f6"),
    F7(64, "f7"),
    F8(65, "f8"),
    F9(66, "f9"),
    F10(67, "f10"),
    F11(68, "f11"),
    F12(69, "f12"),
    PRINTSCREEN(70, "printscreen"),
    SCROLLLOCK(71, "scrolllock"),
    PAUSE(72, "pause"),
    INSERT(73, "insert"),
    HOME(74, "home"),
    PAGEUP(75, "pageup"),
    DELETE(76, "delete"),
    END(77, "end"),
    PAGEDOWN(78, "pagedown"),
    RIGHT(79, "right"),
    LEFT(80, "left"),
    DOWN(81, "down"),
    UP(82, "up"),
    NUMLOCK(83, "numlock"),
    MENU(101, "menu"),
    F13(104, "f13"),
    F14(105, "f14"),
    F15(106, "f15"),
    F16(107, "f16"),
    F17(108, "f17"),
    F18(109, "f18"),
    F19(110, "f19"),
    F20(111, "f20"),
    F21(112, "f21"),
    F22(113, "f22"),
    F23(114, "f23"),
    F24(115, "f24"),
    LCTRL(224, "lctrl"),
    LSHIFT(225, "lshift"),
    LALT(226, "lalt"),
    LGUI(227, "lgui"),
    RCTRL(228, "rctrl"),
    RSHIFT(229, "rshift"),
    RALT(230, "ralt"),
    RGUI(231, "rgui"),
}

enum class ConsumerKey(val usage: Int) {
    PLAY_PAUSE(0xcd),
    NEXT(0xb5),
    PREV(0xb6),
    STOP(0xb7),
    MUTE(0xe2),
    VOLUME_UP(0xe9),
    VOLUME_DOWN(0xea),
    BRIGHTNESS_UP(0x6f),
    BRIGHTNESS_DOWN(0x70),
}

sealed interface HidCommand {
    data class MouseMove(val dx: Int, val dy: Int) : HidCommand

    data class MouseButtons(val buttons: Int) : HidCommand

    data class MouseScroll(val wheel: Int, val pan: Int) : HidCommand

    data class MouseClick(val buttons: Int) : HidCommand

    data class MouseAbs(val x: Int, val y: Int, val buttons: Int) : HidCommand

    data class MouseReport(
        val buttons: Int,
        val dx: Int,
        val dy: Int,
        val wheel: Int,
        val pan: Int,
    ) : HidCommand

    data class KeyDown(val usage: Int) : HidCommand

    data class KeyUp(val usage: Int) : HidCommand

    data class KeyTap(val modifiers: Int, val usage: Int) : HidCommand

    data class KeyReport(val modifiers: Int, val keys: List<Int>) : HidCommand

    data class SetModifiers(val modifiers: Int) : HidCommand

    data class Text(val text: String) : HidCommand

    data object ReleaseAll : HidCommand

    data class ConsumerDown(val usage: Int) : HidCommand

    data object ConsumerUp : HidCommand

    data class ConsumerTap(val usage: Int) : HidCommand

    data class Delay(val ms: Int) : HidCommand

    data class Ping(val nonce: Long) : HidCommand

    data object GetState : HidCommand

    data class Auth(val token: String) : HidCommand
}

sealed interface HidEvent {
    data class Pong(val nonce: Long, val uptimeMs: Long) : HidEvent

    data class Leds(val leds: KeyboardLeds) : HidEvent

    data class State(val flags: Int, val leds: KeyboardLeds, val hidQueueDepth: Int) : HidEvent {
        val usbMounted
            get() = flags and 1 != 0

        val usbSuspended
            get() = flags and 2 != 0

        val authenticated
            get() = flags and 4 != 0
    }

    data class Error(val code: Int, val opcode: Int) : HidEvent

    data class Auth(val result: Int) : HidEvent
}

object HidCodec {
    /** Returns complete command frames; transports packetize these without splitting commands. */
    fun frames(cmd: HidCommand, maxTextBytes: Int = 255): List<ByteArray> {
        val o = ByteArrayOutputStream()
        fun u(v: Int) {
            o.write(v and 255)
        }
        fun s(v: Int) {
            u(v)
            u(v shr 8)
        }
        when (cmd) {
            is HidCommand.MouseMove -> {
                u(1)
                s(cmd.dx)
                s(cmd.dy)
            }
            is HidCommand.MouseButtons -> {
                u(2)
                u(cmd.buttons)
            }
            is HidCommand.MouseScroll -> {
                u(3)
                u(cmd.wheel)
                u(cmd.pan)
            }
            is HidCommand.MouseClick -> {
                u(4)
                u(cmd.buttons)
            }
            is HidCommand.MouseAbs -> {
                u(5)
                s(cmd.x)
                s(cmd.y)
                u(cmd.buttons)
            }
            is HidCommand.MouseReport -> {
                u(6)
                u(cmd.buttons)
                s(cmd.dx)
                s(cmd.dy)
                u(cmd.wheel)
                u(cmd.pan)
            }
            is HidCommand.KeyDown -> {
                u(0x10)
                u(cmd.usage)
            }
            is HidCommand.KeyUp -> {
                u(0x11)
                u(cmd.usage)
            }
            is HidCommand.KeyTap -> {
                u(0x12)
                u(cmd.modifiers)
                u(cmd.usage)
            }
            is HidCommand.KeyReport -> {
                u(0x13)
                u(cmd.modifiers)
                repeat(6) { u(cmd.keys.getOrElse(it) { 0 }) }
            }
            is HidCommand.SetModifiers -> {
                u(0x14)
                u(cmd.modifiers)
            }
            is HidCommand.Text -> {
                require(maxTextBytes in 4..255)
                val bytes = cmd.text.toByteArray(Charsets.UTF_8)
                val result = mutableListOf<ByteArray>()
                var p = 0
                while (p < bytes.size) {
                    var end = minOf(bytes.size, p + maxTextBytes)
                    while (end < bytes.size && bytes[end].toInt() and 0xc0 == 0x80) end--
                    result += byteArrayOf(0x15, (end - p).toByte()) + bytes.copyOfRange(p, end)
                    p = end
                }
                return result
            }
            HidCommand.ReleaseAll -> u(0x16)
            is HidCommand.ConsumerDown -> {
                u(0x18)
                s(cmd.usage)
            }
            HidCommand.ConsumerUp -> u(0x19)
            is HidCommand.ConsumerTap -> {
                u(0x1a)
                s(cmd.usage)
            }
            is HidCommand.Delay -> {
                u(0x20)
                s(cmd.ms.coerceIn(0, 5000))
            }
            is HidCommand.Ping -> {
                u(0x30)
                repeat(4) { u((cmd.nonce shr (it * 8)).toInt()) }
            }
            HidCommand.GetState -> u(0x31)
            is HidCommand.Auth -> {
                val t = cmd.token.toByteArray(Charsets.US_ASCII)
                require(t.size <= 64)
                u(0x40)
                u(t.size)
                o.write(t)
            }
        }
        return listOf(o.toByteArray())
    }

    fun encode(cmds: List<HidCommand>): ByteArray =
        cmds.flatMap { frames(it) }.fold(byteArrayOf()) { a, b -> a + b }

    fun decodeEvents(b: ByteArray): List<HidEvent> {
        var p = 0
        fun u(): Int {
            require(p < b.size) { "Truncated event" }
            return b[p++].toInt() and 255
        }
        fun s(): Int = u() or (u() shl 8)
        fun l(): Long {
            var n = 0L
            repeat(4) { n = n or (u().toLong() shl (8 * it)) }
            return n
        }
        val out = mutableListOf<HidEvent>()
        while (p < b.size) out +=
            when (val op = u()) {
                0x80 -> HidEvent.Pong(l(), l())
                0x81 -> HidEvent.Leds(KeyboardLeds(u()))
                0x82 -> HidEvent.State(u(), KeyboardLeds(u()), s())
                0x83 -> HidEvent.Error(u(), u())
                0x84 -> HidEvent.Auth(u())
                else -> throw IllegalArgumentException("Unknown event $op")
            }
        return out
    }
}

object BleRpcFraming {
    fun chunks(bytes: ByteArray, payloadSize: Int): List<ByteArray> {
        require(bytes.size <= 8192 && payloadSize >= 2)
        val parts = bytes.asList().chunked(payloadSize - 1)
        return parts.mapIndexed { i, p ->
            byteArrayOf(((if (i == 0) 1 else 0) or (if (i == parts.lastIndex) 2 else 0)).toByte()) +
                p.toByteArray()
        }
    }

    class Reassembler {
        private var data: ByteArrayOutputStream? = null

        fun accept(chunk: ByteArray): ByteArray? {
            require(chunk.isNotEmpty())
            val h = chunk[0].toInt()
            require(h and 0xfc == 0)
            if (h and 1 != 0) data = ByteArrayOutputStream()
            val d = requireNotNull(data) { "Missing FIRST" }
            if (d.size() + chunk.size - 1 > 8192) {
                data = null
                throw IllegalArgumentException("RPC too large")
            }
            d.write(chunk, 1, chunk.size - 1)
            return if (h and 2 != 0) {
                data = null
                d.toByteArray()
            } else null
        }
    }
}

package dev.scalexy.esphid.core

import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.math.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.*

object TokenGenerator {
    private val random = SecureRandom()
    private const val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"

    fun make(length: Int = 32): String {
        require(length > 0)
        return buildString { repeat(length) { append(chars[random.nextInt(chars.length)]) } }
    }
}

fun certificateFingerprint(der: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(der).joinToString(":") {
        "%02X".format(it.toInt() and 255)
    }

class PointerAccelerator(var sensitivity: Float = 1f, var acceleration: Float = 1f) {
    fun apply(dx: Float, dy: Float, dtMs: Float): Pair<Float, Float> {
        if (!dx.isFinite() || !dy.isFinite()) return 0f to 0f
        val speed = hypot(dx, dy) / dtMs.coerceAtLeast(1f)
        val factor =
            sensitivity.coerceAtLeast(0f) * (1 + acceleration.coerceAtLeast(0f) * ln(1 + speed))
        return dx * factor to dy * factor
    }
}

data class FirmwareRelease(
    val version: String,
    val tag: String,
    val notes: String,
    val publishedAt: String,
    val assetUrl: String,
)

object FirmwareUpdates {
    private val client = OkHttpClient()

    suspend fun latest(repo: String, variant: String): FirmwareRelease? =
        withContext(Dispatchers.IO) {
            require(Regex("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+").matches(repo))
            require(Regex("[A-Za-z0-9_-]+").matches(variant))
            client
                .newCall(
                    Request.Builder()
                        .url("https://api.github.com/repos/$repo/releases/latest")
                        .header("Accept", "application/vnd.github+json")
                        .build()
                )
                .execute()
                .use { r ->
                    if (r.code == 404) return@withContext null
                    if (!r.isSuccessful) throw RpcException.Transport("GitHub HTTP ${r.code}")
                    val o = ProtocolJson.parseToJsonElement(r.body!!.string()).jsonObject
                    val asset =
                        o["assets"]!!
                            .jsonArray
                            .firstOrNull {
                                it.jsonObject["name"]?.jsonPrimitive?.content ==
                                    "esphid-$variant.bin"
                            }
                            ?.jsonObject ?: return@withContext null
                    fun s(k: String) = o[k]?.jsonPrimitive?.contentOrNull ?: ""
                    val tag = s("tag_name")
                    FirmwareRelease(
                        tag.removePrefix("v"),
                        tag,
                        s("body"),
                        s("published_at"),
                        asset.getValue("browser_download_url").jsonPrimitive.content,
                    )
                }
        }

    fun isNewer(a: String, than: String): Boolean {
        fun parse(s: String): Pair<List<Int>, List<String>?> {
            val p = s.removePrefix("v").substringBefore('+').split('-', limit = 2)
            return p[0].split('.').map { it.toIntOrNull() ?: 0 } to p.getOrNull(1)?.split('.')
        }
        val (x, xp) = parse(a)
        val (y, yp) = parse(than)
        for (i in 0 until maxOf(x.size, y.size)) {
            val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (c != 0) return c > 0
        }
        if (xp == null) return yp != null
        if (yp == null) return false
        for (i in 0 until minOf(xp.size, yp.size)) {
            val xn = xp[i].toIntOrNull()
            val yn = yp[i].toIntOrNull()
            val c =
                when {
                    xn != null && yn != null -> xn.compareTo(yn)
                    xn != null -> -1
                    yn != null -> 1
                    else -> xp[i].compareTo(yp[i])
                }
            if (c != 0) return c > 0
        }
        return xp.size > yp.size
    }
}

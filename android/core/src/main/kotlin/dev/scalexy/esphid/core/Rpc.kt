package dev.scalexy.esphid.core

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.*

interface RpcChannel {
    suspend fun call(method: String, params: JsonElement?): JsonElement
}

sealed class RpcException(msg: String) : Exception(msg) {
    class Remote(val code: Int, message: String) : RpcException(message)

    class Timeout : RpcException("RPC timed out")

    class Transport(message: String) : RpcException(message)

    class Decoding(message: String) : RpcException(message)

    class Unauthorized : RpcException("Unauthorized")
}

internal fun rpcResult(text: String): JsonElement =
    try {
        val o = ProtocolJson.parseToJsonElement(text).jsonObject
        o["error"]?.jsonObject?.let {
            val c = it["code"]?.jsonPrimitive?.int ?: 500
            if (c == 401) throw RpcException.Unauthorized()
            throw RpcException.Remote(c, it["message"]?.jsonPrimitive?.content ?: "Remote error")
        }
        o["result"] ?: throw RpcException.Decoding("Missing result")
    } catch (e: RpcException) {
        throw e
    } catch (e: Exception) {
        throw RpcException.Decoding(e.message ?: "Invalid JSON")
    }

internal class RpcMux(
    private val send: suspend (String) -> Unit,
    private val token: String? = null,
    private val replyTo: String? = null,
    private val serial: Boolean = false,
) : RpcChannel {
    private val ids = AtomicLong()
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<String>>()
    private val mutex = Mutex()

    override suspend fun call(method: String, params: JsonElement?): JsonElement =
        if (serial) mutex.withLock { request(method, params) } else request(method, params)

    private suspend fun request(method: String, params: JsonElement?): JsonElement {
        val id = ids.incrementAndGet()
        val d = CompletableDeferred<String>()
        pending[id] = d
        try {
            val req = buildJsonObject {
                put("id", id)
                put("method", method)
                put("params", params ?: buildJsonObject {})
                token?.let { put("token", it) }
                replyTo?.let { put("reply_to", it) }
            }
            return withTimeout(10000) {
                send(req.toString())
                rpcResult(d.await())
            }
        } catch (e: TimeoutCancellationException) {
            throw RpcException.Timeout()
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException.Transport(e.message ?: "RPC send failed")
        } finally {
            pending.remove(id)
        }
    }

    fun receive(text: String) {
        runCatching {
            val id = ProtocolJson.parseToJsonElement(text).jsonObject["id"]?.jsonPrimitive?.long
            pending[id]?.complete(text)
        }
    }

    fun close() {
        pending.values.forEach {
            it.completeExceptionally(RpcException.Transport("Connection closed"))
        }
        pending.clear()
    }
}

class RpcClient(private val channel: RpcChannel) {
    private suspend inline fun <reified T> read(method: String, params: JsonElement? = null): T =
        try {
            ProtocolJson.decodeFromJsonElement(channel.call(method, params))
        } catch (e: CancellationException) {
            throw e
        } catch (e: RpcException) {
            throw e
        } catch (e: Exception) {
            throw RpcException.Decoding(e.message ?: "Invalid result")
        }

    private fun token(t: String) = buildJsonObject { put("token", t) }

    suspend fun info(): DeviceInfo = read("info")

    suspend fun auth(token: String) {
        channel.call("auth", token(token))
    }

    suspend fun claim(token: String) {
        channel.call("claim", token(token))
    }

    suspend fun setToken(token: String) {
        channel.call("token.set", token(token))
    }

    suspend fun getConfig(): DeviceConfig = read("config.get")

    suspend fun setConfig(patch: JsonObject): ConfigSetResult = read("config.set", patch)

    suspend fun scanWifi(): List<WifiNetwork> {
        val r = channel.call("wifi.scan", null).jsonObject["networks"] ?: JsonArray(emptyList())
        return ProtocolJson.decodeFromJsonElement(r)
    }

    suspend fun startOta(url: String, sha256: String? = null) {
        channel.call(
            "ota.start",
            buildJsonObject {
                put("url", url)
                sha256?.let { put("sha256", it) }
            },
        )
    }

    suspend fun otaStatus(): OtaStatus = read("ota.status")

    suspend fun reboot(delayMs: Int? = null) {
        channel.call("system.reboot", buildJsonObject { delayMs?.let { put("delay_ms", it) } })
    }

    suspend fun factoryReset() {
        channel.call("system.factory_reset", null)
    }

    suspend fun regenerateHttpsCertificate(): String =
        channel
            .call("https.regenerate", null)
            .jsonObject
            .getValue("fingerprint")
            .jsonPrimitive
            .content

    suspend fun sendHid(json: JsonObject) {
        channel.call("hid", json)
    }
}

package com.scalexy.esphid.core

import java.net.*
import java.security.*
import java.security.cert.X509Certificate
import javax.net.SocketFactory
import javax.net.ssl.*
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal interface Wire : AutoCloseable {
    val rpc: RpcChannel
    val maxPacket: Int
        get() = 512

    suspend fun open()

    suspend fun send(bytes: ByteArray)
}

internal open class NoDelaySocketFactory : SocketFactory() {
    override fun createSocket(): Socket = Socket().apply { tcpNoDelay = true }

    override fun createSocket(h: String, p: Int) =
        createSocket().apply { connect(InetSocketAddress(h, p)) }

    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) =
        createSocket().apply {
            bind(InetSocketAddress(l, lp))
            connect(InetSocketAddress(h, p))
        }

    override fun createSocket(h: InetAddress, p: Int) =
        createSocket().apply { connect(InetSocketAddress(h, p)) }

    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) =
        createSocket().apply {
            bind(InetSocketAddress(l, lp))
            connect(InetSocketAddress(h, p))
        }
}

internal class NoDelayTls(private val delegate: SSLSocketFactory) : SSLSocketFactory() {
    private fun tune(s: Socket) = s.apply { tcpNoDelay = true }

    override fun getDefaultCipherSuites() = delegate.defaultCipherSuites

    override fun getSupportedCipherSuites() = delegate.supportedCipherSuites

    override fun createSocket(s: Socket, h: String, p: Int, a: Boolean) =
        tune(delegate.createSocket(s, h, p, a))

    override fun createSocket() = tune(delegate.createSocket())

    override fun createSocket(h: String, p: Int) = tune(delegate.createSocket(h, p))

    override fun createSocket(h: String, p: Int, l: InetAddress, lp: Int) =
        tune(delegate.createSocket(h, p, l, lp))

    override fun createSocket(h: InetAddress, p: Int) = tune(delegate.createSocket(h, p))

    override fun createSocket(h: InetAddress, p: Int, l: InetAddress, lp: Int) =
        tune(delegate.createSocket(h, p, l, lp))
}

internal fun networkClient(d: SavedDevice): OkHttpClient {
    val b =
        OkHttpClient.Builder()
            .socketFactory(NoDelaySocketFactory())
            .pingInterval(0, java.util.concurrent.TimeUnit.SECONDS)
    if (d.httpsPort != null) {
        val fp = requireNotNull(d.tlsFingerprint) { "HTTPS requires a trusted fingerprint" }
        require(Regex("([0-9A-F]{2}:){31}[0-9A-F]{2}").matches(fp)) { "Invalid fingerprint" }
        val trust =
            object : X509TrustManager {
                override fun getAcceptedIssuers() = emptyArray<X509Certificate>()

                override fun checkClientTrusted(c: Array<X509Certificate>, a: String) {
                    throw java.security.cert.CertificateException("Client certificate unsupported")
                }

                override fun checkServerTrusted(c: Array<X509Certificate>, a: String) {
                    if (c.isEmpty() || certificateFingerprint(c[0].encoded) != fp)
                        throw java.security.cert.CertificateException("Fingerprint mismatch")
                }
            }
        val tls = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), null) }
        b.sslSocketFactory(NoDelayTls(tls.socketFactory), trust).hostnameVerifier { _, _ -> true }
    }
    return b.build()
}

internal class NetworkWire(
    private val device: SavedDevice,
    private val kind: TransportKind,
    private val token: String?,
    private val events: (ByteArray) -> Unit,
    private val failure: (Throwable) -> Unit,
) : Wire {
    private val client = networkClient(device)
    private var ws: WebSocket? = null
    private val ready = CompletableDeferred<Unit>()
    private val base =
        "${if(device.httpsPort!=null)"https" else "http"}://${requireNotNull(device.host).let{if(':' in it)"[$it]"else it}}:${device.httpsPort?:device.port}"

    private fun req(path: String) =
        Request.Builder().url(base + path).apply {
            token?.let { header("Authorization", "Bearer $it") }
        }

    private val mux: RpcMux =
        RpcMux({ s ->
            withContext(Dispatchers.IO) {
                if (kind == TransportKind.WEBSOCKET) {
                    if (ws?.send(s) != true) throw RpcException.Transport("WebSocket closed")
                } else {
                    client
                        .newCall(
                            req("/api/rpc")
                                .post(s.toRequestBody("application/json".toMediaType()))
                                .build()
                        )
                        .execute()
                        .use { r ->
                            if (r.code == 401) throw RpcException.Unauthorized()
                            val body =
                                r.body?.string() ?: throw RpcException.Transport("Empty response")
                            muxReceive(body)
                        }
                }
            }
        })

    private fun muxReceive(s: String): Unit {
        mux.receive(s)
    }

    override val rpc: RpcChannel
        get() = mux

    override suspend fun open() {
        if (kind != TransportKind.WEBSOCKET) return
        ws =
            client.newWebSocket(
                req("/ws").build(),
                object : WebSocketListener() {
                    override fun onOpen(w: WebSocket, r: Response) {
                        ready.complete(Unit)
                    }

                    override fun onMessage(w: WebSocket, b: ByteString) {
                        events(b.toByteArray())
                    }

                    override fun onMessage(w: WebSocket, t: String) {
                        mux.receive(t)
                    }

                    override fun onFailure(w: WebSocket, t: Throwable, r: Response?) {
                        ready.completeExceptionally(t)
                        mux.close()
                        failure(t)
                    }

                    override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                        webSocket.close(code, reason)
                        mux.close()
                        failure(RpcException.Transport("WebSocket closing: $code"))
                    }

                    override fun onClosed(w: WebSocket, c: Int, r: String) {
                        mux.close()
                        failure(RpcException.Transport("WebSocket closed: $c"))
                    }
                },
            )
        withTimeout(15000) { ready.await() }
    }

    override suspend fun send(bytes: ByteArray) {
        if (kind == TransportKind.WEBSOCKET) {
            while ((ws?.queueSize() ?: 0) > 0) delay(1)
            if (ws?.send(bytes.toByteString()) != true)
                throw RpcException.Transport("WebSocket send failed")
        } else
            withContext(Dispatchers.IO) {
                client
                    .newCall(
                        req("/api/hid")
                            .post(bytes.toRequestBody("application/octet-stream".toMediaType()))
                            .build()
                    )
                    .execute()
                    .use { r ->
                        if (r.code == 401) throw RpcException.Unauthorized()
                        if (!r.isSuccessful) throw RpcException.Transport("HID HTTP ${r.code}")
                    }
            }
    }

    override fun close() {
        mux.close()
        ws?.cancel()
        client.dispatcher.cancelAll()
        client.connectionPool.evictAll()
        client.dispatcher.executorService.shutdown()
    }
}

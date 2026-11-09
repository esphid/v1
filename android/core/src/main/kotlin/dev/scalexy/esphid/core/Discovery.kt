@file:android.annotation.SuppressLint("MissingPermission")
@file:Suppress("DEPRECATION")

package dev.scalexy.esphid.core

import android.Manifest
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.*
import android.net.wifi.WifiManager
import android.os.*
import kotlinx.coroutines.flow.*

data class DiscoveredDevice(
    val key: String,
    val name: String,
    val source: Source,
    val deviceId: String?,
    val claimed: Boolean?,
    val fwVersion: String?,
) {}

sealed interface Source {
    data class Ble(val address: String, val rssi: Int) : Source

    data class Lan(val host: String, val port: Int) : Source
}

fun DiscoveredDevice.toSavedDevice(): SavedDevice =
    when (val s = source) {
        is Source.Ble ->
            SavedDevice(
                deviceId ?: key,
                name,
                bleAddress = s.address,
                preferredTransport = TransportKind.BLE,
            )
        is Source.Lan ->
            SavedDevice(
                deviceId ?: key,
                name,
                host = s.host,
                port = s.port,
                preferredTransport = TransportKind.WEBSOCKET,
            )
    }

class DeviceDiscovery(context: Context) {
    private val context = context.applicationContext
    private val nsd = context.getSystemService(NsdManager::class.java)
    private val values = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val found = values.asStateFlow()
    private val scanning = MutableStateFlow(false)
    val isScanning = scanning.asStateFlow()
    private val main = Handler(Looper.getMainLooper())
    private val executor = java.util.concurrent.Executor { main.post(it) }
    private val callbacks = mutableMapOf<String, NsdManager.ServiceInfoCallback>()
    private var listener: NsdManager.DiscoveryListener? = null
    private var multicast: WifiManager.MulticastLock? = null
    private var scanner: BluetoothLeScanner? = null
    private val resolves = java.util.ArrayDeque<NsdServiceInfo>()
    private var resolving = false

    private fun resolveNext() {
        if (resolving || !scanning.value) return
        val service = resolves.pollFirst() ?: return
        resolving = true
        fun done() {
            resolving = false
            resolveNext()
        }
        try {
            nsd.resolveService(
                service,
                object : NsdManager.ResolveListener {
                    override fun onResolveFailed(s: NsdServiceInfo, e: Int) {
                        main.post { done() }
                    }

                    override fun onServiceResolved(s: NsdServiceInfo) {
                        main.post {
                            if (scanning.value) resolved(s)
                            done()
                        }
                    }
                },
            )
        } catch (_: Exception) {
            done()
        }
    }

    fun missingPermissions(): List<String> =
        (if (Build.VERSION.SDK_INT >= 31)
                listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
            else listOf(Manifest.permission.ACCESS_FINE_LOCATION))
            .filter { context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }

    private fun add(d: DiscoveredDevice) {
        synchronized(values) { values.value = values.value.filterNot { it.key == d.key } + d }
    }

    private fun resolved(s: NsdServiceInfo) {
        val host = s.host?.hostAddress ?: return
        fun txt(k: String) = s.attributes[k]?.toString(Charsets.UTF_8)
        add(
            DiscoveredDevice(
                "lan:${s.serviceName}",
                txt("name") ?: s.serviceName,
                Source.Lan(host, s.port),
                txt("id"),
                txt("claimed")?.let { it == "1" },
                txt("fw"),
            )
        )
    }

    private val scan =
        object : ScanCallback() {
            override fun onScanResult(t: Int, r: ScanResult) {
                runCatching {
                    add(
                        DiscoveredDevice(
                            "ble:${r.device.address}",
                            r.scanRecord?.deviceName ?: "ESPHID",
                            Source.Ble(r.device.address, r.rssi),
                            null,
                            null,
                            null,
                        )
                    )
                }
            }

            override fun onScanFailed(code: Int) {
                scanner = null
            }
        }

    fun start() {
        if (scanning.value) return
        scanning.value = true
        runCatching {
            multicast =
                context
                    .getSystemService(WifiManager::class.java)
                    .createMulticastLock("ESPHID-discovery")
                    .apply {
                        setReferenceCounted(false)
                        acquire()
                    }
        }
        val l =
            object : NsdManager.DiscoveryListener {
                override fun onDiscoveryStarted(t: String) {}

                override fun onDiscoveryStopped(t: String) {}

                override fun onStartDiscoveryFailed(t: String, e: Int) {
                    stop()
                }

                override fun onStopDiscoveryFailed(t: String, e: Int) {}

                override fun onServiceLost(s: NsdServiceInfo) {
                    synchronized(values) {
                        values.value = values.value.filterNot { it.key == "lan:${s.serviceName}" }
                    }
                    if (Build.VERSION.SDK_INT >= 34)
                        callbacks.remove(s.serviceName)?.let {
                            runCatching { nsd.unregisterServiceInfoCallback(it) }
                        }
                }

                override fun onServiceFound(s: NsdServiceInfo) {
                    if (!scanning.value) return
                    if (Build.VERSION.SDK_INT >= 34) {
                        val cb =
                            object : NsdManager.ServiceInfoCallback {
                                override fun onServiceInfoCallbackRegistrationFailed(e: Int) {}

                                override fun onServiceUpdated(i: NsdServiceInfo) {
                                    if (scanning.value) resolved(i)
                                }

                                override fun onServiceLost() {}

                                override fun onServiceInfoCallbackUnregistered() {}
                            }
                        callbacks[s.serviceName] = cb
                        runCatching { nsd.registerServiceInfoCallback(s, executor, cb) }
                    } else {
                        main.post {
                            resolves.add(s)
                            resolveNext()
                        }
                    }
                }
            }
        listener = l
        runCatching { nsd.discoverServices("_esphid._tcp.", NsdManager.PROTOCOL_DNS_SD, l) }
        if (missingPermissions().isEmpty())
            runCatching {
                scanner =
                    context
                        .getSystemService(BluetoothManager::class.java)
                        .adapter
                        ?.bluetoothLeScanner
                scanner?.startScan(
                    listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(BleIds.service)).build()),
                    ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(),
                    scan,
                )
            }
    }

    fun stop() {
        scanning.value = false
        resolves.clear()
        runCatching { scanner?.stopScan(scan) }
        scanner = null
        listener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        listener = null
        if (Build.VERSION.SDK_INT >= 34)
            callbacks.values.forEach { runCatching { nsd.unregisterServiceInfoCallback(it) } }
        callbacks.clear()
        runCatching { multicast?.release() }
        multicast = null
    }
}

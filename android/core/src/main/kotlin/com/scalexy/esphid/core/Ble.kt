@file:Suppress("DEPRECATION")
@file:android.annotation.SuppressLint("MissingPermission")

package com.scalexy.esphid.core

import android.Manifest
import android.bluetooth.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.*
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

internal object BleIds {
    fun uuid(n: Int) = UUID.fromString("705c000$n-a217-40f5-b47b-6712ed7d7dac")

    val service = uuid(1)
    val hid = uuid(2)
    val events = uuid(3)
    val rpc = uuid(4)
    val cccd = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
}

// Android reports authentication/encryption failures using these three GATT statuses.
internal fun shouldRetryGattAfterBond(status: Int, alreadyRetried: Boolean): Boolean =
    !alreadyRetried && (status == 5 || status == 15 || status == 137)

/**
 * Link drops worth a fresh connection attempt: 133 (generic Android GATT error),
 * 8 (supervision timeout), 34/0x22 (LL response timeout), 35/0x23 and 42/0x2A
 * (LL procedure collisions), 62/0x3E (connection failed to establish), 147/0x93
 * (GATT connection timeout), and a clean disconnect while still setting up.
 */
internal fun isTransientGattFailure(message: String?): Boolean {
    if (message == null) return false
    if (message == "BLE disconnected") return true
    val status = Regex("""^GATT status (\d+)$""").find(message)?.groupValues?.get(1)?.toIntOrNull() ?: return false
    return status in setOf(8, 34, 35, 42, 62, 133, 147)
}

internal class BleWire(
    private val context: Context,
    private val address: String,
    private val events: (ByteArray) -> Unit,
    private val failure: (Throwable) -> Unit,
) : Wire {
    private val thread = HandlerThread("ESPHID-GATT").apply { start() }
    private val handler = Handler(thread.looper)
    private val lock = Mutex()
    private var gatt: BluetoothGatt? = null
    private var ready = CompletableDeferred<Unit>()
    private var op: CompletableDeferred<Int>? = null
    private var mtu = 23
    private var discovered = false
    // True once open() has finished; before that, link drops are retried inside open()
    // instead of being reported to the connection as a failure.
    private var opened = false
    // Accessed only on ESPHID-GATT, including receiver delivery and close().
    private var bondWait: CompletableDeferred<Unit>? = null
    private var closed = false
    private var unregisterBondReceiver: (() -> Unit)? = null
    override val maxPacket
        get() = minOf(512, mtu - 3)

    private val assembler = BleRpcFraming.Reassembler()
    private val mux =
        RpcMux(
            { s ->
                for (c in BleRpcFraming.chunks(s.toByteArray(), maxPacket)) write(
                    BleIds.rpc,
                    c,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT,
                )
            },
            serial = true,
        )
    override val rpc: RpcChannel
        get() = mux

    private fun notification(c: BluetoothGattCharacteristic, b: ByteArray) {
        runCatching {
                if (c.uuid == BleIds.events) events(b)
                else if (c.uuid == BleIds.rpc)
                    assembler.accept(b)?.let { mux.receive(String(it, Charsets.UTF_8)) }
            }
            .onFailure(failure)
    }

    private val callback =
        object : BluetoothGattCallback() {
            override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                handler.post {
                    if (g !== gatt) return@post
                    if (status != 0) {
                        ready.completeExceptionally(RpcException.Transport("GATT status $status"))
                        op?.completeExceptionally(RpcException.Transport("GATT status $status"))
                        if (opened) failure(RpcException.Transport("GATT status $status"))
                    } else if (newState == BluetoothProfile.STATE_CONNECTED) {
                        try {
                            requireConnectPermission()
                            // Connection priority now; the 2M PHY is requested only after setup
                            // (see open()) so it can't collide with pairing/discovery.
                            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                            if (!g.discoverServices())
                                ready.completeExceptionally(
                                    RpcException.Transport("Service discovery rejected")
                                )
                        } catch (e: Exception) {
                            ready.completeExceptionally(e)
                            op?.completeExceptionally(e)
                        }
                    } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                        val e = RpcException.Transport("BLE disconnected")
                        ready.completeExceptionally(e)
                        op?.completeExceptionally(e)
                        if (opened) failure(e)
                    }
                }
            }

            override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                handler.post {
                    if (g !== gatt) return@post
                    if (status == 0) {
                        discovered = true
                        ready.complete(Unit)
                    } else
                        ready.completeExceptionally(
                            RpcException.Transport("Discovery status $status")
                        )
                }
            }

            override fun onMtuChanged(g: BluetoothGatt, m: Int, status: Int) {
                handler.post {
                    if (g !== gatt) return@post
                    if (status == 0) mtu = m
                    op?.complete(status)
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                status: Int,
            ) {
                handler.post { if (g === gatt) op?.complete(status) }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                status: Int,
            ) {
                handler.post { if (g === gatt) op?.complete(status) }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                notification(c, value)
            }

            override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
                notification(c, c.value ?: byteArrayOf())
            }
        }

    private fun requireConnectPermission() {
        if (Build.VERSION.SDK_INT >= 31 &&
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
                PackageManager.PERMISSION_GRANTED
        ) throw RpcException.Transport("Bluetooth Connect permission is required")
    }

    private suspend fun <T> onGattThread(action: () -> T): T {
        val result = CompletableDeferred<T>()
        if (!handler.post {
                try {
                    result.complete(action())
                } catch (e: Exception) {
                    result.completeExceptionally(e)
                }
            }) throw RpcException.Transport("BLE closed")
        return result.await()
    }

    private suspend fun awaitBond(device: BluetoothDevice) {
        val paired = CompletableDeferred<Unit>()
        var registered = false
        var sawBonding = false
        val pairingFailure =
            "Bluetooth pairing was cancelled or failed. Try again and tap Pair when Android asks."
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                try {
                    requireConnectPermission()
                    if (intent.action != BluetoothDevice.ACTION_BOND_STATE_CHANGED) return
                    val changed =
                        if (Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                    if (changed?.address != device.address) return
                    when (intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)) {
                        BluetoothDevice.BOND_BONDED -> paired.complete(Unit)
                        BluetoothDevice.BOND_BONDING -> sawBonding = true
                        BluetoothDevice.BOND_NONE -> {
                            if (sawBonding || intent.getIntExtra(
                                    BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.ERROR
                                ) == BluetoothDevice.BOND_BONDING
                            ) paired.completeExceptionally(RpcException.Transport(pairingFailure))
                        }
                    }
                } catch (e: Exception) {
                    paired.completeExceptionally(e)
                }
            }
        }
        try {
            val completed = withTimeoutOrNull(30_000) {
                onGattThread {
                    requireConnectPermission()
                    if (closed) throw RpcException.Transport("BLE closed")
                    val initialState = device.bondState
                    if (initialState == BluetoothDevice.BOND_BONDED) {
                        paired.complete(Unit)
                        return@onGattThread
                    }
                    sawBonding = initialState == BluetoothDevice.BOND_BONDING
                    bondWait = paired
                    val filter = IntentFilter(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                    // Bluetooth broadcasts can originate in the privileged Bluetooth process.
                    if (Build.VERSION.SDK_INT >= 33)
                        context.registerReceiver(receiver, filter, null, handler, Context.RECEIVER_EXPORTED)
                    else context.registerReceiver(receiver, filter, null, handler)
                    registered = true
                    unregisterBondReceiver = {
                        if (registered) {
                            context.unregisterReceiver(receiver)
                            registered = false
                        }
                    }
                    // Recheck after registration so a completed/in-progress bond cannot be missed.
                    when (device.bondState) {
                        BluetoothDevice.BOND_BONDED -> paired.complete(Unit)
                        BluetoothDevice.BOND_BONDING -> sawBonding = true
                        else -> {
                            if (sawBonding) throw RpcException.Transport(pairingFailure)
                            if (!device.createBond()) {
                                when (device.bondState) {
                                    BluetoothDevice.BOND_BONDED -> paired.complete(Unit)
                                    BluetoothDevice.BOND_BONDING -> sawBonding = true
                                    else -> throw RpcException.Transport(pairingFailure)
                                }
                            } else sawBonding = true
                        }
                    }
                }
                paired.await()
                true
            }
            if (completed == null)
                throw RpcException.Transport(
                    "Bluetooth pairing timed out. Try again and tap Pair when Android asks."
                )
        } finally {
            // Cleanup must run even when the caller cancels during receiver registration.
            withContext(NonCancellable) {
                val cleaned = CompletableDeferred<Unit>()
                if (handler.post {
                        try {
                            if (bondWait === paired) clearBondWait()
                            cleaned.complete(Unit)
                        } catch (e: Exception) {
                            cleaned.completeExceptionally(e)
                        }
                    }) cleaned.await()
                // If the looper has stopped, close() already removed the receiver.
            }
        }
    }

    private fun clearBondWait() {
        unregisterBondReceiver?.invoke()
        unregisterBondReceiver = null
        bondWait = null
    }

    private suspend fun operation(retryAfterBond: Boolean = false, action: () -> Boolean) =
        lock.withLock {
            var retried = false
            while (true) {
                val d = CompletableDeferred<Int>()
                if (!handler.post {
                        op = d
                        try {
                            requireConnectPermission()
                            if (closed) throw RpcException.Transport("BLE closed")
                            if (!action())
                                d.completeExceptionally(RpcException.Transport("GATT operation rejected"))
                        } catch (e: Exception) {
                            d.completeExceptionally(e)
                        }
                    }) throw RpcException.Transport("BLE closed")
                val status = try {
                    withTimeout(10000) { d.await() }
                } finally {
                    handler.post { if (op === d) op = null }
                }
                if (status == 0) break
                if (!retryAfterBond || !shouldRetryGattAfterBond(status, retried))
                    throw RpcException.Transport("GATT operation status $status")
                retried = true
                val device = onGattThread {
                    requireConnectPermission()
                    gatt?.device ?: throw RpcException.Transport("BLE closed")
                }
                awaitBond(device)
            }
        }

    override suspend fun open() {
        requireConnectPermission()
        val manager = context.getSystemService(BluetoothManager::class.java)
        val device =
            manager.adapter?.getRemoteDevice(address)
                ?: throw RpcException.Transport("Bluetooth unavailable")
        awaitBond(device)
        var attempt = 0
        while (true) {
            try {
                connectAndSetUp(device)
                break
            } catch (e: Exception) {
                if (e is CancellationException && e !is TimeoutCancellationException) throw e
                val stop = runCatching {
                    onGattThread {
                        runCatching { gatt?.disconnect() }
                        runCatching { gatt?.close() }
                        gatt = null
                        discovered = false
                        closed
                    }
                }.getOrDefault(true)
                if (stop || attempt >= 2 || !isTransientGattFailure(e.message)) throw e
                attempt++
                delay(400L * attempt)
            }
        }
        onGattThread {
            opened = true
            // Ask for the 2M PHY only now that the link is up, encrypted and configured:
            // a PHY update racing pairing or another LL procedure can drop the link (0x22).
            runCatching {
                requireConnectPermission()
                gatt?.setPreferredPhy(
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_LE_2M_MASK,
                    BluetoothDevice.PHY_OPTION_NO_PREFERRED,
                )
            }
        }
    }

    private suspend fun connectAndSetUp(device: BluetoothDevice) {
        ready = CompletableDeferred()
        onGattThread {
            requireConnectPermission()
            if (closed) throw RpcException.Transport("BLE closed")
            discovered = false
            // Connect on the default 1M PHY; requesting 2M here makes the stack start a PHY
            // update during connection setup.
            gatt =
                device.connectGatt(
                    context,
                    false,
                    callback,
                    BluetoothDevice.TRANSPORT_LE,
                    BluetoothDevice.PHY_LE_1M_MASK,
                    handler,
                )
        }
        withTimeout(15000) { ready.await() }
        operation { gatt!!.requestMtu(517) }
        for (id in listOf(BleIds.events, BleIds.rpc)) operation(retryAfterBond = true) {
            val g = gatt!!
            val c =
                g.getService(BleIds.service)?.getCharacteristic(id)
                    ?: throw RpcException.Transport("Missing ESPHID characteristic")
            check(g.setCharacteristicNotification(c, true))
            val desc = c.getDescriptor(BleIds.cccd) ?: throw RpcException.Transport("Missing CCCD")
            if (Build.VERSION.SDK_INT >= 33)
                g.writeDescriptor(desc, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                    BluetoothStatusCodes.SUCCESS
            else {
                desc.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                g.writeDescriptor(desc)
            }
        }
    }

    private suspend fun write(id: UUID, b: ByteArray, type: Int) = operation(retryAfterBond = true) {
        val g = gatt ?: throw RpcException.Transport("BLE closed")
        val c =
            g.getService(BleIds.service)?.getCharacteristic(id)
                ?: throw RpcException.Transport("Missing characteristic")
        if (Build.VERSION.SDK_INT >= 33)
            g.writeCharacteristic(c, b, type) == BluetoothStatusCodes.SUCCESS
        else {
            c.writeType = type
            c.value = b
            g.writeCharacteristic(c)
        }
    }

    override suspend fun send(bytes: ByteArray) =
        write(BleIds.hid, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)

    override fun close() {
        mux.close()
        op?.completeExceptionally(RpcException.Transport("Closed"))
        handler.post {
            closed = true
            bondWait?.completeExceptionally(RpcException.Transport("Closed"))
            try {
                runCatching { gatt?.disconnect() }
                runCatching { gatt?.close() }
                gatt = null
            } finally {
                clearBondWait()
                thread.quitSafely()
            }
        }
    }
}

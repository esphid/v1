package com.scalexy.esphid.core

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString

class DeviceStore(context: Context) {
    private val prefs =
        context.applicationContext.getSharedPreferences("esphid.devices", Context.MODE_PRIVATE)
    private val mutable =
        MutableStateFlow(
            runCatching {
                    ProtocolJson.decodeFromString<List<SavedDevice>>(
                        prefs.getString("devices", "[]")!!
                    )
                }
                .getOrDefault(emptyList())
        )
    val devices: StateFlow<List<SavedDevice>> = mutable.asStateFlow()
    private val mutex = Mutex()

    suspend fun upsert(d: SavedDevice) =
        mutex.withLock { save(mutable.value.filterNot { it.id == d.id } + d) }

    suspend fun remove(id: String) =
        mutex.withLock {
            save(mutable.value.filterNot { it.id == id })
            withContext(Dispatchers.IO) {
                setToken(id, null)
                setMqttPassword(id, null)
            }
        }

    private suspend fun save(v: List<SavedDevice>) =
        withContext(Dispatchers.IO) {
            check(prefs.edit().putString("devices", ProtocolJson.encodeToString(v)).commit())
            mutable.value = v
        }

    private fun key(): SecretKey =
        synchronized(DeviceStore::class.java) {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            (ks.getKey("esphid.secrets.v1", null) as? SecretKey)
                ?: KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
                    .apply {
                        init(
                            KeyGenParameterSpec.Builder(
                                    "esphid.secrets.v1",
                                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                                )
                                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                                .build()
                        )
                    }
                    .generateKey()
        }

    private fun get(k: String): String? {
        val s = prefs.getString(k, null) ?: return null
        return runCatching {
                val b = Base64.decode(s, Base64.NO_WRAP)
                val c = Cipher.getInstance("AES/GCM/NoPadding")
                c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, b.copyOfRange(0, 12)))
                c.updateAAD(k.toByteArray())
                String(c.doFinal(b.copyOfRange(12, b.size)), Charsets.UTF_8)
            }
            .getOrNull()
    }

    private fun put(k: String, v: String?) {
        val edit = prefs.edit()
        if (v == null) edit.remove(k)
        else {
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.ENCRYPT_MODE, key())
            c.updateAAD(k.toByteArray())
            edit.putString(
                k,
                Base64.encodeToString(c.iv + c.doFinal(v.toByteArray()), Base64.NO_WRAP),
            )
        }
        check(edit.commit())
    }

    fun token(id: String) = get("token:$id")

    fun setToken(id: String, token: String?) = put("token:$id", token)

    fun mqttPassword(id: String) = get("mqtt:$id")

    fun setMqttPassword(id: String, pw: String?) = put("mqtt:$id", pw)
}

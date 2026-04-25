package com.nexio.tv.data.local

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

private val Context.durableDeviceCredentialDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "durable_device_credential"
)

private const val KEYSTORE_ALIAS = "nexio_durable_device_secret"
private const val ENCRYPTED_SECRET_PREFIX = "enc::"
private const val GCM_IV_LENGTH_BYTES = 12
private const val GCM_TAG_LENGTH_BITS = 128
private const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"

data class DurableDeviceCredentialSnapshot(
    val devicePublicId: String? = null,
    val deviceSecret: String? = null,
    val ownerUserId: String? = null
) {
    val isComplete: Boolean
        get() = !devicePublicId.isNullOrBlank() && !deviceSecret.isNullOrBlank()
}

data class PendingDurableCredentialRevokeSnapshot(
    val devicePublicId: String? = null,
    val deviceSecret: String? = null
) {
    val isComplete: Boolean
        get() = !devicePublicId.isNullOrBlank() && !deviceSecret.isNullOrBlank()
}

internal interface DurableDeviceSecretProtector {
    fun encrypt(plaintext: String): String
    fun decrypt(ciphertext: String): String?
}

internal class AndroidKeystoreDurableDeviceSecretProtector : DurableDeviceSecretProtector {
    override fun encrypt(plaintext: String): String {
        val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(plaintext.toByteArray(StandardCharsets.UTF_8))
        val payload = cipher.iv + encrypted
        return ENCRYPTED_SECRET_PREFIX + Base64.encodeToString(payload, Base64.NO_WRAP)
    }

    override fun decrypt(ciphertext: String): String? {
        if (!ciphertext.startsWith(ENCRYPTED_SECRET_PREFIX)) return null
        return runCatching {
            val payload = Base64.decode(ciphertext.removePrefix(ENCRYPTED_SECRET_PREFIX), Base64.DEFAULT)
            if (payload.size <= GCM_IV_LENGTH_BYTES) return null
            val iv = payload.copyOfRange(0, GCM_IV_LENGTH_BYTES)
            val encrypted = payload.copyOfRange(GCM_IV_LENGTH_BYTES, payload.size)
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = keyStore.getKey(KEYSTORE_ALIAS, null) as? SecretKey
        if (existing != null) return existing

        val keyGenerator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        keyGenerator.init(
            KeyGenParameterSpec.Builder(
                KEYSTORE_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return keyGenerator.generateKey()
    }
}

@Singleton
class DurableDeviceCredentialStore internal constructor(
    private val dataStore: DataStore<Preferences>,
    private val secretProtector: DurableDeviceSecretProtector = AndroidKeystoreDurableDeviceSecretProtector()
) {
    @Inject
    constructor(
        @ApplicationContext context: Context
    ) : this(
        dataStore = context.durableDeviceCredentialDataStore,
        secretProtector = AndroidKeystoreDurableDeviceSecretProtector()
    )

    private val devicePublicIdKey = stringPreferencesKey("device_public_id")
    private val deviceSecretKey = stringPreferencesKey("device_secret")
    private val ownerUserIdKey = stringPreferencesKey("owner_user_id")
    private val pendingRevokeDevicePublicIdKey = stringPreferencesKey("pending_revoke_device_public_id")
    private val pendingRevokeDeviceSecretKey = stringPreferencesKey("pending_revoke_device_secret")

    suspend fun snapshot(): DurableDeviceCredentialSnapshot {
        val prefs = dataStore.data.first()
        val rawSecret = prefs[deviceSecretKey]?.trim()?.takeIf { it.isNotBlank() }
        val secret = decryptStoredSecret(rawSecret)

        if (rawSecret != null && secret == null) {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.remove(deviceSecretKey)
            }
        } else if (rawSecret != null && !rawSecret.startsWith(ENCRYPTED_SECRET_PREFIX)) {
            dataStore.edit { mutablePrefs ->
                mutablePrefs[deviceSecretKey] = secretProtector.encrypt(secret.orEmpty())
            }
        }

        return DurableDeviceCredentialSnapshot(
            devicePublicId = prefs[devicePublicIdKey]?.trim()?.takeIf { it.isNotBlank() },
            deviceSecret = secret,
            ownerUserId = prefs[ownerUserIdKey]?.trim()?.takeIf { it.isNotBlank() }
        )
    }

    suspend fun pendingRevokeSnapshot(): PendingDurableCredentialRevokeSnapshot {
        val prefs = dataStore.data.first()
        val rawSecret = prefs[pendingRevokeDeviceSecretKey]
        val secret = rawSecret?.let { stored ->
            runCatching { secretProtector.decrypt(stored) }.getOrNull()
        }

        if (rawSecret != null && secret == null) {
            dataStore.edit { mutablePrefs ->
                mutablePrefs.remove(pendingRevokeDeviceSecretKey)
            }
        }

        return PendingDurableCredentialRevokeSnapshot(
            devicePublicId = prefs[pendingRevokeDevicePublicIdKey]?.trim()?.takeIf { it.isNotBlank() },
            deviceSecret = secret
        )
    }

    suspend fun save(devicePublicId: String, deviceSecret: String, ownerUserId: String? = null) {
        val normalizedPublicId = devicePublicId.trim()
        val normalizedSecret = deviceSecret.trim()
        val normalizedOwnerUserId = ownerUserId?.trim()?.takeIf { it.isNotBlank() }
        dataStore.edit { prefs ->
            if (normalizedPublicId.isBlank()) {
                prefs.remove(devicePublicIdKey)
            } else {
                prefs[devicePublicIdKey] = normalizedPublicId
            }
            if (normalizedSecret.isBlank()) {
                prefs.remove(deviceSecretKey)
            } else {
                prefs[deviceSecretKey] = secretProtector.encrypt(normalizedSecret)
            }
            if (normalizedOwnerUserId == null) {
                prefs.remove(ownerUserIdKey)
            } else {
                prefs[ownerUserIdKey] = normalizedOwnerUserId
            }
        }
    }

    suspend fun savePendingRevoke(devicePublicId: String, deviceSecret: String) {
        val normalizedPublicId = devicePublicId.trim()
        val normalizedSecret = deviceSecret.trim()
        dataStore.edit { prefs ->
            if (normalizedPublicId.isBlank() || normalizedSecret.isBlank()) {
                prefs.remove(pendingRevokeDevicePublicIdKey)
                prefs.remove(pendingRevokeDeviceSecretKey)
            } else {
                prefs[pendingRevokeDevicePublicIdKey] = normalizedPublicId
                prefs[pendingRevokeDeviceSecretKey] = secretProtector.encrypt(normalizedSecret)
            }
        }
    }

    suspend fun clearPendingRevoke() {
        dataStore.edit { prefs ->
            prefs.remove(pendingRevokeDevicePublicIdKey)
            prefs.remove(pendingRevokeDeviceSecretKey)
        }
    }

    suspend fun bindOwnerIfMissing(ownerUserId: String) {
        val normalizedOwnerUserId = ownerUserId.trim()
        if (normalizedOwnerUserId.isBlank()) return
        dataStore.edit { prefs ->
            val existing = prefs[ownerUserIdKey]?.trim()?.takeIf { it.isNotBlank() }
            val hasCredential =
                !prefs[devicePublicIdKey].isNullOrBlank() && !prefs[deviceSecretKey].isNullOrBlank()
            if (existing == null && hasCredential) {
                prefs[ownerUserIdKey] = normalizedOwnerUserId
            }
        }
    }

    suspend fun clearIfOwnerMissingOrMismatch(expectedOwnerUserId: String): Boolean {
        val normalizedExpectedOwnerUserId = expectedOwnerUserId.trim()
        if (normalizedExpectedOwnerUserId.isBlank()) return false

        val snapshot = snapshot()
        if (!snapshot.isComplete) return false
        val normalizedStoredOwnerUserId = snapshot.ownerUserId?.trim()?.takeIf { it.isNotBlank() }
        if (normalizedStoredOwnerUserId == normalizedExpectedOwnerUserId) return false

        clear()
        return true
    }

    suspend fun clear() {
        dataStore.edit { prefs ->
            prefs.remove(devicePublicIdKey)
            prefs.remove(deviceSecretKey)
            prefs.remove(ownerUserIdKey)
        }
    }

    private fun decryptStoredSecret(rawSecret: String?): String? {
        if (rawSecret.isNullOrBlank()) return null
        if (!rawSecret.startsWith(ENCRYPTED_SECRET_PREFIX)) return rawSecret
        return secretProtector.decrypt(rawSecret)?.trim()?.takeIf { it.isNotBlank() }
    }
}

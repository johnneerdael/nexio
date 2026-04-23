package com.nexio.tv.data.local

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.test.core.app.ApplicationProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class DurableDeviceCredentialStoreTest {
    private val devicePublicIdKey = stringPreferencesKey("device_public_id")
    private val deviceSecretKey = stringPreferencesKey("device_secret")

    @Test
    fun `save encrypts device secret before writing preferences`() = runTest {
        val fixture = storeFixture()

        fixture.store.save(
            devicePublicId = "device-public-id",
            deviceSecret = "plain-secret"
        )

        val prefs = fixture.dataStore.data.first()
        assertEquals("device-public-id", prefs[devicePublicIdKey])
        assertEquals("enc::plain-secret::cipher", prefs[deviceSecretKey])
        assertFalse(prefs[deviceSecretKey] == "plain-secret")
    }

    @Test
    fun `snapshot decrypts encrypted secret back into the returned credential`() = runTest {
        val fixture = storeFixture()
        fixture.dataStore.edit { prefs ->
            prefs[devicePublicIdKey] = "device-public-id"
            prefs[deviceSecretKey] = "enc::plain-secret::cipher"
        }

        val snapshot = fixture.store.snapshot()

        assertEquals("device-public-id", snapshot.devicePublicId)
        assertEquals("plain-secret", snapshot.deviceSecret)
        assertTrue(snapshot.isComplete)
    }

    @Test
    fun `snapshot migrates legacy plaintext secret into protected storage`() = runTest {
        val fixture = storeFixture()
        fixture.dataStore.edit { prefs ->
            prefs[devicePublicIdKey] = "device-public-id"
            prefs[deviceSecretKey] = "plain-secret"
        }

        val snapshot = fixture.store.snapshot()
        val prefs = fixture.dataStore.data.first()

        assertEquals("plain-secret", snapshot.deviceSecret)
        assertEquals("enc::plain-secret::cipher", prefs[deviceSecretKey])
    }

    @Test
    fun `snapshot drops undecryptable secret material`() = runTest {
        val fixture = storeFixture()
        fixture.dataStore.edit { prefs ->
            prefs[devicePublicIdKey] = "device-public-id"
            prefs[deviceSecretKey] = "enc::broken"
        }

        val snapshot = fixture.store.snapshot()

        assertEquals("device-public-id", snapshot.devicePublicId)
        assertEquals(null, snapshot.deviceSecret)
        assertFalse(snapshot.isComplete)
    }

    private fun storeFixture(): StoreFixture {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val file = File(context.filesDir, "test-durable-${UUID.randomUUID()}.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            produceFile = { file }
        )
        val store = DurableDeviceCredentialStore(
            dataStore = dataStore,
            secretProtector = FakeDurableDeviceSecretProtector()
        )
        return StoreFixture(store = store, dataStore = dataStore)
    }

    private data class StoreFixture(
        val store: DurableDeviceCredentialStore,
        val dataStore: androidx.datastore.core.DataStore<androidx.datastore.preferences.core.Preferences>
    )

    private class FakeDurableDeviceSecretProtector : DurableDeviceSecretProtector {
        override fun encrypt(plaintext: String): String = "enc::$plaintext::cipher"

        override fun decrypt(ciphertext: String): String? {
            if (!ciphertext.startsWith("enc::") || !ciphertext.endsWith("::cipher")) return null
            return ciphertext.removePrefix("enc::").removeSuffix("::cipher")
        }
    }
}

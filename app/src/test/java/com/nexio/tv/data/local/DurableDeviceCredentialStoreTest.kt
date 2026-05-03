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
    private val ownerUserIdKey = stringPreferencesKey("owner_user_id")

    @Test
    fun `save encrypts device secret before writing preferences`() = runTest {
        val fixture = storeFixture()

        fixture.store.save(
            devicePublicId = "device-public-id",
            deviceSecret = "plain-secret",
            ownerUserId = "owner-a"
        )

        val prefs = fixture.dataStore.data.first()
        assertEquals("device-public-id", prefs[devicePublicIdKey])
        assertEquals("enc::plain-secret::cipher", prefs[deviceSecretKey])
        assertEquals("owner-a", prefs[ownerUserIdKey])
        assertFalse(prefs[deviceSecretKey] == "plain-secret")
    }

    @Test
    fun `snapshot decrypts encrypted secret back into the returned credential`() = runTest {
        val fixture = storeFixture()
        fixture.dataStore.edit { prefs ->
            prefs[devicePublicIdKey] = "device-public-id"
            prefs[deviceSecretKey] = "enc::plain-secret::cipher"
            prefs[ownerUserIdKey] = "owner-a"
        }

        val snapshot = fixture.store.snapshot()

        assertEquals("device-public-id", snapshot.devicePublicId)
        assertEquals("plain-secret", snapshot.deviceSecret)
        assertEquals("owner-a", snapshot.ownerUserId)
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

    @Test
    fun `clearIfOwnerMissingOrMismatch removes stale durable credential`() = runTest {
        val fixture = storeFixture()
        fixture.store.save(
            devicePublicId = "device-public-id",
            deviceSecret = "plain-secret"
        )

        assertTrue(fixture.store.clearIfOwnerMissingOrMismatch("owner-b"))

        val snapshot = fixture.store.snapshot()
        assertEquals(null, snapshot.devicePublicId)
        assertEquals(null, snapshot.deviceSecret)
        assertEquals(null, snapshot.ownerUserId)
    }

    @Test
    fun `clearIfOwnerMissingOrMismatch keeps matching owner-bound credential`() = runTest {
        val fixture = storeFixture()
        fixture.store.save(
            devicePublicId = "device-public-id",
            deviceSecret = "plain-secret",
            ownerUserId = "owner-a"
        )

        assertFalse(fixture.store.clearIfOwnerMissingOrMismatch("owner-a"))

        val snapshot = fixture.store.snapshot()
        assertEquals("device-public-id", snapshot.devicePublicId)
        assertEquals("plain-secret", snapshot.deviceSecret)
        assertEquals("owner-a", snapshot.ownerUserId)
    }

    @Test
    fun `pending revoke survives active credential clear without becoming recoverable`() = runTest {
        val fixture = storeFixture()
        val store = fixture.store

        store.save(
            devicePublicId = "active-public-id",
            deviceSecret = "active-secret",
            ownerUserId = "owner-id"
        )
        store.savePendingRevoke(
            devicePublicId = "active-public-id",
            deviceSecret = "active-secret"
        )
        store.clear()

        val active = store.snapshot()
        val pending = store.pendingRevokeSnapshot()

        assertFalse(active.isComplete)
        assertEquals("active-public-id", pending.devicePublicId)
        assertEquals("active-secret", pending.deviceSecret)
        assertTrue(pending.isComplete)
    }

    @Test
    fun `clear pending revoke removes only pending revoke data`() = runTest {
        val fixture = storeFixture()
        val store = fixture.store

        store.save(
            devicePublicId = "active-public-id",
            deviceSecret = "active-secret",
            ownerUserId = "owner-id"
        )
        store.savePendingRevoke(
            devicePublicId = "pending-public-id",
            deviceSecret = "pending-secret"
        )
        store.clearPendingRevoke()

        val active = store.snapshot()
        val pending = store.pendingRevokeSnapshot()

        assertTrue(active.isComplete)
        assertEquals("active-public-id", active.devicePublicId)
        assertFalse(pending.isComplete)
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

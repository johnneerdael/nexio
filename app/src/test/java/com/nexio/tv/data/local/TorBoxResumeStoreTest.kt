package com.nexio.tv.data.local

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TorBoxResumeStoreTest {

    private fun fakeDataStore(): DataStore<Preferences> {
        val state = MutableStateFlow<Preferences>(mutablePreferencesOf())
        return object : DataStore<Preferences> {
            override val data = state
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                val mutable = (state.value).toMutablePreferences()
                val next = transform(mutable)
                state.update { next }
                return next
            }
            private fun Preferences.toMutablePreferences(): MutablePreferences {
                val mp = mutablePreferencesOf()
                @Suppress("UNCHECKED_CAST")
                this.asMap().forEach { (k, v) -> mp[k as Preferences.Key<Any>] = v }
                return mp
            }
        }
    }

    @Test
    fun `loadPosition returns null when nothing saved`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore(), profileIdProvider = { 0 })
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `savePosition then loadPosition round-trips millis`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore(), profileIdProvider = { 0 })
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 123_456L, durationMs = 3_600_000L)
        assertEquals(123_456L, store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `savePosition near end clears stored entry`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore(), profileIdProvider = { 0 })
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 1_500_000L, durationMs = 3_600_000L)
        // Within 30s of end (durationMs - 30_000 = 3_570_000) — should auto-clear.
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 3_580_000L, durationMs = 3_600_000L)
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `clear removes the entry`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore(), profileIdProvider = { 0 })
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 100L, durationMs = 1_000_000L)
        store.clear(torrentId = 7, fileId = 10)
        assertNull(store.loadPosition(torrentId = 7, fileId = 10))
    }

    @Test
    fun `entries are isolated per torrent and file`() = runTest {
        val store = TorBoxResumeStore(fakeDataStore(), profileIdProvider = { 0 })
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 100L, durationMs = 1_000_000L)
        store.savePosition(torrentId = 7, fileId = 11, positionMs = 200L, durationMs = 1_000_000L)
        store.savePosition(torrentId = 8, fileId = 10, positionMs = 300L, durationMs = 1_000_000L)
        assertEquals(100L, store.loadPosition(7, 10))
        assertEquals(200L, store.loadPosition(7, 11))
        assertEquals(300L, store.loadPosition(8, 10))
    }

    @Test
    fun `entries are isolated per profile`() = runTest {
        val ds = fakeDataStore()
        var profile = 0
        val store = TorBoxResumeStore(ds, profileIdProvider = { profile })
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 111L, durationMs = 1_000_000L)
        profile = 1
        assertNull(store.loadPosition(7, 10))
        store.savePosition(torrentId = 7, fileId = 10, positionMs = 222L, durationMs = 1_000_000L)
        assertEquals(222L, store.loadPosition(7, 10))
        profile = 0
        assertEquals(111L, store.loadPosition(7, 10))
    }
}

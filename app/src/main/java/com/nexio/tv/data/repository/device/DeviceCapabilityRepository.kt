package com.nexio.tv.data.repository.device

import com.nexio.tv.data.local.DeviceCapabilityStore
import com.nexio.tv.data.local.DeviceCapabilityStoreContract
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshot
import com.nexio.tv.data.repository.benchmark.DeviceCapabilitySnapshotProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DeviceCapabilityRepository internal constructor(
    private val store: DeviceCapabilityStoreContract,
    private val capture: suspend () -> DeviceCapabilitySnapshot?
) {
    @Inject
    constructor(
        store: DeviceCapabilityStore,
        provider: DeviceCapabilitySnapshotProvider,
        playerSettingsDataStore: PlayerSettingsDataStore
    ) : this(
        store = store,
        capture = { provider.capture(playerSettingsDataStore.playerSettings.first()) }
    )

    private val mutex = Mutex()

    suspend fun ensureCached(): DeviceCapabilitySnapshot? = mutex.withLock {
        store.latestSnapshot.first()?.let { return@withLock it }
        val snapshot = capture()
        snapshot?.let { store.save(it) }
        snapshot
    }

    suspend fun snapshotForAutoplay(): DeviceCapabilitySnapshot? = ensureCached()
}

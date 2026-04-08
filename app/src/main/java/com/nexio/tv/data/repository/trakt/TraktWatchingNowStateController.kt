package com.nexio.tv.data.repository.trakt

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktWatchingNowStateController @Inject constructor() {

    data class Snapshot(
        val active: Boolean = false,
        val title: String? = null,
        val contentType: String? = null,
        val progressPercent: Float? = null,
        val updatedAtMs: Long = 0L
    )

    private val state = MutableStateFlow(Snapshot())
    private var optimisticVersion: Long = 0L

    fun observe(): StateFlow<Snapshot> = state.asStateFlow()

    fun current(): Snapshot = state.value

    fun publish(snapshot: Snapshot) {
        state.value = snapshot.copy(updatedAtMs = System.currentTimeMillis())
    }

    fun nextOptimisticVersion(): Long {
        optimisticVersion += 1L
        return optimisticVersion
    }

    fun rollbackIfCurrent(
        expectedVersion: Long,
        rollbackState: Snapshot
    ) {
        if (optimisticVersion == expectedVersion) {
            publish(rollbackState)
        }
    }
}

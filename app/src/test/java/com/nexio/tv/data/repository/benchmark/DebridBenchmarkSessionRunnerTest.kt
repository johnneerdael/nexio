package com.nexio.tv.data.repository.benchmark

import com.nexio.tv.data.local.PlayerSettings
import com.nexio.tv.data.local.VodCacheSizeMode
import org.junit.Assert.assertEquals
import org.junit.Test

class DebridBenchmarkSessionRunnerTest {

    @Test
    fun `transport config snapshot records current player transport settings`() {
        val snapshot = PlayerSettings(
            vodCacheSizeMode = VodCacheSizeMode.ON,
            useParallelConnections = true,
            parallelConnectionCount = 4,
            parallelChunkSizeMb = 32
        ).toBenchmarkTransportConfigSnapshot()

        assertEquals(true, snapshot.parallelConnectionsEnabled)
        assertEquals(4, snapshot.parallelConnectionCount)
        assertEquals(true, snapshot.vodCacheEnabled)
        assertEquals(32, snapshot.parallelChunkSizeMb)
    }
}

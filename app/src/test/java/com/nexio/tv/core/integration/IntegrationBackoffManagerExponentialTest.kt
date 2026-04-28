package com.nexio.tv.core.integration

import com.nexio.tv.data.local.integration.IntegrationProviderBackoffDao
import com.nexio.tv.data.local.integration.IntegrationProviderBackoffEntity
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IntegrationBackoffManagerExponentialTest {

    @Test
    fun `consecutive failures grow block window exponentially up to cap`() = runTest {
        val dao = InMemoryBackoffDao()
        val manager = IntegrationBackoffManager(
            dao = dao,
            baseMs = 2_000L,
            capMs = 60_000L,
            jitterMs = 0L
        )

        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        val first = dao.getEntity()!!
        assertEquals(1, first.consecutiveFailures)
        assertEquals(2_000L, first.blockedUntilEpochMs - first.updatedAtEpochMs)

        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        val second = dao.getEntity()!!
        assertEquals(2, second.consecutiveFailures)
        assertEquals(4_000L, second.blockedUntilEpochMs - second.updatedAtEpochMs)

        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        val third = dao.getEntity()!!
        assertEquals(3, third.consecutiveFailures)
        assertEquals(8_000L, third.blockedUntilEpochMs - third.updatedAtEpochMs)
    }

    @Test
    fun `clear resets consecutiveFailures and unblocks immediately`() = runTest {
        val dao = InMemoryBackoffDao()
        val manager = IntegrationBackoffManager(dao = dao, baseMs = 2_000L, capMs = 60_000L, jitterMs = 0L)

        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        assertTrue(manager.isBlocked(IntegrationProvider.TMDB, IntegrationScope.GlobalContent))

        manager.clear(IntegrationProvider.TMDB, IntegrationScope.GlobalContent)
        assertFalse(manager.isBlocked(IntegrationProvider.TMDB, IntegrationScope.GlobalContent))

        // Next failure starts back at base (not 8s)
        manager.noteHttpFailure(IntegrationProvider.TMDB, IntegrationScope.GlobalContent, 500, null, "err")
        val reset = dao.getEntity()!!
        assertEquals(1, reset.consecutiveFailures)
        assertEquals(2_000L, reset.blockedUntilEpochMs - reset.updatedAtEpochMs)
    }
}

private class InMemoryBackoffDao : IntegrationProviderBackoffDao {
    private val store = mutableMapOf<String, IntegrationProviderBackoffEntity>()
    override suspend fun upsert(entity: IntegrationProviderBackoffEntity) {
        store[entity.key] = entity
    }
    override suspend fun get(provider: String, scopeKey: String): IntegrationProviderBackoffEntity? =
        store["$provider:$scopeKey"]
    override suspend fun clear(provider: String, scopeKey: String) {
        store.remove("$provider:$scopeKey")
    }
    fun getEntity(): IntegrationProviderBackoffEntity? = store.values.firstOrNull()
}

package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktMutationOutboxStoreTest {

    @Test
    fun `write and read round trips snapshot`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(sampleEnvelope(id = "queued-1")),
            nextWritableAtMs = 3_000L,
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    @Test
    fun `future schema is ignored`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 99,
              "snapshot": {
                "items": [{"id":"future"}],
                "nextWritableAtMs": 50,
                "updatedAtMs": 60
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read()

        assertTrue(restored.items.isEmpty())
        assertEquals(0L, restored.nextWritableAtMs)
        assertEquals(0L, restored.updatedAtMs)
    }

    @Test
    fun `write and read round trips SIMKL mutation envelope snapshot`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(
                TraktMutationEnvelope(
                    id = "simkl-queued-1",
                    adapterKey = "simkl.library",
                    mutationKind = "simkl.library.addToList",
                    priority = TraktMutationPriorityBucket.WATCHLIST,
                    collapseKey = "simkl.library:simkl:plantowatch",
                    payload = JsonObject().apply { addProperty("contentId", "tt1375666") },
                    rollbackPayload = JsonObject().apply { addProperty("before", false) },
                    metadata = JsonObject().apply { addProperty("scope", "simkl") },
                    state = TraktMutationLifecycleState.QUEUED,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                    nextAttemptAtMs = 1_000L
                )
            ),
            nextWritableAtMs = 3_000L,
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun sampleEnvelope(id: String): TraktMutationEnvelope {
        return TraktMutationEnvelope(
            id = id,
            adapterKey = "progress",
            mutationKind = "history_add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "show-1",
            payload = JsonObject().apply { addProperty("contentId", "show-1") },
            rollbackPayload = JsonObject().apply { addProperty("before", false) },
            metadata = JsonObject().apply { addProperty("scope", "progress") },
            state = TraktMutationLifecycleState.QUEUED,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            nextAttemptAtMs = 1_000L
        )
    }
}

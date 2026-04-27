package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class ProviderMutationOutboxCrossProfileTest {

    @Test
    fun `profile1 drain only leases profile1 envelopes`() = runTest {
        val prefsP1 = InMemorySharedPreferences()
        val prefsP2 = InMemorySharedPreferences()
        val context = mockContext(
            "trakt_mutation_outbox" to prefsP1,
            "trakt_mutation_outbox_p2" to prefsP2
        )
        val store = TraktMutationOutboxStore(context = context)
        val worker = TraktMutationOutboxWorker(store = store)

        // Seed one envelope per profile
        worker.enqueue(envelope(id = "p1-a", profileId = 1))
        worker.enqueue(envelope(id = "p2-b", profileId = 2))

        // Drain for profile 1: must get p1-a
        val leasedP1 = worker.leaseNextReady(profileId = 1)
        assertNotNull("expected a lease for profile 1", leasedP1)
        assertEquals("p1-a", leasedP1!!.envelope.id)
        assertEquals(1, leasedP1.envelope.profileId)

        // No more leases for profile 1
        val leasedP1Again = worker.leaseNextReady(profileId = 1)
        assertNull("profile 1 must have no further envelopes", leasedP1Again)

        // Profile 2 still has its envelope intact
        val leasedP2 = worker.leaseNextReady(profileId = 2)
        assertNotNull("profile 2 envelope must still be available", leasedP2)
        assertEquals("p2-b", leasedP2!!.envelope.id)
        assertEquals(2, leasedP2.envelope.profileId)
    }

    private fun mockContext(vararg prefsByName: Pair<String, InMemorySharedPreferences>): Context =
        mockk(relaxed = true) {
            prefsByName.forEach { (name, prefs) ->
                every { getSharedPreferences(name, Context.MODE_PRIVATE) } returns prefs
            }
        }

    private fun envelope(id: String, profileId: Int): TraktMutationEnvelope =
        TraktMutationEnvelope(
            id = id,
            profileId = profileId,
            adapterKey = "progress",
            mutationKind = "history_add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "show-$id",
            payload = JsonObject().apply { addProperty("contentId", "show-$id") },
            rollbackPayload = JsonObject().apply { addProperty("before", false) },
            metadata = JsonObject().apply { addProperty("scope", "progress") },
            state = TraktMutationLifecycleState.QUEUED,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            nextAttemptAtMs = 1_000L
        )
}

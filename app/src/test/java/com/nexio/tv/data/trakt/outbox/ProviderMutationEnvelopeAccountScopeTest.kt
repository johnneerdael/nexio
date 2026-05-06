package com.nexio.tv.data.trakt.outbox

import com.google.gson.JsonObject
import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.Assert.assertThrows

class ProviderMutationEnvelopeAccountScopeTest {
    @Test
    fun `envelope requires credential hash`() {
        assertThrows(IllegalArgumentException::class.java) {
            scopedEnvelope(credentialHash = "")
        }
    }

    @Test
    fun `envelope requires positive profile id`() {
        assertThrows(IllegalArgumentException::class.java) {
            scopedEnvelope(profileId = 0)
        }
    }

    @Test
    fun `account scope key includes profile provider and credential`() {
        val envelope = scopedEnvelope(
            profileId = 2,
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-credential-hash"
        )

        assertEquals(
            "profile:2:provider:SIMKL:credential:simkl-credential-hash",
            envelope.accountScopeKey
        )
    }

    @Test
    fun `collapse does not cross credential hashes`() {
        val policy = TraktMutationOutboxPolicy()
        val first = scopedEnvelope(id = "first", credentialHash = "credential-a", collapseKey = "same-title")
        val second = scopedEnvelope(id = "second", credentialHash = "credential-b", collapseKey = "same-title")

        val afterFirst = policy.enqueue(TraktMutationOutboxSnapshot(), first, nowMs = 1_000L)
        val afterSecond = policy.enqueue(afterFirst, second, nowMs = 1_001L)

        assertEquals(2, afterSecond.items.count { it.state == TraktMutationLifecycleState.QUEUED })
    }

    private fun scopedEnvelope(
        id: String = "id",
        profileId: Int = 1,
        provider: TrackingProvider = TrackingProvider.TRAKT,
        credentialHash: String = "credential-hash",
        collapseKey: String? = "collapse"
    ): TraktMutationEnvelope =
        TraktMutationEnvelope(
            id = id,
            profileId = profileId,
            provider = provider,
            credentialHash = credentialHash,
            adapterKey = "progress",
            mutationKind = "history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = collapseKey,
            payload = JsonObject()
        )
}

package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktMutationOutboxPolicyTest {
    private val policy = TraktMutationOutboxPolicy()

    @Test
    fun `enqueue collapses older queued mutation with same collapse key`() {
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(
                sampleEnvelope(
                    id = "existing",
                    priority = TraktMutationPriorityBucket.WATCHED,
                    collapseKey = "show:1"
                )
            )
        )

        val updated = policy.enqueue(
            snapshot = snapshot,
            envelope = sampleEnvelope(
                id = "incoming",
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "show:1"
            ),
            nowMs = 2_000L
        )

        val collapsed = updated.items.first { it.id == "existing" }
        val incoming = updated.items.first { it.id == "incoming" }
        assertEquals(TraktMutationLifecycleState.COLLAPSED, collapsed.state)
        assertEquals("incoming", collapsed.supersededById)
        assertEquals(TraktMutationLifecycleState.QUEUED, incoming.state)
    }

    @Test
    fun `mutations with same collapse key but different profiles do not collapse`() {
        val first = sampleEnvelope(
            id = "p1",
            priority = TraktMutationPriorityBucket.SCROBBLE,
            profileId = 1,
            collapseKey = "scrobble:tt1375666"
        )
        val second = sampleEnvelope(
            id = "p2",
            priority = TraktMutationPriorityBucket.SCROBBLE,
            profileId = 2,
            collapseKey = "scrobble:tt1375666"
        )

        val afterFirst = policy.enqueue(TraktMutationOutboxSnapshot(), first, 1_000L)
        val afterSecond = policy.enqueue(afterFirst, second, 1_001L)

        assertEquals(2, afterSecond.items.count { it.state == TraktMutationLifecycleState.QUEUED })
    }


    @Test
    fun `lease selection yields lower priority bucket after scrobble burst`() {
        var snapshot = TraktMutationOutboxSnapshot(
            items = listOf(
                sampleEnvelope(id = "s1", priority = TraktMutationPriorityBucket.SCROBBLE),
                sampleEnvelope(id = "s2", priority = TraktMutationPriorityBucket.SCROBBLE, createdAtMs = 20L, updatedAtMs = 20L),
                sampleEnvelope(id = "s3", priority = TraktMutationPriorityBucket.SCROBBLE, createdAtMs = 30L, updatedAtMs = 30L),
                sampleEnvelope(id = "w1", priority = TraktMutationPriorityBucket.WATCHED, createdAtMs = 40L, updatedAtMs = 40L)
            )
        )
        var fairness = TraktMutationFairnessState()

        repeat(3) { index ->
            val selection = policy.leaseNextReady(
                snapshot = snapshot,
                nowMs = 10_000L + index,
                fairnessState = fairness,
                leaseDurationMs = 5_000L,
                leaseTokenFactory = { "lease-$index" }
            )
            assertEquals(TraktMutationPriorityBucket.SCROBBLE, selection?.lease?.envelope?.priority)
            fairness = selection!!.lease.fairnessState
            snapshot = selection.snapshot.copy(
                items = selection.snapshot.items.map { item ->
                    if (item.leaseToken == "lease-$index") {
                        item.copy(
                            state = TraktMutationLifecycleState.SUCCEEDED,
                            leaseToken = null,
                            leaseExpiresAtMs = null,
                            completedAtMs = 10_000L + index
                        )
                    } else {
                        item
                    }
                }
            )
        }

        val fairnessSelection = policy.leaseNextReady(
            snapshot = snapshot,
            nowMs = 20_000L,
            fairnessState = fairness,
            leaseDurationMs = 5_000L,
            leaseTokenFactory = { "lease-fair" }
        )

        assertEquals(TraktMutationPriorityBucket.WATCHED, fairnessSelection?.lease?.envelope?.priority)
    }

    @Test
    fun `classify failure honors retry after and terminal statuses`() {
        val rateLimited = policy.classifyFailure(
            failure = TraktMutationExecutionResult.Failure(
                httpStatusCode = 429,
                retryAfterHeader = "7",
                reason = "too many requests"
            ),
            attemptCount = 1,
            nowMs = 1_000L
        )
        val terminal = policy.classifyFailure(
            failure = TraktMutationExecutionResult.Failure(
                httpStatusCode = 404,
                reason = "missing"
            ),
            attemptCount = 1,
            nowMs = 1_000L
        )

        assertEquals(8_000L, (rateLimited as TraktMutationSettlement.Retryable).retryAtMs)
        assertTrue(terminal is TraktMutationSettlement.TerminalFailure)
    }

    @Test
    fun `retryable non rate limit failures do not globally stall other queued writes`() {
        val leased = sampleEnvelope(
            id = "leased",
            priority = TraktMutationPriorityBucket.SCROBBLE,
            createdAtMs = 10L,
            updatedAtMs = 10L
        ).copy(
            state = TraktMutationLifecycleState.LEASED,
            leaseToken = "lease-1",
            leaseExpiresAtMs = 5_000L
        )
        val queued = sampleEnvelope(
            id = "queued",
            priority = TraktMutationPriorityBucket.WATCHED,
            createdAtMs = 20L,
            updatedAtMs = 20L
        )
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(leased, queued)
        )

        val settled = policy.settleLease(
            snapshot = snapshot,
            leaseToken = "lease-1",
            settlement = TraktMutationSettlement.Retryable(
                retryAtMs = 61_000L,
                reason = "temporary upstream failure",
                httpStatusCode = 503
            ),
            nowMs = 1_000L
        )

        assertEquals(2_000L, settled.snapshot.nextWritableAtMs)
    }

    @Test
    fun `rate limited retry keeps the global write gate parked until retry after`() {
        val leased = sampleEnvelope(
            id = "leased",
            priority = TraktMutationPriorityBucket.SCROBBLE,
            createdAtMs = 10L,
            updatedAtMs = 10L
        ).copy(
            state = TraktMutationLifecycleState.LEASED,
            leaseToken = "lease-429",
            leaseExpiresAtMs = 5_000L
        )
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(leased)
        )

        val settled = policy.settleLease(
            snapshot = snapshot,
            leaseToken = "lease-429",
            settlement = TraktMutationSettlement.Retryable(
                retryAtMs = 61_000L,
                reason = "rate limited",
                httpStatusCode = 429
            ),
            nowMs = 1_000L
        )

        assertEquals(61_000L, settled.snapshot.nextWritableAtMs)
    }

    @Test
    fun `worker enforces next writable gate between settlements`() = runTest {
        val now = MutableNow(1_000L)
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = policy,
            timeProvider = { now.value },
            leaseDurationMs = 5_000L,
            leaseTokenFactory = { "lease-${now.value}" }
        )

        worker.enqueue(sampleEnvelope(id = "first", priority = TraktMutationPriorityBucket.WATCHED))
        now.value += 1
        worker.enqueue(sampleEnvelope(id = "second", priority = TraktMutationPriorityBucket.WATCHED))

        val firstLease = worker.leaseNextReady()
        assertEquals("first", firstLease?.envelope?.id)
        val firstSettled = worker.settle(
            profileId = firstLease!!.envelope.profileId,
            leaseToken = firstLease!!.envelope.leaseToken!!,
            settlement = TraktMutationSettlement.Succeeded(httpStatusCode = 201)
        )
        assertNotNull(firstSettled)

        assertNull(worker.leaseNextReady())

        now.value += 1_000L
        val secondLease = worker.leaseNextReady()
        assertEquals("second", secondLease?.envelope?.id)
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun sampleEnvelope(
        id: String,
        priority: TraktMutationPriorityBucket,
        collapseKey: String? = null,
        profileId: Int = 1,
        createdAtMs: Long = 10L,
        updatedAtMs: Long = 10L
    ): TraktMutationEnvelope {
        return TraktMutationEnvelope(
            id = id,
            profileId = profileId,
            provider = TrackingProvider.TRAKT,
            credentialHash = "trakt-test-credential",
            adapterKey = "progress",
            mutationKind = "progress.history",
            priority = priority,
            collapseKey = collapseKey,
            payload = JsonObject(),
            metadata = JsonObject(),
            state = TraktMutationLifecycleState.QUEUED,
            createdAtMs = createdAtMs,
            updatedAtMs = updatedAtMs,
            nextAttemptAtMs = createdAtMs
        )
    }

    private class MutableNow(var value: Long)
}

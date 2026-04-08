package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class TraktMutationOutboxCoordinatorTest {

    @Test
    fun `coordinator applies optimistic state then reconciles successful mutations`() = runTest {
        val adapter = RecordingAdapter(
            adapterKey = "progress",
            executionResult = TraktMutationExecutionResult.Success(httpStatusCode = 201)
        )
        val coordinator = buildCoordinator(adapter)
        val envelope = sampleEnvelope()

        coordinator.enqueueAndDrain(envelope)

        awaitTerminalState(coordinator, envelope.id)

        assertEquals(listOf(envelope.id), adapter.optimisticApplied)
        assertEquals(listOf(envelope.id), adapter.reconciled)
        assertTrue(adapter.rolledBack.isEmpty())
    }

    @Test
    fun `coordinator rolls terminal failures back to server truth`() = runTest {
        val adapter = RecordingAdapter(
            adapterKey = "progress",
            executionResult = TraktMutationExecutionResult.Failure(
                httpStatusCode = 404,
                reason = "missing"
            )
        )
        val coordinator = buildCoordinator(adapter)
        val envelope = sampleEnvelope()

        coordinator.enqueueAndDrain(envelope)

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals(listOf(envelope.id), adapter.optimisticApplied)
        assertEquals(listOf(envelope.id), adapter.rolledBack)
        assertTrue(adapter.reconciled.isEmpty())
    }

    @Test
    fun `coordinator resumes queued mutations that were persisted before startup`() = runTest {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val envelope = sampleEnvelope().copy(id = "persisted")
        store.write(
            TraktMutationOutboxSnapshot(items = listOf(envelope))
        )
        val adapter = RecordingAdapter(
            adapterKey = "progress",
            executionResult = TraktMutationExecutionResult.Success(httpStatusCode = 201)
        )
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )

        val coordinator = TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter)
        )

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertTrue(adapter.optimisticApplied.isEmpty())
        assertEquals(listOf(envelope.id), adapter.reconciled)
    }

    @Test
    fun `enqueueAndAwaitOrThrow surfaces terminal failures consistently`() = runBlocking {
        val adapter = RecordingAdapter(
            adapterKey = "progress",
            executionResult = TraktMutationExecutionResult.Failure(
                httpStatusCode = 404,
                reason = "missing"
            )
        )
        val coordinator = buildCoordinator(adapter)

        try {
            coordinator.enqueueAndAwaitOrThrow(sampleEnvelope(), timeoutMs = 2_000L)
            fail("Expected IllegalStateException")
        } catch (error: IllegalStateException) {
            assertEquals("missing", error.message)
        }
    }

    private suspend fun awaitTerminalState(
        coordinator: TraktMutationOutboxCoordinator,
        envelopeId: String
    ): TraktMutationEnvelope {
        repeat(500) {
            val item = coordinator.snapshot().items.firstOrNull { it.id == envelopeId }
                ?: error("Missing envelope $envelopeId")
            if (item.state == TraktMutationLifecycleState.SUCCEEDED ||
                item.state == TraktMutationLifecycleState.TERMINAL_FAILED
            ) {
                return item
            }
            Thread.sleep(10)
        }
        error("Timed out waiting for envelope $envelopeId to settle")
    }

    private fun buildCoordinator(
        adapter: RecordingAdapter
    ): TraktMutationOutboxCoordinator {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )
        return TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter)
        )
    }

    private fun sampleEnvelope(): TraktMutationEnvelope {
        return TraktMutationEnvelope(
            adapterKey = "progress",
            mutationKind = "history:add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "show:season-1",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private class RecordingAdapter(
        override val adapterKey: String,
        private val executionResult: TraktMutationExecutionResult
    ) : TraktMutationAdapter {
        val optimisticApplied = mutableListOf<String>()
        val reconciled = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()

        override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
            optimisticApplied += envelope.id
        }

        override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
            return executionResult
        }

        override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
            reconciled += envelope.id
        }

        override suspend fun rollbackToServerTruth(
            envelope: TraktMutationEnvelope,
            failure: TraktMutationSettlement.TerminalFailure
        ) {
            rolledBack += envelope.id
        }
    }
}

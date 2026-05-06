package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

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
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertTrue(adapter.optimisticApplied.isEmpty())
        assertEquals(listOf(envelope.id), adapter.reconciled)
    }

    @Test
    fun `coordinator settles persisted mutation in envelope profile store`() = runTest {
        val profileOnePrefs = InMemorySharedPreferences()
        val profileTwoPrefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(
            context = mockContext(
                "trakt_mutation_outbox" to profileOnePrefs,
                "trakt_mutation_outbox_p2" to profileTwoPrefs
            )
        )
        val envelope = sampleEnvelope().copy(id = "persisted-profile-two", profileId = 2)
        store.write(
            TraktMutationOutboxSnapshot(items = listOf(envelope)),
            profileId = 2
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
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )

        val settled = awaitTerminalState(coordinator, envelope.id, profileId = 2)

        assertTrue(coordinator.snapshot(profileId = 1).items.isEmpty())
        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertEquals(2, settled.profileId)
        assertEquals(listOf(envelope.id), adapter.reconciled)
    }

    @Test
    fun `coordinator resumes persisted SIMKL queued mutations on startup`() = runTest {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val envelope = TraktMutationEnvelope(
            id = "simkl-persisted",
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-test-credential",
            adapterKey = "simkl.progress-history",
            mutationKind = "simkl.progress.history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "tt1375666",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )
        store.write(
            TraktMutationOutboxSnapshot(items = listOf(envelope))
        )
        val adapter = RecordingAdapter(
            adapterKey = "simkl.progress-history",
            executionResult = TraktMutationExecutionResult.Success(httpStatusCode = 201)
        )
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )

        val coordinator = TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertTrue(adapter.optimisticApplied.isEmpty())
        assertEquals(listOf(envelope.id), adapter.reconciled)
    }

    @Test
    fun `coordinator retries transient SIMKL failures and eventually reconciles success`() = runTest {
        val adapter = SequencedRecordingAdapter(
            adapterKey = "simkl.library",
            executionResults = ArrayDeque(
                listOf(
                    TraktMutationExecutionResult.Failure(
                        reason = "offline",
                        throwable = IOException("offline")
                    ),
                    TraktMutationExecutionResult.Success(httpStatusCode = 200)
                )
            )
        )
        val coordinator = buildCoordinator(adapter)
        val envelope = TraktMutationEnvelope(
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-test-credential",
            adapterKey = "simkl.library",
            mutationKind = "simkl.library.addToList",
            priority = TraktMutationPriorityBucket.WATCHLIST,
            collapseKey = "simkl.library:plantowatch",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )

        coordinator.enqueueAndDrain(envelope)

        val settled = awaitTerminalState(coordinator, envelope.id, maxPolls = 900)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertEquals(2, adapter.executeCalls)
        assertEquals(listOf(envelope.id), adapter.reconciled)
        assertTrue(adapter.rolledBack.isEmpty())
    }

    @Test
    fun `coordinator rolls terminal SIMKL failures back to server truth`() = runTest {
        val adapter = RecordingAdapter(
            adapterKey = "simkl.library",
            executionResult = TraktMutationExecutionResult.Failure(
                httpStatusCode = 404,
                reason = "missing"
            )
        )
        val coordinator = buildCoordinator(adapter)
        val envelope = TraktMutationEnvelope(
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-test-credential",
            adapterKey = "simkl.library",
            mutationKind = "simkl.library.addToList",
            priority = TraktMutationPriorityBucket.WATCHLIST,
            collapseKey = "simkl.library:plantowatch",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )

        coordinator.enqueueAndDrain(envelope)

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals(listOf(envelope.id), adapter.optimisticApplied)
        assertEquals(listOf(envelope.id), adapter.rolledBack)
        assertTrue(adapter.reconciled.isEmpty())
    }

    @Test
    fun `coordinator rolls persisted SIMKL terminal failures back to server truth on startup`() = runTest {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val envelope = TraktMutationEnvelope(
            id = "simkl-persisted-terminal",
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-test-credential",
            adapterKey = "simkl.library",
            mutationKind = "simkl.library.addToList",
            priority = TraktMutationPriorityBucket.WATCHLIST,
            collapseKey = "simkl.library:plantowatch",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )
        store.write(
            TraktMutationOutboxSnapshot(items = listOf(envelope))
        )
        val adapter = RecordingAdapter(
            adapterKey = "simkl.library",
            executionResult = TraktMutationExecutionResult.Failure(
                httpStatusCode = 404,
                reason = "missing"
            )
        )
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )

        val coordinator = TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )

        val settled = awaitTerminalState(coordinator, envelope.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertTrue(adapter.optimisticApplied.isEmpty())
        assertEquals(listOf(envelope.id), adapter.rolledBack)
        assertTrue(adapter.reconciled.isEmpty())
    }

    @Test
    fun `coordinator retries persisted SIMKL transient failures on startup and eventually reconciles success`() = runTest {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val envelope = TraktMutationEnvelope(
            id = "simkl-persisted-retry",
            provider = TrackingProvider.SIMKL,
            credentialHash = "simkl-test-credential",
            adapterKey = "simkl.progress-history",
            mutationKind = "simkl.progress.history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "tt1375666",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )
        store.write(
            TraktMutationOutboxSnapshot(items = listOf(envelope))
        )
        val adapter = SequencedRecordingAdapter(
            adapterKey = "simkl.progress-history",
            executionResults = ArrayDeque(
                listOf(
                    TraktMutationExecutionResult.Failure(
                        reason = "offline",
                        throwable = IOException("offline")
                    ),
                    TraktMutationExecutionResult.Success(httpStatusCode = 200)
                )
            )
        )
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )

        val coordinator = TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )

        val settled = awaitTerminalState(coordinator, envelope.id, maxPolls = 900)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertTrue(adapter.optimisticApplied.isEmpty())
        assertEquals(2, adapter.executeCalls)
        assertEquals(listOf(envelope.id), adapter.reconciled)
        assertTrue(adapter.rolledBack.isEmpty())
    }

    private suspend fun awaitTerminalState(
        coordinator: TraktMutationOutboxCoordinator,
        envelopeId: String,
        profileId: Int = 1,
        maxPolls: Int = 500
    ): TraktMutationEnvelope {
        repeat(maxPolls) {
            val item = coordinator.snapshot(profileId).items.firstOrNull { it.id == envelopeId }
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
        adapter: TraktMutationAdapter
    ): TraktMutationOutboxCoordinator {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val worker = TraktMutationOutboxWorker(
            store = store,
            policy = TraktMutationOutboxPolicy()
        )
        return TraktMutationOutboxCoordinator(
            worker = worker,
            adapters = setOf(adapter),
            accountScopeValidator = AcceptingValidator
        )
    }

    private fun sampleEnvelope(): TraktMutationEnvelope {
        return TraktMutationEnvelope(
            provider = TrackingProvider.TRAKT,
            credentialHash = "trakt-test-credential",
            adapterKey = "progress",
            mutationKind = "history:add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "show:season-1",
            payload = JsonObject(),
            rollbackPayload = JsonObject(),
            metadata = JsonObject()
        )
    }

    private object AcceptingValidator : ProviderMutationAccountScopeValidator {
        override suspend fun validateForEnqueue(envelope: TraktMutationEnvelope) = Unit
        override suspend fun validateForExecute(envelope: TraktMutationEnvelope) = Unit
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun mockContext(vararg prefsByName: Pair<String, InMemorySharedPreferences>): Context {
        return mockk(relaxed = true) {
            prefsByName.forEach { (name, prefs) ->
                every { getSharedPreferences(name, Context.MODE_PRIVATE) } returns prefs
            }
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

    private class SequencedRecordingAdapter(
        override val adapterKey: String,
        private val executionResults: ArrayDeque<TraktMutationExecutionResult>
    ) : TraktMutationAdapter {
        val optimisticApplied = mutableListOf<String>()
        val reconciled = mutableListOf<String>()
        val rolledBack = mutableListOf<String>()
        var executeCalls: Int = 0

        override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
            optimisticApplied += envelope.id
        }

        override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
            executeCalls += 1
            return executionResults.removeFirstOrNull()
                ?: TraktMutationExecutionResult.Success(httpStatusCode = 200)
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

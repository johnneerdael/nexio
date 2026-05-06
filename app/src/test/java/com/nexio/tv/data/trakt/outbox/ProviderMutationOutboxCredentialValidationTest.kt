package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMutationOutboxCredentialValidationTest {
    @Test
    fun `enqueue rejects stale credential hash`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "current"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val stale = envelope(credentialHash = "stale")

        val result = runCatching { coordinator.enqueueAndDrain(stale) }

        assertTrue(result.exceptionOrNull() is ProviderMutationAccountScopeException)
        assertTrue(adapter.executed.isEmpty())
    }

    @Test
    fun `drain rejects credential hash changed after persistence`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "old"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "old"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "new"))

        val settled = awaitTerminalState(coordinator, queued.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(null, settled.lastHttpStatusCode)
    }

    @Test
    fun `account scope mismatch does not record provider 401 backoff`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "old"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)
        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "old"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "new"))

        val settled = awaitTerminalState(coordinator, queued.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(null, settled.lastHttpStatusCode)
    }

    @Test
    fun `token refresh does not invalidate outbox for same account`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "account-hash"))
        )
        val adapter = RecordingAdapter("progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)

        val queued = coordinator.enqueueAndDrain(envelope(credentialHash = "account-hash"))
        validator.sessions = mapOf(TrackingProvider.TRAKT to TrackingAuthSession(TrackingProvider.TRAKT, 1, "account-hash"))

        val settled = awaitTerminalState(coordinator, queued.id)

        assertEquals(TraktMutationLifecycleState.SUCCEEDED, settled.state)
        assertEquals(listOf(queued.id), adapter.executed)
    }

    @Test
    fun `reauth different account rejects old outbox mutations`() = runTest {
        val validator = FakeValidator(
            sessions = mapOf(TrackingProvider.SIMKL to TrackingAuthSession(TrackingProvider.SIMKL, 1, "account-a"))
        )
        val adapter = RecordingAdapter("simkl.progress", TraktMutationExecutionResult.Success(200))
        val coordinator = testCoordinator(adapter, validator)

        val queued = coordinator.enqueueAndDrain(
            envelope(provider = TrackingProvider.SIMKL, credentialHash = "account-a", adapterKey = "simkl.progress")
        )
        validator.sessions = mapOf(TrackingProvider.SIMKL to TrackingAuthSession(TrackingProvider.SIMKL, 1, "account-b"))

        val settled = awaitTerminalState(coordinator, queued.id)

        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, settled.state)
        assertEquals("ACCOUNT_SCOPE_MISMATCH", settled.lastError)
        assertEquals(emptyList<String>(), adapter.executed)
    }

    private suspend fun awaitTerminalState(
        coordinator: ProviderMutationOutboxCoordinator,
        envelopeId: String,
        maxPolls: Int = 500
    ): TraktMutationEnvelope {
        repeat(maxPolls) {
            val item = coordinator.snapshot(profileId = 1).items.firstOrNull { it.id == envelopeId }
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

    private fun envelope(
        provider: TrackingProvider = TrackingProvider.TRAKT,
        credentialHash: String,
        adapterKey: String = "progress"
    ): TraktMutationEnvelope =
        TraktMutationEnvelope(
            profileId = 1,
            provider = provider,
            credentialHash = credentialHash,
            adapterKey = adapterKey,
            mutationKind = "progress.history.add",
            priority = TraktMutationPriorityBucket.WATCHED,
            payload = JsonObject()
        )

    private class FakeValidator(
        var sessions: Map<TrackingProvider, TrackingAuthSession>
    ) : ProviderMutationAccountScopeValidator {
        override suspend fun validateForEnqueue(envelope: TraktMutationEnvelope) {
            validate(envelope)
        }

        override suspend fun validateForExecute(envelope: TraktMutationEnvelope) {
            validate(envelope)
        }

        private fun validate(envelope: TraktMutationEnvelope) {
            val session = sessions[envelope.provider]
            if (session?.profileId != envelope.profileId || session.credentialHash != envelope.credentialHash) {
                throw ProviderMutationAccountScopeException("ACCOUNT_SCOPE_MISMATCH")
            }
        }
    }

    private class RecordingAdapter(
        override val adapterKey: String,
        private val result: TraktMutationExecutionResult
    ) : TraktMutationAdapter {
        val executed = mutableListOf<String>()

        override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

        override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
            executed += envelope.id
            return result
        }

        override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) = Unit

        override suspend fun rollbackToServerTruth(
            envelope: TraktMutationEnvelope,
            failure: TraktMutationSettlement.TerminalFailure
        ) = Unit
    }

    private fun testCoordinator(
        adapter: TraktMutationAdapter,
        validator: ProviderMutationAccountScopeValidator
    ): ProviderMutationOutboxCoordinator {
        val store = TraktMutationOutboxStore(context = mockContext(InMemorySharedPreferences()))
        val worker = TraktMutationOutboxWorker(store = store)
        return ProviderMutationOutboxCoordinator(worker, setOf(adapter), validator)
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }
}

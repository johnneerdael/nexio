package com.nexio.tv.data.trakt.outbox

import com.nexio.tv.domain.model.TrackingProvider
import org.junit.Assert.assertTrue
import org.junit.Test

class ProviderMutationOutboxPolicySimklTest {
    @Test
    fun `simkl scrobble 423 is retryable like CrossWatch`() {
        val result = TraktMutationOutboxPolicy().classifyFailure(
            failure = TraktMutationExecutionResult.Failure(
                httpStatusCode = 423,
                retryAfterHeader = "7",
                reason = "locked"
            ),
            attemptCount = 1,
            nowMs = 1_000L,
            provider = TrackingProvider.SIMKL
        )

        assertTrue(result is TraktMutationSettlement.Retryable)
        assertTrue((result as TraktMutationSettlement.Retryable).retryAtMs >= 8_000L)
    }

    @Test
    fun `trakt 423 remains terminal`() {
        val result = TraktMutationOutboxPolicy().classifyFailure(
            failure = TraktMutationExecutionResult.Failure(
                httpStatusCode = 423,
                retryAfterHeader = "7",
                reason = "locked"
            ),
            attemptCount = 1,
            nowMs = 1_000L,
            provider = TrackingProvider.TRAKT
        )

        assertTrue(result is TraktMutationSettlement.TerminalFailure)
    }
}

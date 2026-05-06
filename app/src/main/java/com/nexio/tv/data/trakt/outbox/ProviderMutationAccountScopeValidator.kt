package com.nexio.tv.data.trakt.outbox

import com.nexio.tv.data.repository.TrackingAccountScopeProvider
import javax.inject.Inject
import javax.inject.Singleton

class ProviderMutationAccountScopeException(
    message: String
) : IllegalStateException(message)

interface ProviderMutationAccountScopeValidator {
    suspend fun validateForEnqueue(envelope: TraktMutationEnvelope)
    suspend fun validateForExecute(envelope: TraktMutationEnvelope)
}

@Singleton
class DefaultProviderMutationAccountScopeValidator @Inject constructor(
    private val accountScopeProvider: TrackingAccountScopeProvider
) : ProviderMutationAccountScopeValidator {
    override suspend fun validateForEnqueue(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    override suspend fun validateForExecute(envelope: TraktMutationEnvelope) {
        validate(envelope)
    }

    private suspend fun validate(envelope: TraktMutationEnvelope) {
        val current = accountScopeProvider.accountScopedSession(
            provider = envelope.provider,
            profileId = envelope.profileId
        )
        if (current.credentialHash.isNullOrBlank() || current.credentialHash != envelope.credentialHash) {
            throw ProviderMutationAccountScopeException("ACCOUNT_SCOPE_MISMATCH")
        }
    }
}

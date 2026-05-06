package com.nexio.tv.data.repository.trakt

import com.google.gson.JsonObject
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.TraktSettingsDataStore
import com.nexio.tv.data.repository.TraktRecommendationRef
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.TrackingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktDiscoveryMutationAdapter @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider,
    private val traktSettingsDataStore: TraktSettingsDataStore
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
        traktSettingsDataStore.addDismissedRecommendationKey(
            key = envelope.recommendationKey()
        )
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val session = TrackingAuthSession(
            provider = envelope.provider,
            profileId = envelope.profileId,
            credentialHash = envelope.credentialHash
        )
        val response = traktIntegrationProvider.hideRecommendation(
            session = session,
            type = envelope.recommendationType(),
            id = envelope.pathId()
        ) ?: return TraktMutationExecutionResult.Failure(
            reason = "Trakt request failed"
        )

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(
                httpStatusCode = response.code()
            )
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to hide recommendation (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) = Unit

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        traktSettingsDataStore.clearDismissedRecommendationKey(
            key = envelope.recommendationKey()
        )
    }

    companion object {
        private const val PAYLOAD_RECOMMENDATION_KEY = "recommendationKey"
        private const val PAYLOAD_RECOMMENDATION_TYPE = "type"
        private const val PAYLOAD_PATH_ID = "pathId"

        const val ADAPTER_KEY = "discovery"
        private const val MUTATION_KIND = "discovery.hideRecommendation"

        fun buildDismissRecommendationEnvelope(
            ref: TraktRecommendationRef,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_RECOMMENDATION_KEY, ref.recommendationKey)
                addProperty(PAYLOAD_RECOMMENDATION_TYPE, ref.type)
                addProperty(PAYLOAD_PATH_ID, ref.pathId)
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.TRAKT,
                credentialHash = requireNotNull(session.credentialHash) {
                    "Trakt mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "discovery:${ref.recommendationKey}",
                payload = payload,
                rollbackPayload = payload.deepCopy()
            )
        }

        private fun TraktMutationEnvelope.recommendationKey(): String {
            return payload.get(PAYLOAD_RECOMMENDATION_KEY)?.asString
                ?: error("Missing recommendation key payload")
        }

        private fun TraktMutationEnvelope.recommendationType(): String {
            return payload.get(PAYLOAD_RECOMMENDATION_TYPE)?.asString
                ?: error("Missing recommendation type payload")
        }

        private fun TraktMutationEnvelope.pathId(): String {
            return payload.get(PAYLOAD_PATH_ID)?.asString
                ?: error("Missing Trakt path id payload")
        }

    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktDiscoveryMutationAdapterModule {

    @Binds
    @IntoSet
    abstract fun bindTraktDiscoveryMutationAdapter(
        impl: TraktDiscoveryMutationAdapter
    ): TraktMutationAdapter
}

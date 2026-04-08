package com.nexio.tv.data.repository.trakt

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddNotFoundDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktSeasonMarkMutationAdapter @Inject constructor(
    private val traktProgressMutationExecutor: TraktProgressMutationExecutor
) : TraktMutationAdapter {

    private val notFoundByEnvelopeId = ConcurrentHashMap<String, Set<Int>>()

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val body = TraktHistoryAddRequestDto(
            episodes = envelope.episodes().map { episode ->
                TraktHistoryEpisodeAddDto(ids = TraktIdsDto(trakt = episode.traktId))
            }
        )
        val response = traktProgressMutationExecutor.addHistory(body)
            ?: return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")

        val notFound = response.body()
            ?.notFound
            ?.episodes
            ?.mapNotNull { it.ids?.trakt }
            ?.toSet()
            ?: emptySet()
        notFoundByEnvelopeId[envelope.id] = notFound

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to batch mark season watched (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) = Unit

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) = Unit

    fun consumeNotFound(envelopeId: String): Set<Int> {
        return notFoundByEnvelopeId.remove(envelopeId).orEmpty()
    }

    companion object {
        private const val PAYLOAD_EPISODES = "episodes"
        const val ADAPTER_KEY = "season-batch"
        const val MUTATION_KIND = "progress.history.batchAdd"

        fun buildEnvelope(
            showContentId: String,
            seasonNumber: Int,
            episodes: List<TraktEpisodeRef>
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                add(PAYLOAD_EPISODES, JsonArray().apply {
                    episodes.forEach { episode ->
                        add(JsonObject().apply {
                            addProperty("traktId", episode.traktId)
                        })
                    }
                })
            }
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "$showContentId:season:$seasonNumber",
                payload = payload
            )
        }

        private fun TraktMutationEnvelope.episodes(): List<TraktEpisodeRef> {
            return payload.getAsJsonArray(PAYLOAD_EPISODES)
                ?.mapNotNull { element ->
                    val traktId = element.asJsonObject.get("traktId")?.asInt ?: return@mapNotNull null
                    TraktEpisodeRef(traktId)
                }
                .orEmpty()
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktSeasonMarkMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindTraktSeasonMarkMutationAdapter(
        impl: TraktSeasonMarkMutationAdapter
    ): TraktMutationAdapter
}

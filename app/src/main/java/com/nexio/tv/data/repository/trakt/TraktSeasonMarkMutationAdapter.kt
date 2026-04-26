package com.nexio.tv.data.repository.trakt

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryEpisodeAddDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.TraktProgressService
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
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class TraktSeasonMarkMutationAdapter @Inject constructor(
    private val traktProgressMutationExecutor: TraktProgressMutationExecutor,
    private val snapshotServiceProvider: Provider<ContinueWatchingSnapshotService>,
    private val traktProgressService: TraktProgressService
) : TraktMutationAdapter {

    private val notFoundEpisodeNumbersByEnvelopeId = ConcurrentHashMap<String, Set<Int>>()

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
        envelope.episodes().forEach { episode ->
            traktProgressService.applyOptimisticProgress(
                episode.toCompletedProgress(contentId = envelope.showContentId(), seasonNumber = envelope.seasonNumber())
            )
        }
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val body = TraktHistoryAddRequestDto(
            episodes = envelope.episodes().map { episode ->
                TraktHistoryEpisodeAddDto(
                    number = episode.episodeNumber,
                    ids = TraktIdsDto(trakt = episode.traktId)
                )
            }
        )
        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val response = traktProgressMutationExecutor.addHistory(session, body)
            ?: return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")

        val notFoundEpisodeNumbers = response.body()
            ?.notFound
            ?.episodes
            .orEmpty()
            .mapNotNull { dto -> dto.ids?.trakt }
            .toSet()
            .let { notFoundIds ->
                if (notFoundIds.isEmpty()) {
                    emptySet()
                } else {
                    envelope.episodes()
                        .filter { it.traktId in notFoundIds }
                        .map { it.episodeNumber }
                        .toSet()
                }
            }
        notFoundEpisodeNumbersByEnvelopeId[envelope.id] = notFoundEpisodeNumbers

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

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        val notFoundEpisodeNumbers = notFoundEpisodeNumbersByEnvelopeId.remove(envelope.id).orEmpty()
        if (notFoundEpisodeNumbers.isNotEmpty()) {
            snapshotServiceProvider.get().rollbackEpisodes(
                envelope.rollbackState().filterEpisodeNumbers(notFoundEpisodeNumbers)
            )
        }
        snapshotServiceProvider.get().ensureFresh(force = true)
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        notFoundEpisodeNumbersByEnvelopeId.remove(envelope.id)
        snapshotServiceProvider.get().rollbackEpisodes(envelope.rollbackState())
        snapshotServiceProvider.get().ensureFresh(force = true)
    }

    companion object {
        private const val PAYLOAD_SHOW_CONTENT_ID = "showContentId"
        private const val PAYLOAD_SEASON_NUMBER = "seasonNumber"
        private const val PAYLOAD_EPISODES = "episodes"
        private const val PAYLOAD_EPISODE_NUMBER = "episodeNumber"
        private const val PAYLOAD_TRAKT_ID = "traktId"

        const val ADAPTER_KEY = "season-batch"
        const val MUTATION_KIND = "progress.history.batchAdd"

        private val gson = Gson()

        fun buildEnvelope(
            showContentId: String,
            seasonNumber: Int,
            episodes: List<TraktEpisodeRef>,
            rollbackState: ContinueWatchingSnapshotService.EpisodeRollbackState,
            profileId: Int = 1
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_SHOW_CONTENT_ID, showContentId)
                addProperty(PAYLOAD_SEASON_NUMBER, seasonNumber)
                add(PAYLOAD_EPISODES, JsonArray().apply {
                    episodes.forEach { episode ->
                        add(JsonObject().apply {
                            addProperty(PAYLOAD_EPISODE_NUMBER, episode.episodeNumber)
                            addProperty(PAYLOAD_TRAKT_ID, episode.traktId)
                        })
                    }
                })
            }
            return TraktMutationEnvelope(
                profileId = profileId,
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "$showContentId:season:$seasonNumber",
                payload = payload,
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        private fun TraktMutationEnvelope.episodes(): List<TraktEpisodeRef> {
            return payload.getAsJsonArray(PAYLOAD_EPISODES)
                ?.mapNotNull { element ->
                    val obj = element.asJsonObject
                    val episodeNumber = obj.get(PAYLOAD_EPISODE_NUMBER)?.asInt ?: return@mapNotNull null
                    val traktId = obj.get(PAYLOAD_TRAKT_ID)?.asInt ?: return@mapNotNull null
                    TraktEpisodeRef(
                        episodeNumber = episodeNumber,
                        traktId = traktId
                    )
                }
                .orEmpty()
        }

        private fun TraktMutationEnvelope.showContentId(): String {
            return payload.get(PAYLOAD_SHOW_CONTENT_ID)?.asString.orEmpty()
        }

        private fun TraktMutationEnvelope.seasonNumber(): Int {
            return payload.get(PAYLOAD_SEASON_NUMBER)?.asInt ?: 0
        }

        private fun TraktMutationEnvelope.rollbackState(): ContinueWatchingSnapshotService.EpisodeRollbackState {
            return gson.fromJson(
                rollbackPayload ?: JsonObject(),
                ContinueWatchingSnapshotService.EpisodeRollbackState::class.java
            ) ?: ContinueWatchingSnapshotService.EpisodeRollbackState()
        }

        private fun ContinueWatchingSnapshotService.EpisodeRollbackState.filterEpisodeNumbers(
            episodeNumbers: Set<Int>
        ): ContinueWatchingSnapshotService.EpisodeRollbackState {
            if (episodeNumbers.isEmpty()) return ContinueWatchingSnapshotService.EpisodeRollbackState()
            return ContinueWatchingSnapshotService.EpisodeRollbackState(
                resumeItems = resumeItems.filter { it.episode in episodeNumbers },
                nextUpItems = nextUpItems.filter { it.episode in episodeNumbers },
                traktUpNextItems = traktUpNextItems.filter { it.episode in episodeNumbers }
            )
        }

        private fun TraktEpisodeRef.toCompletedProgress(
            contentId: String,
            seasonNumber: Int
        ): com.nexio.tv.domain.model.WatchProgress {
            return com.nexio.tv.domain.model.WatchProgress(
                contentId = contentId,
                contentType = "series",
                name = contentId,
                poster = null,
                backdrop = null,
                logo = null,
                videoId = "$contentId:$seasonNumber:$episodeNumber",
                season = seasonNumber,
                episode = episodeNumber,
                episodeTitle = null,
                position = 1L,
                duration = 1L,
                lastWatched = 0L,
                progressPercent = 100f
            )
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

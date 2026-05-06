package com.nexio.tv.data.repository.simkl

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryEpisodePayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistorySeasonPayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryShowPayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TrackingProgressService
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
import javax.inject.Provider
import javax.inject.Singleton

@Singleton
class SimklSeasonMarkMutationAdapter @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
    private val snapshotServiceProvider: Provider<ContinueWatchingSnapshotService>,
    private val trackingProgressService: TrackingProgressService
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
        envelope.episodeNumbers().forEach { episodeNumber ->
            trackingProgressService.applyOptimisticProgress(
                toCompletedProgress(
                    contentId = envelope.showContentId(),
                    seasonNumber = envelope.seasonNumber(),
                    episodeNumber = episodeNumber
                )
            )
        }
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = remote.addHistory(envelope.requestBody(), envelope.session())
        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to batch mark season watched on SIMKL (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        snapshotServiceProvider.get().ensureFresh(force = true)
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        snapshotServiceProvider.get().rollbackEpisodes(envelope.rollbackState())
        snapshotServiceProvider.get().ensureFresh(force = true)
    }

    companion object {
        private const val PAYLOAD_SHOW_CONTENT_ID = "showContentId"
        private const val PAYLOAD_SHOW_TITLE = "showTitle"
        private const val PAYLOAD_SHOW_YEAR = "showYear"
        private const val PAYLOAD_IS_ANIME = "isAnime"
        private const val PAYLOAD_SEASON_NUMBER = "seasonNumber"
        private const val PAYLOAD_EPISODES = "episodes"
        private const val PAYLOAD_EPISODE_NUMBER = "episodeNumber"

        const val ADAPTER_KEY = "simkl.season-batch"
        const val MUTATION_KIND = "simkl.progress.history.batchAdd"

        private val gson = Gson()

        fun buildEnvelope(
            showContentId: String,
            showTitle: String,
            showYear: Int?,
            isAnime: Boolean,
            seasonNumber: Int,
            episodeNumbers: List<Int>,
            rollbackState: ContinueWatchingSnapshotService.EpisodeRollbackState,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_SHOW_CONTENT_ID, showContentId)
                addProperty(PAYLOAD_SHOW_TITLE, showTitle)
                showYear?.let { addProperty(PAYLOAD_SHOW_YEAR, it) }
                addProperty(PAYLOAD_IS_ANIME, isAnime)
                addProperty(PAYLOAD_SEASON_NUMBER, seasonNumber)
                add(PAYLOAD_EPISODES, JsonArray().apply {
                    episodeNumbers.forEach { ep ->
                        add(JsonObject().apply {
                            addProperty(PAYLOAD_EPISODE_NUMBER, ep)
                        })
                    }
                })
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "$showContentId:season:$seasonNumber",
                payload = payload,
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        private fun TraktMutationEnvelope.requestBody(): SimklHistoryAddRequestDto {
            val showPayload = SimklHistoryShowPayloadDto(
                title = payload.get(PAYLOAD_SHOW_TITLE)?.asString,
                year = payload.get(PAYLOAD_SHOW_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                status = "completed",
                ids = parseIds(showContentId()),
                seasons = listOf(
                    SimklHistorySeasonPayloadDto(
                        number = seasonNumber(),
                        episodes = episodeNumbers().map { num ->
                            SimklHistoryEpisodePayloadDto(number = num)
                        }
                    )
                )
            )
            return if (payload.get(PAYLOAD_IS_ANIME)?.asBoolean == true) {
                SimklHistoryAddRequestDto(anime = listOf(showPayload))
            } else {
                SimklHistoryAddRequestDto(shows = listOf(showPayload))
            }
        }

        private fun TraktMutationEnvelope.showContentId(): String {
            return payload.get(PAYLOAD_SHOW_CONTENT_ID)?.asString.orEmpty()
        }

        private fun TraktMutationEnvelope.seasonNumber(): Int {
            return payload.get(PAYLOAD_SEASON_NUMBER)?.asInt ?: 1
        }

        private fun TraktMutationEnvelope.episodeNumbers(): List<Int> {
            return payload.getAsJsonArray(PAYLOAD_EPISODES)
                ?.mapNotNull { element ->
                    element.asJsonObject.get(PAYLOAD_EPISODE_NUMBER)?.asInt
                }
                .orEmpty()
        }

        private fun parseIds(contentId: String): SimklIdsDto {
            val trimmed = contentId.trim()
            return when {
                trimmed.startsWith("tt", ignoreCase = true) -> SimklIdsDto(imdb = trimmed.substringBefore(':'))
                trimmed.startsWith("tmdb:", ignoreCase = true) -> SimklIdsDto(tmdb = trimmed.substringAfter(':'))
                trimmed.startsWith("simkl:", ignoreCase = true) -> SimklIdsDto(simkl = trimmed.substringAfter(':').toLongOrNull())
                else -> SimklIdsDto()
            }
        }

        private fun TraktMutationEnvelope.rollbackState(): ContinueWatchingSnapshotService.EpisodeRollbackState {
            return gson.fromJson(
                rollbackPayload ?: JsonObject(),
                ContinueWatchingSnapshotService.EpisodeRollbackState::class.java
            ) ?: ContinueWatchingSnapshotService.EpisodeRollbackState()
        }

        private fun TraktMutationEnvelope.session(): TrackingAuthSession {
            return TrackingAuthSession(
                provider = provider,
                profileId = profileId,
                credentialHash = credentialHash
            )
        }

        private fun toCompletedProgress(
            contentId: String,
            seasonNumber: Int,
            episodeNumber: Int
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
abstract class SimklSeasonMarkMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindSimklSeasonMarkMutationAdapter(
        impl: SimklSeasonMarkMutationAdapter
    ): TraktMutationAdapter
}

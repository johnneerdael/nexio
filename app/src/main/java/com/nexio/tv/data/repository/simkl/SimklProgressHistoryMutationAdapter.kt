package com.nexio.tv.data.repository.simkl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryEpisodePayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistorySeasonPayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryShowPayloadDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.domain.model.WatchProgress
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklProgressHistoryMutationAdapter @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
    private val simklProgressService: SimklProgressService
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
        if (envelope.mutationKind == MUTATION_KIND_HISTORY_ADD) {
            simklProgressService.applyOptimisticProgress(envelope.progress())
        }
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> remote.addHistory(envelope.historyAddBody(), envelope.session())
            MUTATION_KIND_HISTORY_REMOVE -> remote.removeFromHistoryAndLists(envelope.historyRemoveBody(), envelope.session())
            MUTATION_KIND_PLAYBACK_DELETE -> remote.deletePlayback(envelope.playbackId(), envelope.session())
            else -> null
        } ?: return TraktMutationExecutionResult.Failure(httpStatusCode = 400, reason = "Unsupported SIMKL progress mutation ${envelope.mutationKind}")

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "SIMKL progress mutation failed (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        simklProgressService.refreshNow()
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD,
            MUTATION_KIND_HISTORY_REMOVE -> simklProgressService.rollbackQueuedHistoryRemove(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                removeShow = envelope.removeShow()
            )
            MUTATION_KIND_PLAYBACK_DELETE -> simklProgressService.rollbackQueuedPlaybackDelete(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                clearShow = envelope.clearShow()
            )
        }
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
        private const val PAYLOAD_CONTENT_ID = "contentId"
        private const val PAYLOAD_SEASON = "season"
        private const val PAYLOAD_EPISODE = "episode"
        private const val PAYLOAD_PLAYBACK_ID = "playbackId"
        private const val PAYLOAD_REMOVE_SHOW = "removeShow"
        private const val PAYLOAD_CLEAR_SHOW = "clearShow"
        private const val METADATA_TITLE = "title"
        private const val METADATA_YEAR = "year"

        const val ADAPTER_KEY = "simkl.progress-history"
        const val MUTATION_KIND_HISTORY_ADD = "simkl.progress.history.add"
        const val MUTATION_KIND_HISTORY_REMOVE = "simkl.progress.history.remove"
        const val MUTATION_KIND_PLAYBACK_DELETE = "simkl.progress.playback.delete"

        private val gson = Gson()

        fun buildHistoryAddEnvelope(
            progress: WatchProgress,
            title: String?,
            year: Int?,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                add(PAYLOAD_PROGRESS, gson.toJsonTree(progress))
            }
            val metadata = JsonObject().apply {
                title?.let { addProperty(METADATA_TITLE, it) }
                year?.let { addProperty(METADATA_YEAR, it) }
            }
            val collapseKey = buildCollapseKey(progress.contentId, progress.season, progress.episode)
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_HISTORY_ADD,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = collapseKey,
                payload = payload,
                metadata = metadata
            )
        }

        fun buildHistoryRemoveEnvelope(
            contentId: String,
            season: Int?,
            episode: Int?,
            removeShow: Boolean = false,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_CONTENT_ID, contentId)
                season?.let { addProperty(PAYLOAD_SEASON, it) }
                episode?.let { addProperty(PAYLOAD_EPISODE, it) }
                if (removeShow) addProperty(PAYLOAD_REMOVE_SHOW, true)
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_HISTORY_REMOVE,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = buildCollapseKey(contentId, season, episode, removeShow),
                payload = payload
            )
        }

        fun buildPlaybackDeleteEnvelope(
            playbackId: Long,
            contentId: String,
            season: Int?,
            episode: Int?,
            clearShow: Boolean = false,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_PLAYBACK_ID, playbackId)
                addProperty(PAYLOAD_CONTENT_ID, contentId)
                season?.let { addProperty(PAYLOAD_SEASON, it) }
                episode?.let { addProperty(PAYLOAD_EPISODE, it) }
                if (clearShow) addProperty(PAYLOAD_CLEAR_SHOW, true)
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_PLAYBACK_DELETE,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "simkl.playback:$playbackId",
                payload = payload
            )
        }

        private fun buildCollapseKey(contentId: String, season: Int?, episode: Int?, removeShow: Boolean = false): String =
            buildString {
                append(contentId.trim())
                if (removeShow) {
                    append(":show")
                } else {
                    season?.let { append(":s$it") }
                    episode?.let { append(":e$it") }
                }
            }.ifBlank { contentId }

        private fun TraktMutationEnvelope.progress(): WatchProgress =
            gson.fromJson(payload.get(PAYLOAD_PROGRESS), WatchProgress::class.java)

        private fun TraktMutationEnvelope.contentId(): String =
            payload.get(PAYLOAD_CONTENT_ID)?.asString ?: progress().contentId

        private fun TraktMutationEnvelope.season(): Int? =
            payload.get(PAYLOAD_SEASON)?.takeIf { !it.isJsonNull }?.asInt ?: progress().season

        private fun TraktMutationEnvelope.episode(): Int? =
            payload.get(PAYLOAD_EPISODE)?.takeIf { !it.isJsonNull }?.asInt ?: progress().episode

        private fun TraktMutationEnvelope.playbackId(): Long =
            payload.get(PAYLOAD_PLAYBACK_ID)?.asLong ?: error("Missing playbackId")

        private fun TraktMutationEnvelope.removeShow(): Boolean =
            payload.get(PAYLOAD_REMOVE_SHOW)?.asBoolean ?: false

        private fun TraktMutationEnvelope.clearShow(): Boolean =
            payload.get(PAYLOAD_CLEAR_SHOW)?.asBoolean ?: false

        private fun TraktMutationEnvelope.session(): TrackingAuthSession =
            TrackingAuthSession(
                provider = provider,
                profileId = profileId,
                credentialHash = credentialHash
            )

        private fun TraktMutationEnvelope.historyAddBody(): SimklHistoryAddRequestDto {
            val progress = progress()
            val mediaRef = progress.toSimklMediaRef(title(), year())
            return if (progress.season != null && progress.episode != null) {
                val showPayload = SimklHistoryShowPayloadDto(
                    title = mediaRef.title,
                    year = mediaRef.year,
                    ids = mediaRef.ids,
                    watchedAt = isoFromMillis(progress.lastWatched),
                    seasons = listOf(
                        SimklHistorySeasonPayloadDto(
                            number = progress.season,
                            episodes = listOf(
                                SimklHistoryEpisodePayloadDto(
                                    number = progress.episode,
                                    watchedAt = isoFromMillis(progress.lastWatched)
                                )
                            )
                        )
                    )
                )
                SimklHistoryAddRequestDto(shows = listOf(showPayload))
            } else {
                SimklHistoryAddRequestDto(
                    movies = listOf(mediaRef.copy())
                )
            }
        }

        private fun TraktMutationEnvelope.historyRemoveBody(): SimklHistoryRemoveRequestDto {
            val contentId = contentId()
            val season = season()
            val episode = episode()
            val removeShow = removeShow()
            val parsed = parseIds(contentId)
            return if (season != null && episode != null) {
                SimklHistoryRemoveRequestDto(
                    shows = listOf(
                        SimklHistoryShowPayloadDto(
                            ids = parsed,
                            seasons = listOf(
                                SimklHistorySeasonPayloadDto(
                                    number = season,
                                    episodes = listOf(SimklHistoryEpisodePayloadDto(number = episode))
                                )
                            )
                        )
                    )
                )
            } else if (removeShow) {
                SimklHistoryRemoveRequestDto(
                    shows = listOf(
                        SimklHistoryShowPayloadDto(ids = parsed)
                    )
                )
            } else {
                SimklHistoryRemoveRequestDto(
                    movies = listOf(
                        SimklMediaRefDto(ids = parsed)
                    )
                )
            }
        }

        private fun WatchProgress.toSimklMediaRef(title: String?, year: Int?): SimklMediaRefDto {
            return SimklMediaRefDto(
                title = title ?: name.takeIf { it.isNotBlank() },
                year = year,
                ids = parseIds(contentId)
            )
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

        private fun TraktMutationEnvelope.title(): String? =
            metadata.get(METADATA_TITLE)?.asString

        private fun TraktMutationEnvelope.year(): Int? =
            metadata.get(METADATA_YEAR)?.takeIf { !it.isJsonNull }?.asInt

        private fun isoFromMillis(millis: Long): String =
            java.time.Instant.ofEpochMilli(millis).toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SimklProgressHistoryMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindSimklProgressHistoryMutationAdapter(
        impl: SimklProgressHistoryMutationAdapter
    ): TraktMutationAdapter
}

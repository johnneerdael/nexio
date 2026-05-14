package com.nexio.tv.data.repository.mdblist

import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleClearRequestDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleEpisodeDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleMovieDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleSeasonDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListScrobbleShowDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListSyncIdsDto
import com.nexio.tv.data.remote.dto.mdblist.MDBListWatchedSyncRequestDto
import com.nexio.tv.data.repository.MDBListProgressService
import com.nexio.tv.data.repository.MDBListSettingsReader
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
import kotlinx.coroutines.flow.first

@Singleton
class MDBListProgressMutationAdapter @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
    private val progressService: MDBListProgressService
) : TraktMutationAdapter {
    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val apiKey = currentApiKey()
        val response = when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> api.addWatched(apiKey, envelope.watchedBody())
            MUTATION_KIND_SEASON_HISTORY_ADD -> api.addWatched(apiKey, envelope.watchedBody())
            MUTATION_KIND_HISTORY_REMOVE -> api.removeWatched(apiKey, envelope.watchedBody())
            MUTATION_KIND_PLAYBACK_CLEAR -> api.clearScrobble(apiKey, envelope.clearScrobbleBody())
            else -> null
        } ?: return TraktMutationExecutionResult.Failure(
            httpStatusCode = 400,
            reason = "Unsupported MDBList progress mutation ${envelope.mutationKind}"
        )

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "MDBList progress mutation failed (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        progressService.refreshNowImmediate()
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) = Unit

    private suspend fun currentApiKey(): String {
        return settingsReader.settings.first().apiKey.trim()
    }

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
        private const val PAYLOAD_CONTENT_ID = "contentId"
        private const val PAYLOAD_SEASON = "season"
        private const val PAYLOAD_EPISODE = "episode"
        private const val PAYLOAD_EPISODES = "episodes"
        private const val PAYLOAD_EPISODE_NUMBER = "episodeNumber"
        private const val PAYLOAD_REMOVE_SHOW = "removeShow"
        private const val PAYLOAD_CLEAR_SHOW = "clearShow"
        private const val METADATA_TITLE = "title"
        private const val METADATA_YEAR = "year"

        const val ADAPTER_KEY = "mdblist.progress-history"
        const val MUTATION_KIND_HISTORY_ADD = "mdblist.progress.history.add"
        const val MUTATION_KIND_HISTORY_REMOVE = "mdblist.progress.history.remove"
        const val MUTATION_KIND_PLAYBACK_CLEAR = "mdblist.progress.playback.clear"
        const val MUTATION_KIND_SEASON_HISTORY_ADD = "mdblist.progress.history.batchAdd"

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
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.MDBLIST,
                credentialHash = requireNotNull(session.credentialHash) {
                    "MDBList mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_HISTORY_ADD,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = buildCollapseKey(progress.contentId, progress.season, progress.episode),
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
                provider = TrackingProvider.MDBLIST,
                credentialHash = requireNotNull(session.credentialHash) {
                    "MDBList mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_HISTORY_REMOVE,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = buildCollapseKey(contentId, season, episode, removeShow),
                payload = payload
            )
        }

        fun buildSeasonHistoryAddEnvelope(
            showContentId: String,
            showTitle: String?,
            showYear: Int?,
            seasonNumber: Int,
            episodeNumbers: List<Int>,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_CONTENT_ID, showContentId)
                addProperty(PAYLOAD_SEASON, seasonNumber)
                add(PAYLOAD_EPISODES, JsonArray().apply {
                    for (i in episodeNumbers.indices) {
                        add(JsonObject().apply {
                            addProperty(PAYLOAD_EPISODE_NUMBER, episodeNumbers[i])
                        })
                    }
                })
            }
            val metadata = JsonObject().apply {
                showTitle?.let { addProperty(METADATA_TITLE, it) }
                showYear?.let { addProperty(METADATA_YEAR, it) }
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.MDBLIST,
                credentialHash = requireNotNull(session.credentialHash) {
                    "MDBList mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_SEASON_HISTORY_ADD,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = buildCollapseKey(showContentId, seasonNumber, null, showLevel = true),
                payload = payload,
                metadata = metadata
            )
        }

        fun buildPlaybackClearEnvelope(
            contentId: String,
            season: Int?,
            episode: Int?,
            clearShow: Boolean = false,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_CONTENT_ID, contentId)
                season?.let { addProperty(PAYLOAD_SEASON, it) }
                episode?.let { addProperty(PAYLOAD_EPISODE, it) }
                if (clearShow) addProperty(PAYLOAD_CLEAR_SHOW, true)
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.MDBLIST,
                credentialHash = requireNotNull(session.credentialHash) {
                    "MDBList mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_PLAYBACK_CLEAR,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = buildCollapseKey("playback:${contentId.trim()}", season, episode, clearShow),
                payload = payload
            )
        }

        private fun TraktMutationEnvelope.watchedBody(): MDBListWatchedSyncRequestDto {
            val progress = payload.get(PAYLOAD_PROGRESS)?.let {
                gson.fromJson(it, WatchProgress::class.java)
            }
            val contentId = contentId(progress)
            val season = season(progress)
            val episode = episode(progress)
            val ids = syncIds(contentId)
            val episodeNumbers = episodeNumbers()
            return if (season != null && episodeNumbers.isNotEmpty()) {
                MDBListWatchedSyncRequestDto(
                    shows = listOf(
                        MDBListWatchedSyncRequestDto.Show(
                            title = title() ?: progress?.name?.takeIf { it.isNotBlank() },
                            year = year(),
                            ids = ids,
                            seasons = listOf(
                                MDBListWatchedSyncRequestDto.Season(
                                    number = season,
                                    episodes = episodeNumbers.map { episodeNumber ->
                                        MDBListWatchedSyncRequestDto.Episode(number = episodeNumber)
                                    }
                                )
                            )
                        )
                    )
                )
            } else if (season != null && episode != null) {
                MDBListWatchedSyncRequestDto(
                    shows = listOf(
                        MDBListWatchedSyncRequestDto.Show(
                            title = title() ?: progress?.name?.takeIf { it.isNotBlank() },
                            year = year(),
                            ids = ids,
                            seasons = listOf(
                                MDBListWatchedSyncRequestDto.Season(
                                    number = season,
                                    episodes = listOf(
                                        MDBListWatchedSyncRequestDto.Episode(
                                            number = episode,
                                            watchedAt = progress?.lastWatched?.let(::isoFromMillis)
                                        )
                                    )
                                )
                            ),
                            watchedAt = progress?.lastWatched?.let(::isoFromMillis)
                        )
                    )
                )
            } else {
                MDBListWatchedSyncRequestDto(
                    movies = listOf(
                        MDBListWatchedSyncRequestDto.Movie(
                            title = title() ?: progress?.name?.takeIf { it.isNotBlank() },
                            year = year(),
                            ids = ids,
                            watchedAt = progress?.lastWatched?.let(::isoFromMillis)
                        )
                    )
                )
            }
        }

        private fun TraktMutationEnvelope.clearScrobbleBody(): MDBListScrobbleClearRequestDto {
            val ids = scrobbleIds(contentId(null))
            val season = season(null)
            val episode = episode(null)
            return if (season != null && episode != null) {
                MDBListScrobbleClearRequestDto(
                    show = MDBListScrobbleShowDto(
                        ids = ids,
                        season = MDBListScrobbleSeasonDto(
                            number = season,
                            episode = MDBListScrobbleEpisodeDto(number = episode)
                        )
                    )
                )
            } else {
                MDBListScrobbleClearRequestDto(
                    movie = MDBListScrobbleMovieDto(ids = ids)
                )
            }
        }

        private fun TraktMutationEnvelope.contentId(progress: WatchProgress?): String =
            payload.get(PAYLOAD_CONTENT_ID)?.asString ?: progress?.contentId ?: error("Missing contentId")

        private fun TraktMutationEnvelope.season(progress: WatchProgress?): Int? =
            payload.get(PAYLOAD_SEASON)?.takeIf { !it.isJsonNull }?.asInt ?: progress?.season

        private fun TraktMutationEnvelope.episode(progress: WatchProgress?): Int? =
            payload.get(PAYLOAD_EPISODE)?.takeIf { !it.isJsonNull }?.asInt ?: progress?.episode

        private fun TraktMutationEnvelope.episodeNumbers(): List<Int> =
            payload.getAsJsonArray(PAYLOAD_EPISODES)
                ?.mapNotNull { element ->
                    element.asJsonObject.get(PAYLOAD_EPISODE_NUMBER)?.asInt
                }
                .orEmpty()

        private fun TraktMutationEnvelope.title(): String? =
            metadata.get(METADATA_TITLE)?.asString

        private fun TraktMutationEnvelope.year(): Int? =
            metadata.get(METADATA_YEAR)?.takeIf { !it.isJsonNull }?.asInt

        private fun buildCollapseKey(contentId: String, season: Int?, episode: Int?, showLevel: Boolean = false): String =
            buildString {
                append(contentId.trim())
                if (showLevel) {
                    append(":show")
                } else {
                    season?.let { append(":s$it") }
                    episode?.let { append(":e$it") }
                }
            }.ifBlank { contentId }

        private fun syncIds(contentId: String): MDBListSyncIdsDto {
            val trimmed = contentId.trim()
            return when {
                trimmed.startsWith("tt", ignoreCase = true) -> MDBListSyncIdsDto(imdb = trimmed.substringBefore(':'))
                trimmed.startsWith("tmdb:", ignoreCase = true) -> MDBListSyncIdsDto(tmdb = trimmed.substringAfter(':').toIntOrNull())
                trimmed.startsWith("tvdb:", ignoreCase = true) -> MDBListSyncIdsDto(tvdb = trimmed.substringAfter(':').toIntOrNull())
                trimmed.startsWith("trakt:", ignoreCase = true) -> MDBListSyncIdsDto(trakt = trimmed.substringAfter(':').toIntOrNull())
                trimmed.startsWith("kitsu:", ignoreCase = true) -> MDBListSyncIdsDto(kitsu = trimmed.substringAfter(':').toIntOrNull())
                trimmed.startsWith("mdblist:", ignoreCase = true) -> MDBListSyncIdsDto(mdblist = trimmed.substringAfter(':'))
                else -> MDBListSyncIdsDto()
            }
        }

        private fun scrobbleIds(contentId: String): MDBListScrobbleIdsDto {
            val trimmed = contentId.trim()
            return when {
                trimmed.startsWith("tt", ignoreCase = true) -> MDBListScrobbleIdsDto(imdb = trimmed.substringBefore(':'))
                trimmed.startsWith("tmdb:", ignoreCase = true) -> MDBListScrobbleIdsDto(tmdb = trimmed.substringAfter(':').toIntOrNull())
                trimmed.startsWith("tvdb:", ignoreCase = true) -> MDBListScrobbleIdsDto(tvdb = trimmed.substringAfter(':').toIntOrNull())
                else -> MDBListScrobbleIdsDto()
            }
        }

        private fun isoFromMillis(millis: Long): String =
            java.time.Instant.ofEpochMilli(millis).toString()
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class MDBListProgressMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindMDBListProgressMutationAdapter(
        impl: MDBListProgressMutationAdapter
    ): TraktMutationAdapter
}

package com.nexio.tv.data.repository.simkl

import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.simkl.SimklEpisodeDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.domain.model.ProviderIds
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
class SimklScrobbleMutationAdapter @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
    private val simklProgressService: SimklProgressService,
    private val watchingNowStateController: TraktWatchingNowStateController
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val session = TrackingAuthSession(
            provider = envelope.provider,
            profileId = envelope.profileId,
            credentialHash = envelope.credentialHash
        )
        val response = when (envelope.mutationKind) {
            MUTATION_KIND_CHECKIN -> remote.checkin(envelope.buildRequestBody(), session)
            MUTATION_KIND_SCROBBLE -> when (envelope.scrobbleAction()) {
                "start" -> remote.scrobbleStart(envelope.buildRequestBody(), session)
                "pause" -> remote.scrobblePause(envelope.buildRequestBody(), session)
                else -> remote.scrobbleStop(envelope.buildRequestBody(), session)
            }
            else -> null
        } ?: return TraktMutationExecutionResult.Failure(httpStatusCode = 400, reason = "Unsupported SIMKL scrobble mutation ${envelope.mutationKind}")

        return if (response.isSuccessful || response.code() == 409) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "SIMKL scrobble failed (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        // Only refresh after stop/checkin - heartbeat starts and pauses don't change
        // watch history or playback state on SIMKL, so there is nothing to reconcile.
        if (envelope.mutationKind == MUTATION_KIND_CHECKIN || envelope.scrobbleAction() == "stop") {
            simklProgressService.refreshNow()
        }
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        watchingNowStateController.rollbackIfCurrent(
            expectedVersion = envelope.optimisticVersion(),
            rollbackState = envelope.rollbackState()
        )
    }

    companion object {
        private const val PAYLOAD_ITEM_TYPE = "itemType"
        private const val PAYLOAD_PARENT_KIND = "parentKind"
        private const val PAYLOAD_TITLE = "title"
        private const val PAYLOAD_YEAR = "year"
        private const val PAYLOAD_IMDB = "imdb"
        private const val PAYLOAD_TMDB = "tmdb"
        private const val PAYLOAD_TVDB = "tvdb"
        private const val PAYLOAD_SIMKL = "simkl"
        private const val PAYLOAD_MAL = "mal"
        private const val PAYLOAD_ANILIST = "anilist"
        private const val PAYLOAD_KITSU = "kitsu"
        private const val PAYLOAD_ANIDB = "anidb"
        private const val PAYLOAD_SHOW_TITLE = "showTitle"
        private const val PAYLOAD_SHOW_YEAR = "showYear"
        private const val PAYLOAD_SHOW_IMDB = "showImdb"
        private const val PAYLOAD_SHOW_TMDB = "showTmdb"
        private const val PAYLOAD_SHOW_TVDB = "showTvdb"
        private const val PAYLOAD_SHOW_SIMKL = "showSimkl"
        private const val PAYLOAD_SHOW_MAL = "showMal"
        private const val PAYLOAD_SHOW_ANILIST = "showAnilist"
        private const val PAYLOAD_SHOW_KITSU = "showKitsu"
        private const val PAYLOAD_SHOW_ANIDB = "showAnidb"
        private const val PAYLOAD_SEASON = "season"
        private const val PAYLOAD_NUMBER = "number"
        private const val PAYLOAD_EPISODE_TITLE = "episodeTitle"
        private const val PAYLOAD_ACTION = "action"
        private const val PAYLOAD_PROGRESS = "progress"
        private const val PAYLOAD_MESSAGE = "message"

        private const val PARENT_KIND_MOVIE = "movie"
        private const val PARENT_KIND_SHOW = "show"
        private const val PARENT_KIND_ANIME = "anime"

        private const val METADATA_ROLLBACK_ACTIVE = "rollbackActive"
        private const val METADATA_ROLLBACK_TITLE = "rollbackTitle"
        private const val METADATA_ROLLBACK_CONTENT_TYPE = "rollbackContentType"
        private const val METADATA_ROLLBACK_PROGRESS = "rollbackProgress"
        private const val METADATA_OPTIMISTIC_VERSION = "optimisticVersion"

        const val ADAPTER_KEY = "simkl.scrobble"
        const val MUTATION_KIND_SCROBBLE = "simkl.scrobble.state"
        const val MUTATION_KIND_CHECKIN = "simkl.scrobble.checkin"

        fun buildScrobbleEnvelope(
            item: TrackingScrobbleItem,
            action: String,
            progressPercent: Float,
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                addProperty(PAYLOAD_ACTION, action)
                addProperty(PAYLOAD_PROGRESS, progressPercent.coerceIn(0f, 100f))
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_SCROBBLE,
                priority = TraktMutationPriorityBucket.SCROBBLE,
                collapseKey = "simkl.scrobble:${item.itemKey()}",
                payload = payload,
                metadata = buildRollbackMetadata(rollbackState, optimisticVersion)
            )
        }

        fun buildCheckinEnvelope(
            item: TrackingScrobbleItem,
            message: String?,
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long,
            session: TrackingAuthSession
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                message?.let { addProperty(PAYLOAD_MESSAGE, it) }
            }
            return TraktMutationEnvelope(
                profileId = session.profileId,
                provider = TrackingProvider.SIMKL,
                credentialHash = requireNotNull(session.credentialHash) {
                    "SIMKL mutation envelopes require account-scoped credentialHash"
                },
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_CHECKIN,
                priority = TraktMutationPriorityBucket.SCROBBLE,
                collapseKey = "simkl.scrobble:${item.itemKey()}",
                payload = payload,
                metadata = buildRollbackMetadata(rollbackState, optimisticVersion)
            )
        }

        private fun JsonObject.populateItem(item: TrackingScrobbleItem) {
            val isAnime = item.hasAnimeIds()
            when (item) {
                is TrackingScrobbleItem.Movie -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "movie")
                    addProperty(PAYLOAD_PARENT_KIND, if (isAnime) PARENT_KIND_ANIME else PARENT_KIND_MOVIE)
                    item.title?.let { addProperty(PAYLOAD_TITLE, it) }
                    item.year?.let { addProperty(PAYLOAD_YEAR, it) }
                    item.hydratedIds?.let { ids ->
                        ids.imdb?.let { addProperty(PAYLOAD_IMDB, it) }
                        ids.tmdb?.let { addProperty(PAYLOAD_TMDB, it) }
                        ids.tvdb?.let { addProperty(PAYLOAD_TVDB, it) }
                        ids.simkl?.toLongOrNull()?.let { addProperty(PAYLOAD_SIMKL, it) }
                        if (isAnime) {
                            ids.mal?.let { addProperty(PAYLOAD_MAL, it) }
                            ids.anilist?.let { addProperty(PAYLOAD_ANILIST, it) }
                            ids.kitsu?.let { addProperty(PAYLOAD_KITSU, it) }
                            ids.anidb?.let { addProperty(PAYLOAD_ANIDB, it) }
                        }
                    } ?: parseSimklIds(item.contentId).let { ids ->
                        ids.imdb?.let { addProperty(PAYLOAD_IMDB, it) }
                        ids.tmdb?.let { addProperty(PAYLOAD_TMDB, it) }
                        ids.simkl?.let { addProperty(PAYLOAD_SIMKL, it) }
                    }
                }
                is TrackingScrobbleItem.Episode -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "episode")
                    addProperty(PAYLOAD_PARENT_KIND, if (isAnime) PARENT_KIND_ANIME else PARENT_KIND_SHOW)
                    item.showTitle?.let { addProperty(PAYLOAD_SHOW_TITLE, it) }
                    item.showYear?.let { addProperty(PAYLOAD_SHOW_YEAR, it) }
                    item.hydratedIds?.let { ids ->
                        ids.imdb?.let { addProperty(PAYLOAD_SHOW_IMDB, it) }
                        ids.tmdb?.let { addProperty(PAYLOAD_SHOW_TMDB, it) }
                        ids.tvdb?.let { addProperty(PAYLOAD_SHOW_TVDB, it) }
                        ids.simkl?.toLongOrNull()?.let { addProperty(PAYLOAD_SHOW_SIMKL, it) }
                        if (isAnime) {
                            ids.mal?.let { addProperty(PAYLOAD_SHOW_MAL, it) }
                            ids.anilist?.let { addProperty(PAYLOAD_SHOW_ANILIST, it) }
                            ids.kitsu?.let { addProperty(PAYLOAD_SHOW_KITSU, it) }
                            ids.anidb?.let { addProperty(PAYLOAD_SHOW_ANIDB, it) }
                        }
                    } ?: parseSimklIds(item.contentId).let { ids ->
                        ids.imdb?.let { addProperty(PAYLOAD_SHOW_IMDB, it) }
                        ids.tmdb?.let { addProperty(PAYLOAD_SHOW_TMDB, it) }
                        ids.simkl?.let { addProperty(PAYLOAD_SHOW_SIMKL, it) }
                    }
                    addProperty(PAYLOAD_SEASON, item.season)
                    addProperty(PAYLOAD_NUMBER, item.number)
                    item.episodeTitle?.let { addProperty(PAYLOAD_EPISODE_TITLE, it) }
                }
            }
        }

        // Anime when any anime-native sidecar id is present on the hydrated bundle.
        private fun TrackingScrobbleItem.hasAnimeIds(): Boolean {
            val ids = hydratedIds ?: return false
            return ids.mal != null || ids.anilist != null || ids.kitsu != null || ids.anidb != null
        }

        private fun ProviderIds.toSimklIds(): ParsedSimklIds = ParsedSimklIds(
            simkl = simkl?.toLongOrNull(),
            imdb = imdb,
            tmdb = tmdb,
        )

        private fun buildRollbackMetadata(
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long
        ) = JsonObject().apply {
            addProperty(METADATA_ROLLBACK_ACTIVE, rollbackState.active)
            rollbackState.title?.let { addProperty(METADATA_ROLLBACK_TITLE, it) }
            rollbackState.contentType?.let { addProperty(METADATA_ROLLBACK_CONTENT_TYPE, it) }
            rollbackState.progressPercent?.let { addProperty(METADATA_ROLLBACK_PROGRESS, it) }
            addProperty(METADATA_OPTIMISTIC_VERSION, optimisticVersion)
        }

        private fun TrackingScrobbleItem.itemKey(): String = when (this) {
            is TrackingScrobbleItem.Movie -> "movie:$contentId:${year ?: 0}"
            is TrackingScrobbleItem.Episode -> "episode:$contentId:$season:$number"
        }

        private fun TraktMutationEnvelope.optimisticVersion(): Long =
            metadata.get(METADATA_OPTIMISTIC_VERSION)?.asLong ?: 0L

        private fun TraktMutationEnvelope.rollbackState(): TraktWatchingNowStateController.Snapshot =
            TraktWatchingNowStateController.Snapshot(
                active = metadata.get(METADATA_ROLLBACK_ACTIVE)?.asBoolean ?: false,
                title = metadata.get(METADATA_ROLLBACK_TITLE)?.asString,
                contentType = metadata.get(METADATA_ROLLBACK_CONTENT_TYPE)?.asString,
                progressPercent = metadata.get(METADATA_ROLLBACK_PROGRESS)?.takeIf { !it.isJsonNull }?.asFloat
            )

        private fun TraktMutationEnvelope.scrobbleAction(): String =
            payload.get(PAYLOAD_ACTION)?.asString ?: "stop"

        private fun TraktMutationEnvelope.buildRequestBody(): SimklScrobbleRequestDto {
            val parentKind = payload.get(PAYLOAD_PARENT_KIND)?.asString ?: when (
                payload.get(PAYLOAD_ITEM_TYPE)?.asString
            ) {
                "movie" -> PARENT_KIND_MOVIE
                else -> PARENT_KIND_SHOW
            }
            val progress = payload.get(PAYLOAD_PROGRESS)?.takeIf { !it.isJsonNull }?.asFloat
            return when (payload.get(PAYLOAD_ITEM_TYPE)?.asString) {
                "movie" -> {
                    val movieIds = SimklIdsDto(
                        simkl = payload.get(PAYLOAD_SIMKL)?.takeIf { !it.isJsonNull }?.asLong,
                        imdb = payload.get(PAYLOAD_IMDB)?.asString,
                        tmdb = payload.get(PAYLOAD_TMDB)?.takeIf { !it.isJsonNull }?.asString,
                        tvdb = payload.get(PAYLOAD_TVDB)?.asString,
                        mal = payload.get(PAYLOAD_MAL)?.asString,
                        anilist = payload.get(PAYLOAD_ANILIST)?.asString,
                        kitsu = payload.get(PAYLOAD_KITSU)?.asString,
                        anidb = payload.get(PAYLOAD_ANIDB)?.asString,
                    )
                    val movieRef = SimklMediaRefDto(
                        title = payload.get(PAYLOAD_TITLE)?.asString,
                        year = payload.get(PAYLOAD_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = movieIds,
                    )
                    if (parentKind == PARENT_KIND_ANIME) {
                        SimklScrobbleRequestDto(progress = progress, anime = movieRef)
                    } else {
                        SimklScrobbleRequestDto(progress = progress, movie = movieRef)
                    }
                }
                else -> {
                    val showIds = SimklIdsDto(
                        simkl = payload.get(PAYLOAD_SHOW_SIMKL)?.takeIf { !it.isJsonNull }?.asLong,
                        imdb = payload.get(PAYLOAD_SHOW_IMDB)?.asString,
                        tmdb = payload.get(PAYLOAD_SHOW_TMDB)?.takeIf { !it.isJsonNull }?.asString,
                        tvdb = payload.get(PAYLOAD_SHOW_TVDB)?.asString,
                        mal = payload.get(PAYLOAD_SHOW_MAL)?.asString,
                        anilist = payload.get(PAYLOAD_SHOW_ANILIST)?.asString,
                        kitsu = payload.get(PAYLOAD_SHOW_KITSU)?.asString,
                        anidb = payload.get(PAYLOAD_SHOW_ANIDB)?.asString,
                    )
                    val showRef = SimklMediaRefDto(
                        title = payload.get(PAYLOAD_SHOW_TITLE)?.asString,
                        year = payload.get(PAYLOAD_SHOW_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = showIds,
                    )
                    val episodeDto = SimklEpisodeDto(
                        season = payload.get(PAYLOAD_SEASON)?.asInt,
                        number = payload.get(PAYLOAD_NUMBER)?.asInt,
                        title = payload.get(PAYLOAD_EPISODE_TITLE)?.asString,
                    )
                    if (parentKind == PARENT_KIND_ANIME) {
                        SimklScrobbleRequestDto(progress = progress, anime = showRef, episode = episodeDto)
                    } else {
                        SimklScrobbleRequestDto(progress = progress, show = showRef, episode = episodeDto)
                    }
                }
            }
        }

        private data class ParsedSimklIds(
            val simkl: Long? = null,
            val imdb: String? = null,
            val tmdb: String? = null
        )

        private fun parseSimklIds(contentId: String?): ParsedSimklIds {
            if (contentId.isNullOrBlank()) return ParsedSimklIds()
            val raw = contentId.trim()
            return when {
                raw.startsWith("tt", ignoreCase = true) -> ParsedSimklIds(imdb = raw.substringBefore(':'))
                raw.startsWith("tmdb:", ignoreCase = true) -> ParsedSimklIds(tmdb = raw.substringAfter(':'))
                raw.startsWith("simkl:", ignoreCase = true) -> ParsedSimklIds(simkl = raw.substringAfter(':').toLongOrNull())
                raw.toLongOrNull() != null -> ParsedSimklIds(simkl = raw.toLongOrNull())
                else -> ParsedSimklIds()
            }
        }

    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SimklScrobbleMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindSimklScrobbleMutationAdapter(
        impl: SimklScrobbleMutationAdapter
    ): TraktMutationAdapter
}

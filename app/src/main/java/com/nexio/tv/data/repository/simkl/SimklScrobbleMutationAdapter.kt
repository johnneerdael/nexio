package com.nexio.tv.data.repository.simkl

import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.simkl.SimklEpisodeDto
import com.nexio.tv.data.remote.dto.simkl.SimklIdsDto
import com.nexio.tv.data.remote.dto.simkl.SimklMediaRefDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
import com.nexio.tv.data.repository.SimklProgressService
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.trakt.TraktWatchingNowStateController
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
        val response = when (envelope.mutationKind) {
            MUTATION_KIND_CHECKIN -> remote.checkin(envelope.buildRequestBody())
            MUTATION_KIND_SCROBBLE -> when (envelope.scrobbleAction()) {
                "start" -> remote.scrobbleStart(envelope.buildRequestBody())
                "pause" -> remote.scrobblePause(envelope.buildRequestBody())
                else -> remote.scrobbleStop(envelope.buildRequestBody())
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
        private const val PAYLOAD_TITLE = "title"
        private const val PAYLOAD_YEAR = "year"
        private const val PAYLOAD_IMDB = "imdb"
        private const val PAYLOAD_TMDB = "tmdb"
        private const val PAYLOAD_SIMKL = "simkl"
        private const val PAYLOAD_SHOW_TITLE = "showTitle"
        private const val PAYLOAD_SHOW_YEAR = "showYear"
        private const val PAYLOAD_SHOW_IMDB = "showImdb"
        private const val PAYLOAD_SHOW_TMDB = "showTmdb"
        private const val PAYLOAD_SHOW_SIMKL = "showSimkl"
        private const val PAYLOAD_SEASON = "season"
        private const val PAYLOAD_NUMBER = "number"
        private const val PAYLOAD_EPISODE_TITLE = "episodeTitle"
        private const val PAYLOAD_ACTION = "action"
        private const val PAYLOAD_PROGRESS = "progress"
        private const val PAYLOAD_MESSAGE = "message"

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
            optimisticVersion: Long
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                addProperty(PAYLOAD_ACTION, action)
                addProperty(PAYLOAD_PROGRESS, progressPercent.coerceIn(0f, 100f))
            }
            return TraktMutationEnvelope(
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
            optimisticVersion: Long
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                message?.let { addProperty(PAYLOAD_MESSAGE, it) }
            }
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_CHECKIN,
                priority = TraktMutationPriorityBucket.SCROBBLE,
                collapseKey = "simkl.scrobble:${item.itemKey()}",
                payload = payload,
                metadata = buildRollbackMetadata(rollbackState, optimisticVersion)
            )
        }

        private fun JsonObject.populateItem(item: TrackingScrobbleItem) {
            when (item) {
                is TrackingScrobbleItem.Movie -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "movie")
                    item.title?.let { addProperty(PAYLOAD_TITLE, it) }
                    item.year?.let { addProperty(PAYLOAD_YEAR, it) }
                    parseSimklIds(item.contentId).imdb?.let { addProperty(PAYLOAD_IMDB, it) }
                    parseSimklIds(item.contentId).tmdb?.let { addProperty(PAYLOAD_TMDB, it) }
                    parseSimklIds(item.contentId).simkl?.let { addProperty(PAYLOAD_SIMKL, it) }
                }
                is TrackingScrobbleItem.Episode -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "episode")
                    item.showTitle?.let { addProperty(PAYLOAD_SHOW_TITLE, it) }
                    item.showYear?.let { addProperty(PAYLOAD_SHOW_YEAR, it) }
                    parseSimklIds(item.contentId).imdb?.let { addProperty(PAYLOAD_SHOW_IMDB, it) }
                    parseSimklIds(item.contentId).tmdb?.let { addProperty(PAYLOAD_SHOW_TMDB, it) }
                    parseSimklIds(item.contentId).simkl?.let { addProperty(PAYLOAD_SHOW_SIMKL, it) }
                    addProperty(PAYLOAD_SEASON, item.season)
                    addProperty(PAYLOAD_NUMBER, item.number)
                    item.episodeTitle?.let { addProperty(PAYLOAD_EPISODE_TITLE, it) }
                }
            }
        }

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
            return when (payload.get(PAYLOAD_ITEM_TYPE)?.asString) {
                "movie" -> SimklScrobbleRequestDto(
                    progress = payload.get(PAYLOAD_PROGRESS)?.takeIf { !it.isJsonNull }?.asFloat,
                    movie = SimklMediaRefDto(
                        title = payload.get(PAYLOAD_TITLE)?.asString,
                        year = payload.get(PAYLOAD_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = SimklIdsDto(
                            simkl = payload.get(PAYLOAD_SIMKL)?.takeIf { !it.isJsonNull }?.asLong,
                            imdb = payload.get(PAYLOAD_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_TMDB)?.takeIf { !it.isJsonNull }?.asString
                        )
                    )
                )
                else -> SimklScrobbleRequestDto(
                    progress = payload.get(PAYLOAD_PROGRESS)?.takeIf { !it.isJsonNull }?.asFloat,
                    show = SimklMediaRefDto(
                        title = payload.get(PAYLOAD_SHOW_TITLE)?.asString,
                        year = payload.get(PAYLOAD_SHOW_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = SimklIdsDto(
                            simkl = payload.get(PAYLOAD_SHOW_SIMKL)?.takeIf { !it.isJsonNull }?.asLong,
                            imdb = payload.get(PAYLOAD_SHOW_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_SHOW_TMDB)?.takeIf { !it.isJsonNull }?.asString
                        )
                    ),
                    episode = SimklEpisodeDto(
                        season = payload.get(PAYLOAD_SEASON)?.asInt,
                        number = payload.get(PAYLOAD_NUMBER)?.asInt,
                        title = payload.get(PAYLOAD_EPISODE_TITLE)?.asString
                    )
                )
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

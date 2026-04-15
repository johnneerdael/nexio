package com.nexio.tv.data.repository.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryRemoveRequestDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktProgressService
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
class TraktProgressHistoryMutationAdapter @Inject constructor(
    private val traktApi: TraktApi,
    private val traktAuthService: TraktAuthService,
    private val traktProgressService: TraktProgressService
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> traktProgressService.applyOptimisticProgress(envelope.progress())
            MUTATION_KIND_HISTORY_REMOVE,
            MUTATION_KIND_PLAYBACK_DELETE -> Unit
        }
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> executeHistoryAdd(envelope)
            MUTATION_KIND_HISTORY_REMOVE -> executeHistoryRemove(envelope)
            MUTATION_KIND_PLAYBACK_DELETE -> executePlaybackDelete(envelope)
            else -> TraktMutationExecutionResult.Failure(
                httpStatusCode = 400,
                reason = "Unsupported progress mutation kind ${envelope.mutationKind}"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> traktProgressService.reconcileQueuedHistoryAddSuccess(envelope.progress())
            MUTATION_KIND_HISTORY_REMOVE -> traktProgressService.reconcileQueuedHistoryRemoveSuccess(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                removeShow = envelope.removeShow()
            )
            MUTATION_KIND_PLAYBACK_DELETE -> traktProgressService.reconcileQueuedPlaybackDeleteSuccess(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                clearShow = envelope.clearShow()
            )
        }
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> traktProgressService.rollbackQueuedHistoryAdd(envelope.progress())
            MUTATION_KIND_HISTORY_REMOVE -> traktProgressService.rollbackQueuedHistoryRemove(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                removeShow = envelope.removeShow()
            )
            MUTATION_KIND_PLAYBACK_DELETE -> traktProgressService.rollbackQueuedPlaybackDelete(
                contentId = envelope.contentId(),
                season = envelope.season(),
                episode = envelope.episode(),
                clearShow = envelope.clearShow()
            )
        }
    }

    private suspend fun executeHistoryAdd(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val body = traktProgressService.buildHistoryAddRequestForOutbox(
            progress = envelope.progress(),
            title = envelope.title(),
            year = envelope.year()
        ) ?: return TraktMutationExecutionResult.Failure(
            httpStatusCode = 422,
            reason = "Insufficient Trakt IDs to mark watched"
        )

        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val response = traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.addHistory(authHeader, body)
        } ?: return TraktMutationExecutionResult.Failure(
            reason = "Trakt request failed"
        )

        val responseBody = response.body()
        return if (!response.isSuccessful || traktProgressService.hasHistoryAddNotFoundForOutbox(responseBody)) {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to mark watched on Trakt (${response.code()})"
            )
        } else {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        }
    }

    private suspend fun executeHistoryRemove(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val body = traktProgressService.buildHistoryRemoveRequestForOutbox(
            contentId = envelope.contentId(),
            season = envelope.season(),
            episode = envelope.episode(),
            removeShow = envelope.removeShow()
        ) ?: return TraktMutationExecutionResult.Success(httpStatusCode = 204)

        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val response = traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.removeHistory(authHeader, body)
        } ?: return TraktMutationExecutionResult.Failure(
            reason = "Trakt request failed"
        )

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to remove history on Trakt (${response.code()})"
            )
        }
    }

    private suspend fun executePlaybackDelete(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val playbackId = envelope.playbackId()
        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val response = traktAuthService.executeAuthorizedWriteRequest(session) { authHeader ->
            traktApi.deletePlayback(authHeader, playbackId)
        } ?: return TraktMutationExecutionResult.Failure(
            reason = "Trakt request failed"
        )

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Failed to delete playback on Trakt (${response.code()})"
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

        const val ADAPTER_KEY = "progress-history"
        const val MUTATION_KIND_HISTORY_ADD = "progress.history.add"
        const val MUTATION_KIND_HISTORY_REMOVE = "progress.history.remove"
        const val MUTATION_KIND_PLAYBACK_DELETE = "progress.playback.delete"

        private val gson = Gson()

        fun buildHistoryAddEnvelope(
            progress: WatchProgress,
            title: String?,
            year: Int?
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                add(PAYLOAD_PROGRESS, gson.toJsonTree(progress))
            }
            val metadata = JsonObject().apply {
                title?.let { addProperty(METADATA_TITLE, it) }
                year?.let { addProperty(METADATA_YEAR, it) }
            }
            val collapseKey = buildString {
                append(progress.contentId.trim())
                progress.season?.let { append(":s$it") }
                progress.episode?.let { append(":e$it") }
            }.ifBlank { null }

            return TraktMutationEnvelope(
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
            removeShow: Boolean = false
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_CONTENT_ID, contentId)
                season?.let { addProperty(PAYLOAD_SEASON, it) }
                episode?.let { addProperty(PAYLOAD_EPISODE, it) }
                if (removeShow) addProperty(PAYLOAD_REMOVE_SHOW, true)
            }
            val collapseKey = buildString {
                append(contentId.trim())
                if (removeShow) {
                    append(":show")
                } else {
                    season?.let { append(":s$it") }
                    episode?.let { append(":e$it") }
                }
            }.ifBlank { null }
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_HISTORY_REMOVE,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = collapseKey,
                payload = payload
            )
        }

        fun buildPlaybackDeleteEnvelope(
            playbackId: Long,
            contentId: String,
            season: Int?,
            episode: Int?,
            clearShow: Boolean = false
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                addProperty(PAYLOAD_PLAYBACK_ID, playbackId)
                addProperty(PAYLOAD_CONTENT_ID, contentId)
                season?.let { addProperty(PAYLOAD_SEASON, it) }
                episode?.let { addProperty(PAYLOAD_EPISODE, it) }
                if (clearShow) addProperty(PAYLOAD_CLEAR_SHOW, true)
            }
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_PLAYBACK_DELETE,
                priority = TraktMutationPriorityBucket.WATCHED,
                collapseKey = "playback:$playbackId",
                payload = payload
            )
        }

        private fun TraktMutationEnvelope.progress(): WatchProgress {
            return gson.fromJson(
                payload.get(PAYLOAD_PROGRESS),
                WatchProgress::class.java
            )
        }

        private fun TraktMutationEnvelope.contentId(): String {
            return payload.get(PAYLOAD_CONTENT_ID)?.asString
                ?: error("Missing content id payload")
        }

        private fun TraktMutationEnvelope.season(): Int? {
            return payload.get(PAYLOAD_SEASON)?.takeIf { !it.isJsonNull }?.asInt
        }

        private fun TraktMutationEnvelope.episode(): Int? {
            return payload.get(PAYLOAD_EPISODE)?.takeIf { !it.isJsonNull }?.asInt
        }

        private fun TraktMutationEnvelope.playbackId(): Long {
            return payload.get(PAYLOAD_PLAYBACK_ID)?.asLong
                ?: error("Missing playback id payload")
        }

        private fun TraktMutationEnvelope.removeShow(): Boolean {
            return payload.get(PAYLOAD_REMOVE_SHOW)?.asBoolean ?: false
        }

        private fun TraktMutationEnvelope.clearShow(): Boolean {
            return payload.get(PAYLOAD_CLEAR_SHOW)?.asBoolean ?: false
        }

        private fun TraktMutationEnvelope.title(): String? {
            return metadata.get(METADATA_TITLE)?.asString
        }

        private fun TraktMutationEnvelope.year(): Int? {
            return metadata.get(METADATA_YEAR)?.asInt
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktProgressHistoryMutationAdapterModule {

    @Binds
    @IntoSet
    abstract fun bindTraktProgressHistoryMutationAdapter(
        impl: TraktProgressHistoryMutationAdapter
    ): TraktMutationAdapter
}

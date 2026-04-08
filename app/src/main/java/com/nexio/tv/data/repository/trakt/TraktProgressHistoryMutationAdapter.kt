package com.nexio.tv.data.repository.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.api.TraktApi
import com.nexio.tv.data.remote.dto.trakt.TraktHistoryAddRequestDto
import com.nexio.tv.data.repository.TraktAuthService
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
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
        }
    }

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> executeHistoryAdd(envelope)
            else -> TraktMutationExecutionResult.Failure(
                httpStatusCode = 400,
                reason = "Unsupported progress mutation kind ${envelope.mutationKind}"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> traktProgressService.reconcileQueuedHistoryAddSuccess(envelope.progress())
        }
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        when (envelope.mutationKind) {
            MUTATION_KIND_HISTORY_ADD -> traktProgressService.rollbackQueuedHistoryAdd(envelope.progress())
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

        val response = traktAuthService.executeAuthorizedWriteRequest { authHeader ->
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

    companion object {
        private const val PAYLOAD_PROGRESS = "progress"
        private const val METADATA_TITLE = "title"
        private const val METADATA_YEAR = "year"

        const val ADAPTER_KEY = "progress-history"
        const val MUTATION_KIND_HISTORY_ADD = "progress.history.add"

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

        private fun TraktMutationEnvelope.progress(): WatchProgress {
            return gson.fromJson(
                payload.get(PAYLOAD_PROGRESS),
                WatchProgress::class.java
            )
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

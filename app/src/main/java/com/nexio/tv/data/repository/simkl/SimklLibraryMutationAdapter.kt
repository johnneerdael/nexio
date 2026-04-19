package com.nexio.tv.data.repository.simkl

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.SimklLibraryService
import com.nexio.tv.data.repository.SimklTrackingRemoteDataSource
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
class SimklLibraryMutationAdapter @Inject constructor(
    private val remote: SimklTrackingRemoteDataSource,
    private val libraryServiceProvider: Provider<SimklLibraryService>
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = when (envelope.mutationKind) {
            MUTATION_KIND_ADD_TO_LIST -> remote.addToList(envelope.addBody(), envelope.session())
            MUTATION_KIND_REMOVE -> remote.removeFromHistoryAndLists(envelope.removeBody(), envelope.session())
            else -> null
        } ?: return TraktMutationExecutionResult.Failure(httpStatusCode = 400, reason = "Unsupported SIMKL library mutation ${envelope.mutationKind}")

        return if (response.isSuccessful) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "SIMKL library mutation failed (${response.code()})"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        libraryServiceProvider.get().refreshProfile(profileId = envelope.profileId, force = true)
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        libraryServiceProvider.get().rollbackQueuedLibraryMutation(
            rollbackState = envelope.rollbackState(),
            profileId = envelope.profileId
        )
    }

    companion object {
        private const val PAYLOAD_BODY = "body"
        const val ADAPTER_KEY = "simkl.library"
        const val MUTATION_KIND_ADD_TO_LIST = "simkl.library.addToList"
        const val MUTATION_KIND_REMOVE = "simkl.library.remove"
        private val gson = Gson()

        fun buildAddToListEnvelope(
            listKey: String,
            body: SimklAddToListRequestDto,
            rollbackState: SimklLibraryService.LibraryRollbackState,
            profileId: Int = 1
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                profileId = profileId,
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_ADD_TO_LIST,
                priority = TraktMutationPriorityBucket.WATCHLIST,
                collapseKey = "simkl.library:$listKey",
                payload = JsonObject().apply { add(PAYLOAD_BODY, gson.toJsonTree(body)) },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        fun buildRemoveEnvelope(
            body: SimklHistoryRemoveRequestDto,
            rollbackState: SimklLibraryService.LibraryRollbackState,
            profileId: Int = 1
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                profileId = profileId,
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_REMOVE,
                priority = TraktMutationPriorityBucket.WATCHLIST,
                collapseKey = "simkl.library:remove",
                payload = JsonObject().apply { add(PAYLOAD_BODY, gson.toJsonTree(body)) },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        private fun TraktMutationEnvelope.addBody(): SimklAddToListRequestDto =
            gson.fromJson(payload.get(PAYLOAD_BODY), SimklAddToListRequestDto::class.java)

        private fun TraktMutationEnvelope.removeBody(): SimklHistoryRemoveRequestDto =
            gson.fromJson(payload.get(PAYLOAD_BODY), SimklHistoryRemoveRequestDto::class.java)

        private fun TraktMutationEnvelope.rollbackState(): SimklLibraryService.LibraryRollbackState =
            gson.fromJson(
                rollbackPayload ?: JsonObject(),
                SimklLibraryService.LibraryRollbackState::class.java
            ) ?: SimklLibraryService.LibraryRollbackState()

        private fun TraktMutationEnvelope.session(): TrackingAuthSession =
            TrackingAuthSession(
                provider = TrackingProvider.SIMKL,
                profileId = profileId
            )
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class SimklLibraryMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindSimklLibraryMutationAdapter(
        impl: SimklLibraryMutationAdapter
    ): TraktMutationAdapter
}

package com.nexio.tv.data.repository.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.repository.TraktLibraryService
import com.nexio.tv.data.remote.dto.trakt.TraktCreateOrUpdateListRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktListItemsMutationResponseDto
import com.nexio.tv.data.remote.dto.trakt.TraktListSummaryDto
import com.nexio.tv.data.remote.dto.trakt.TraktReorderListsRequestDto
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
import retrofit2.Response

@Singleton
class TraktLibraryMutationAdapter @Inject constructor(
    private val executor: TraktLibraryMutationExecutor,
    private val libraryServiceProvider: Provider<TraktLibraryService>
) : TraktMutationAdapter {

    private val createdListsByEnvelopeId = ConcurrentHashMap<String, TraktListSummaryDto>()

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return when (envelope.mutationKind) {
            MUTATION_KIND_CREATE_LIST -> executeCreateList(envelope)
            MUTATION_KIND_UPDATE_LIST -> executeUpdateList(envelope)
            MUTATION_KIND_DELETE_LIST -> executeDeleteList(envelope)
            MUTATION_KIND_REORDER_LISTS -> executeReorderLists(envelope)
            MUTATION_KIND_WATCHLIST_ADD -> executeWatchlistAdd(envelope)
            MUTATION_KIND_WATCHLIST_REMOVE -> executeWatchlistRemove(envelope)
            MUTATION_KIND_LIST_ADD -> executeListAdd(envelope)
            MUTATION_KIND_LIST_REMOVE -> executeListRemove(envelope)
            else -> TraktMutationExecutionResult.Failure(
                httpStatusCode = 400,
                reason = "Unsupported library mutation kind ${envelope.mutationKind}"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        when (envelope.mutationKind) {
            MUTATION_KIND_CREATE_LIST -> {
                libraryServiceProvider.get().reconcileQueuedCreateListSuccess(
                    provisionalKey = envelope.provisionalKey(),
                    createdList = createdListsByEnvelopeId.remove(envelope.id),
                    profileId = envelope.profileId
                )
            }

            else -> Unit
        }
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        val service = libraryServiceProvider.get()
        val provisionalKey = envelope.provisionalKeyOrNull()
        if (provisionalKey != null) {
            service.rollbackQueuedLibraryMutation(
                rollbackState = envelope.rollbackState(),
                provisionalKeyToRemove = provisionalKey,
                profileId = envelope.profileId
            )
        } else {
            service.rollbackQueuedLibraryMutation(
                rollbackState = envelope.rollbackState(),
                profileId = envelope.profileId
            )
        }
    }

    private suspend fun executeCreateList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(
            envelope = envelope,
            failureLabel = "create list",
            successValidator = { summary ->
                summary?.let { createdListsByEnvelopeId[envelope.id] = it }
                summary?.ids?.trakt != null || !summary?.ids?.slug.isNullOrBlank()
            }
        ) {
            executor.createUserList(
                session = envelope.session(),
                id = ME_PATH,
                body = envelope.listRequestBody()
            )
        }
    }

    private suspend fun executeUpdateList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(failureLabel = "update list") {
            executor.updateUserList(
                session = envelope.session(),
                id = ME_PATH,
                listId = envelope.listId(),
                body = envelope.listRequestBody()
            )
        }
    }

    private suspend fun executeDeleteList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(failureLabel = "delete list") {
            executor.deleteUserList(
                session = envelope.session(),
                id = ME_PATH,
                listId = envelope.listId()
            )
        }
    }

    private suspend fun executeReorderLists(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(failureLabel = "reorder lists") {
            executor.reorderUserLists(
                session = envelope.session(),
                id = ME_PATH,
                body = envelope.reorderRequestBody()
            )
        }
    }

    private suspend fun executeWatchlistAdd(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(
            envelope = envelope,
            failureLabel = "add to watchlist",
            successValidator = ::isSuccessfulAddResponse
        ) {
            executor.addToWatchlist(envelope.session(), envelope.listItemsBody())
        }
    }

    private suspend fun executeWatchlistRemove(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(failureLabel = "remove from watchlist") {
            executor.removeFromWatchlist(envelope.session(), envelope.listItemsBody())
        }
    }

    private suspend fun executeListAdd(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(
            envelope = envelope,
            failureLabel = "add to list",
            successValidator = ::isSuccessfulAddResponse
        ) {
            executor.addUserListItems(
                session = envelope.session(),
                id = ME_PATH,
                listId = envelope.listId(),
                body = envelope.listItemsBody()
            )
        }
    }

    private suspend fun executeListRemove(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return executeMutation(failureLabel = "remove from list") {
            executor.removeUserListItems(
                session = envelope.session(),
                id = ME_PATH,
                listId = envelope.listId(),
                body = envelope.listItemsBody()
            )
        }
    }

    private fun success(code: Int?) = TraktMutationExecutionResult.Success(httpStatusCode = code)

    private fun failed(reason: String, code: Int? = null) = TraktMutationExecutionResult.Failure(
        httpStatusCode = code,
        reason = reason
    )

    private suspend fun <T> executeMutation(
        envelope: TraktMutationEnvelope? = null,
        failureLabel: String,
        successValidator: ((T?) -> Boolean)? = null,
        request: suspend () -> Response<T>?
    ): TraktMutationExecutionResult {
        val response = request() ?: return failed("Trakt request failed")
        val body = response.body()
        if (response.isSuccessful && successValidator != null && !successValidator(body)) {
            return failed("Failed to $failureLabel (${response.code()})", response.code())
        }
        return response.toExecutionResult(
            failureLabel = failureLabel,
            mutationKind = envelope?.mutationKind
        )
    }

    private fun <T> Response<T>.toExecutionResult(
        failureLabel: String,
        mutationKind: String? = null
    ): TraktMutationExecutionResult {
        val code = code()
        return if (isSuccessful) {
            success(code)
        } else {
            val context = mutationKind?.let { " for $it" }.orEmpty()
            failed("Failed to $failureLabel$context ($code)", code)
        }
    }

    private fun isSuccessfulAddResponse(body: TraktListItemsMutationResponseDto?): Boolean {
        val added = body?.added
        val existing = body?.existing
        val addCount = (added?.movies ?: 0) + (added?.shows ?: 0) + (added?.seasons ?: 0) + (added?.episodes ?: 0)
        val existingCount = (existing?.movies ?: 0) + (existing?.shows ?: 0) + (existing?.seasons ?: 0) + (existing?.episodes ?: 0)
        return addCount > 0 || existingCount > 0
    }

    companion object {
        private const val PAYLOAD_LIST_ID = "listId"
        private const val PAYLOAD_BODY = "body"
        private const val METADATA_PROVISIONAL_KEY = "provisionalKey"

        const val ADAPTER_KEY = "library"
        const val MUTATION_KIND_CREATE_LIST = "library.list.create"
        const val MUTATION_KIND_UPDATE_LIST = "library.list.update"
        const val MUTATION_KIND_DELETE_LIST = "library.list.delete"
        const val MUTATION_KIND_REORDER_LISTS = "library.list.reorder"
        const val MUTATION_KIND_WATCHLIST_ADD = "library.watchlist.add"
        const val MUTATION_KIND_WATCHLIST_REMOVE = "library.watchlist.remove"
        const val MUTATION_KIND_LIST_ADD = "library.listItems.add"
        const val MUTATION_KIND_LIST_REMOVE = "library.listItems.remove"
        private val gson = Gson()

        fun buildCreateListEnvelope(
            provisionalKey: String,
            body: TraktCreateOrUpdateListRequestDto
        ): TraktMutationEnvelope {
            return buildCreateListEnvelope(
                provisionalKey = provisionalKey,
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildCreateListEnvelope(
            provisionalKey: String,
            body: TraktCreateOrUpdateListRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_CREATE_LIST,
                priority = TraktMutationPriorityBucket.WATCHLIST,
                collapseKey = "library:create:${body.name.orEmpty()}",
                payload = JsonObject().apply { add(PAYLOAD_BODY, gson.toJsonTree(body)) },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject,
                metadata = JsonObject().apply { addProperty(METADATA_PROVISIONAL_KEY, provisionalKey) }
            )
        }

        fun buildUpdateListEnvelope(
            listId: String,
            body: TraktCreateOrUpdateListRequestDto
        ): TraktMutationEnvelope {
            return buildUpdateListEnvelope(
                listId = listId,
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildUpdateListEnvelope(
            listId: String,
            body: TraktCreateOrUpdateListRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_UPDATE_LIST,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:list:$listId",
                payload = JsonObject().apply {
                    addProperty(PAYLOAD_LIST_ID, listId)
                    add(PAYLOAD_BODY, gson.toJsonTree(body))
                },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        fun buildDeleteListEnvelope(listId: String): TraktMutationEnvelope {
            return buildDeleteListEnvelope(
                listId = listId,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildDeleteListEnvelope(
            listId: String,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_DELETE_LIST,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:list:$listId",
                payload = JsonObject().apply {
                    addProperty(PAYLOAD_LIST_ID, listId)
                },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        fun buildReorderListsEnvelope(
            rank: List<Long>
        ): TraktMutationEnvelope {
            return buildReorderListsEnvelope(
                rank = rank,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildReorderListsEnvelope(
            rank: List<Long>,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_REORDER_LISTS,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:reorder",
                payload = JsonObject().apply {
                    add(PAYLOAD_BODY, gson.toJsonTree(TraktReorderListsRequestDto(rank = rank)))
                },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        fun buildWatchlistAddEnvelope(
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildWatchlistAddEnvelope(
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildWatchlistAddEnvelope(
            body: TraktListItemsMutationRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_WATCHLIST_ADD, "library:watchlist", body, rollbackState = rollbackState)
        }

        fun buildWatchlistRemoveEnvelope(
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildWatchlistRemoveEnvelope(
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildWatchlistRemoveEnvelope(
            body: TraktListItemsMutationRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_WATCHLIST_REMOVE, "library:watchlist", body, rollbackState = rollbackState)
        }

        fun buildListAddEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildListAddEnvelope(
                listId = listId,
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildListAddEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_LIST_ADD, "library:list:$listId", body, rollbackState, listId)
        }

        fun buildListRemoveEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildListRemoveEnvelope(
                listId = listId,
                body = body,
                rollbackState = TraktLibraryService.LibraryRollbackState()
            )
        }

        fun buildListRemoveEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_LIST_REMOVE, "library:list:$listId", body, rollbackState, listId)
        }

        private fun buildItemEnvelope(
            kind: String,
            collapseKey: String,
            body: TraktListItemsMutationRequestDto,
            rollbackState: TraktLibraryService.LibraryRollbackState,
            listId: String? = null
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = kind,
                priority = if (kind.contains("watchlist")) TraktMutationPriorityBucket.WATCHLIST else TraktMutationPriorityBucket.LISTS,
                collapseKey = collapseKey,
                payload = JsonObject().apply {
                    listId?.let { addProperty(PAYLOAD_LIST_ID, it) }
                    add(PAYLOAD_BODY, gson.toJsonTree(body))
                },
                rollbackPayload = gson.toJsonTree(rollbackState).asJsonObject
            )
        }

        private fun TraktMutationEnvelope.listId(): String {
            return payload.get(PAYLOAD_LIST_ID)?.asString
                ?: error("Missing listId payload")
        }

        private fun TraktMutationEnvelope.listRequestBody(): TraktCreateOrUpdateListRequestDto {
            return gson.fromJson(payload.get(PAYLOAD_BODY), TraktCreateOrUpdateListRequestDto::class.java)
        }

        private fun TraktMutationEnvelope.reorderRequestBody(): TraktReorderListsRequestDto {
            return gson.fromJson(payload.get(PAYLOAD_BODY), TraktReorderListsRequestDto::class.java)
        }

        private fun TraktMutationEnvelope.listItemsBody(): TraktListItemsMutationRequestDto {
            return gson.fromJson(payload.get(PAYLOAD_BODY), TraktListItemsMutationRequestDto::class.java)
        }

        private fun TraktMutationEnvelope.rollbackState(): TraktLibraryService.LibraryRollbackState {
            return gson.fromJson(
                rollbackPayload ?: JsonObject(),
                TraktLibraryService.LibraryRollbackState::class.java
            ) ?: TraktLibraryService.LibraryRollbackState()
        }

        private fun TraktMutationEnvelope.provisionalKey(): String {
            return metadata.get(METADATA_PROVISIONAL_KEY)?.asString
                ?: error("Missing provisional key metadata")
        }

        private fun TraktMutationEnvelope.provisionalKeyOrNull(): String? {
            return metadata.get(METADATA_PROVISIONAL_KEY)?.asString
        }

        private fun TraktMutationEnvelope.session(): TrackingAuthSession {
            return TrackingAuthSession(
                provider = TrackingProvider.TRAKT,
                profileId = profileId
            )
        }

        private const val ME_PATH = "me"
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktLibraryMutationAdapterModule {
    @Binds
    @IntoSet
    abstract fun bindTraktLibraryMutationAdapter(
        impl: TraktLibraryMutationAdapter
    ): TraktMutationAdapter
}

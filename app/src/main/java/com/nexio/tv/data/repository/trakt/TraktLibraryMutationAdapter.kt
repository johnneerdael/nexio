package com.nexio.tv.data.repository.trakt

import com.google.gson.Gson
import com.google.gson.JsonObject
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
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktLibraryMutationAdapter @Inject constructor(
    private val executor: TraktLibraryMutationExecutor
) : TraktMutationAdapter {

    private val createdListsByEnvelopeId = ConcurrentHashMap<String, TraktListSummaryDto>()
    private val listMutationResponsesByEnvelopeId = ConcurrentHashMap<String, TraktListItemsMutationResponseDto?>()

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

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) = Unit

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) = Unit

    fun consumeCreatedListSummary(envelopeId: String): TraktListSummaryDto? {
        return createdListsByEnvelopeId.remove(envelopeId)
    }

    fun consumeListMutationResponse(envelopeId: String): TraktListItemsMutationResponseDto? {
        return listMutationResponsesByEnvelopeId.remove(envelopeId)
    }

    private suspend fun executeCreateList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.createUserList(
            id = ME_PATH,
            body = envelope.listRequestBody()
        ) ?: return failed("Trakt request failed")
        response.body()?.let { createdListsByEnvelopeId[envelope.id] = it }
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to create list (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeUpdateList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.updateUserList(
            id = ME_PATH,
            listId = envelope.listId(),
            body = envelope.listRequestBody()
        ) ?: return failed("Trakt request failed")
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to update list (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeDeleteList(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.deleteUserList(
            id = ME_PATH,
            listId = envelope.listId()
        ) ?: return failed("Trakt request failed")
        return if (response.isSuccessful || response.code() == 204) success(response.code()) else failed(
            "Failed to delete list (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeReorderLists(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.reorderUserLists(
            id = ME_PATH,
            body = envelope.reorderRequestBody()
        ) ?: return failed("Trakt request failed")
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to reorder lists (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeWatchlistAdd(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.addToWatchlist(envelope.listItemsBody()) ?: return failed("Trakt request failed")
        listMutationResponsesByEnvelopeId[envelope.id] = response.body()
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to add to watchlist (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeWatchlistRemove(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.removeFromWatchlist(envelope.listItemsBody()) ?: return failed("Trakt request failed")
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to remove from watchlist (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeListAdd(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.addUserListItems(
            id = ME_PATH,
            listId = envelope.listId(),
            body = envelope.listItemsBody()
        ) ?: return failed("Trakt request failed")
        listMutationResponsesByEnvelopeId[envelope.id] = response.body()
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to add to list (${response.code()})",
            response.code()
        )
    }

    private suspend fun executeListRemove(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val response = executor.removeUserListItems(
            id = ME_PATH,
            listId = envelope.listId(),
            body = envelope.listItemsBody()
        ) ?: return failed("Trakt request failed")
        return if (response.isSuccessful) success(response.code()) else failed(
            "Failed to remove from list (${response.code()})",
            response.code()
        )
    }

    private fun success(code: Int?) = TraktMutationExecutionResult.Success(httpStatusCode = code)

    private fun failed(reason: String, code: Int? = null) = TraktMutationExecutionResult.Failure(
        httpStatusCode = code,
        reason = reason
    )

    companion object {
        private const val PAYLOAD_LIST_ID = "listId"
        private const val PAYLOAD_BODY = "body"

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
            body: TraktCreateOrUpdateListRequestDto
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_CREATE_LIST,
                priority = TraktMutationPriorityBucket.WATCHLIST,
                collapseKey = "library:create:${body.name.orEmpty()}",
                payload = JsonObject().apply { add(PAYLOAD_BODY, gson.toJsonTree(body)) }
            )
        }

        fun buildUpdateListEnvelope(
            listId: String,
            body: TraktCreateOrUpdateListRequestDto
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_UPDATE_LIST,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:list:$listId",
                payload = JsonObject().apply {
                    addProperty(PAYLOAD_LIST_ID, listId)
                    add(PAYLOAD_BODY, gson.toJsonTree(body))
                }
            )
        }

        fun buildDeleteListEnvelope(listId: String): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_DELETE_LIST,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:list:$listId",
                payload = JsonObject().apply { addProperty(PAYLOAD_LIST_ID, listId) }
            )
        }

        fun buildReorderListsEnvelope(
            rank: List<Long>
        ): TraktMutationEnvelope {
            return TraktMutationEnvelope(
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_REORDER_LISTS,
                priority = TraktMutationPriorityBucket.LISTS,
                collapseKey = "library:reorder",
                payload = JsonObject().apply {
                    add(PAYLOAD_BODY, gson.toJsonTree(TraktReorderListsRequestDto(rank = rank)))
                }
            )
        }

        fun buildWatchlistAddEnvelope(body: TraktListItemsMutationRequestDto): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_WATCHLIST_ADD, "library:watchlist", body)
        }

        fun buildWatchlistRemoveEnvelope(body: TraktListItemsMutationRequestDto): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_WATCHLIST_REMOVE, "library:watchlist", body)
        }

        fun buildListAddEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_LIST_ADD, "library:list:$listId", body, listId)
        }

        fun buildListRemoveEnvelope(
            listId: String,
            body: TraktListItemsMutationRequestDto
        ): TraktMutationEnvelope {
            return buildItemEnvelope(MUTATION_KIND_LIST_REMOVE, "library:list:$listId", body, listId)
        }

        private fun buildItemEnvelope(
            kind: String,
            collapseKey: String,
            body: TraktListItemsMutationRequestDto,
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
                }
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

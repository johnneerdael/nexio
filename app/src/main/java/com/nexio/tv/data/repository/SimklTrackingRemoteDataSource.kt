package com.nexio.tv.data.repository

import com.nexio.tv.data.integration.simkl.SimklIntegrationProvider
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklAddToListResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryAddRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklHistoryRemoveRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklLastActivitiesResponseDto
import com.nexio.tv.data.remote.dto.simkl.SimklLibraryItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklPlaybackItemDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleRequestDto
import com.nexio.tv.data.remote.dto.simkl.SimklScrobbleResponseDto
import com.squareup.moshi.JsonReader
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import javax.inject.Inject
import javax.inject.Singleton
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import retrofit2.Response

@Singleton
class SimklTrackingRemoteDataSource @Inject constructor(
    private val simklIntegrationProvider: SimklIntegrationProvider
) {
    suspend fun getLastActivities(session: TrackingAuthSession? = null): Response<SimklLastActivitiesResponseDto> {
        return simklIntegrationProvider.getLastActivities(session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun getAllItemsByStatus(
        type: String,
        status: String,
        dateFrom: String? = null,
        extended: String? = null,
        episodeWatchedAt: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklIntegrationProvider.getAllItemsByStatus(
            type = type,
            status = status,
            dateFrom = dateFrom,
            extended = extended,
            episodeWatchedAt = episodeWatchedAt,
            session = session
        ) ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun getAllItemsByType(
        type: String,
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklIntegrationProvider.getAllItemsByType(
            type = type,
            dateFrom = dateFrom,
            extended = extended,
            session = session
        ) ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun getAllItems(
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return simklIntegrationProvider.getAllItems(
            dateFrom = dateFrom,
            extended = extended,
            session = session
        ) ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun getPlayback(type: String, session: TrackingAuthSession? = null): Response<List<SimklPlaybackItemDto>> {
        return simklIntegrationProvider.getPlayback(type, session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun deletePlayback(playbackId: Long, session: TrackingAuthSession? = null): Response<Unit> {
        return simklIntegrationProvider.deletePlayback(playbackId, session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun addToList(
        body: SimklAddToListRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklAddToListResponseDto> {
        return simklIntegrationProvider.addToList(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun addHistory(body: SimklHistoryAddRequestDto, session: TrackingAuthSession? = null): Response<Unit> {
        return simklIntegrationProvider.addHistory(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun removeFromHistoryAndLists(
        body: SimklHistoryRemoveRequestDto,
        session: TrackingAuthSession? = null
    ): Response<Unit> {
        return simklIntegrationProvider.removeFromHistoryAndLists(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun scrobbleStart(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return simklIntegrationProvider.scrobbleStart(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun scrobblePause(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return simklIntegrationProvider.scrobblePause(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun scrobbleStop(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return simklIntegrationProvider.scrobbleStop(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

    suspend fun checkin(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return simklIntegrationProvider.checkin(body = body, session = session)
            ?: throw IllegalStateException("SIMKL authentication required")
    }

}

internal fun Response<okhttp3.ResponseBody>.toLibraryItemsResponse(
    moshi: Moshi
): Response<List<SimklLibraryItemDto>> {
    if (!isSuccessful) {
        return Response.error(code(), errorBody() ?: "".toResponseBody(null))
    }
    val rawPayload = body()?.string().orEmpty()
    return Response.success(parseSimklLibraryItemsPayload(rawPayload, moshi), raw())
}

internal fun parseSimklLibraryItemsPayload(
    rawPayload: String,
    moshi: Moshi = Moshi.Builder().add(KotlinJsonAdapterFactory()).build()
): List<SimklLibraryItemDto> {
    if (rawPayload.isBlank()) return emptyList()
    val reader = JsonReader.of(Buffer().writeUtf8(rawPayload))
    val itemAdapter = moshi.adapter(SimklLibraryItemDto::class.java)
    val listAdapter = moshi.adapter<List<SimklLibraryItemDto>>(
        Types.newParameterizedType(List::class.java, SimklLibraryItemDto::class.java)
    )

    fun readArray(arrayReader: JsonReader): List<SimklLibraryItemDto> {
        return listAdapter.fromJson(arrayReader).orEmpty()
    }

    fun readObject(objectReader: JsonReader): List<SimklLibraryItemDto> {
        val items = mutableListOf<SimklLibraryItemDto>()
        objectReader.beginObject()
        while (objectReader.hasNext()) {
            when (objectReader.nextName()) {
                "movies", "shows", "anime", "items", "results", "lists" -> {
                    if (objectReader.peek() == JsonReader.Token.BEGIN_ARRAY) {
                        items += readArray(objectReader)
                    } else {
                        objectReader.skipValue()
                    }
                }
                "data" -> {
                    when (objectReader.peek()) {
                        JsonReader.Token.BEGIN_ARRAY -> items += readArray(objectReader)
                        JsonReader.Token.BEGIN_OBJECT -> items += readObject(objectReader)
                        else -> objectReader.skipValue()
                    }
                }
                else -> objectReader.skipValue()
            }
        }
        objectReader.endObject()
        return items
    }

    return when (reader.peek()) {
        JsonReader.Token.BEGIN_ARRAY -> readArray(reader)
        JsonReader.Token.BEGIN_OBJECT -> readObject(reader)
        else -> itemAdapter.fromJson(reader)?.let(::listOf).orEmpty()
    }
}

package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.SimklApi
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
    private val simklApi: SimklApi,
    private val simklAuthService: SimklAuthService,
    private val moshi: Moshi
) {
    suspend fun getLastActivities(session: TrackingAuthSession? = null): Response<SimklLastActivitiesResponseDto> {
        return authorized(session) { authHeader ->
            simklApi.getLastActivities(authHeader)
        }
    }

    suspend fun getAllItemsByStatus(
        type: String,
        status: String,
        dateFrom: String? = null,
        extended: String? = null,
        episodeWatchedAt: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return authorized(session) { authHeader ->
            simklApi.getAllItemsByStatusRaw(
                authorization = authHeader,
                type = type,
                status = status,
                dateFrom = dateFrom,
                extended = extended,
                episodeWatchedAt = episodeWatchedAt
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getAllItemsByType(
        type: String,
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return authorized(session) { authHeader ->
            simklApi.getAllItemsByTypeRaw(
                authorization = authHeader,
                type = type,
                dateFrom = dateFrom,
                extended = extended
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getAllItems(
        dateFrom: String? = null,
        extended: String? = null,
        session: TrackingAuthSession? = null
    ): Response<List<SimklLibraryItemDto>> {
        return authorized(session) { authHeader ->
            simklApi.getAllItemsRaw(
                authorization = authHeader,
                dateFrom = dateFrom,
                extended = extended
            ).toLibraryItemsResponse(moshi)
        }
    }

    suspend fun getPlayback(type: String, session: TrackingAuthSession? = null): Response<List<SimklPlaybackItemDto>> {
        return authorized(session) { authHeader -> simklApi.getPlayback(authorization = authHeader, type = type) }
    }

    suspend fun deletePlayback(playbackId: Long, session: TrackingAuthSession? = null): Response<Unit> {
        return authorized(session) { authHeader -> simklApi.deletePlayback(authorization = authHeader, playbackId = playbackId) }
    }

    suspend fun addToList(
        body: SimklAddToListRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklAddToListResponseDto> {
        return authorizedWrite(session) { authHeader -> simklApi.addToList(authorization = authHeader, body = body) }
    }

    suspend fun addHistory(body: SimklHistoryAddRequestDto, session: TrackingAuthSession? = null): Response<Unit> {
        return authorizedWrite(session) { authHeader -> simklApi.addHistory(authorization = authHeader, body = body) }
    }

    suspend fun removeFromHistoryAndLists(
        body: SimklHistoryRemoveRequestDto,
        session: TrackingAuthSession? = null
    ): Response<Unit> {
        return authorizedWrite(session) { authHeader ->
            simklApi.removeFromHistoryAndLists(authorization = authHeader, body = body)
        }
    }

    suspend fun scrobbleStart(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return authorizedWrite(session) { authHeader -> simklApi.scrobbleStart(authorization = authHeader, body = body) }
    }

    suspend fun scrobblePause(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return authorizedWrite(session) { authHeader -> simklApi.scrobblePause(authorization = authHeader, body = body) }
    }

    suspend fun scrobbleStop(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return authorizedWrite(session) { authHeader -> simklApi.scrobbleStop(authorization = authHeader, body = body) }
    }

    suspend fun checkin(
        body: SimklScrobbleRequestDto,
        session: TrackingAuthSession? = null
    ): Response<SimklScrobbleResponseDto> {
        return authorizedWrite(session) { authHeader -> simklApi.checkin(authorization = authHeader, body = body) }
    }

    private suspend fun <T> authorized(
        session: TrackingAuthSession?,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T> {
        return if (session != null) {
            simklAuthService.executeAuthorizedRequest(session, call)
        } else {
            simklAuthService.executeAuthorizedRequest(call)
        } ?: throw IllegalStateException("SIMKL authentication required")
    }

    private suspend fun <T> authorizedWrite(
        session: TrackingAuthSession?,
        call: suspend (authorizationHeader: String) -> Response<T>
    ): Response<T> {
        return if (session != null) {
            simklAuthService.executeAuthorizedWriteRequest(session, call)
        } else {
            simklAuthService.executeAuthorizedWriteRequest(call)
        } ?: throw IllegalStateException("SIMKL authentication required")
    }
}

private fun Response<okhttp3.ResponseBody>.toLibraryItemsResponse(
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

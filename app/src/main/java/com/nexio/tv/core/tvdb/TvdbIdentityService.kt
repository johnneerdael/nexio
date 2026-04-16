package com.nexio.tv.core.tvdb

import android.util.Log
import com.nexio.tv.data.local.TvdbIdentityCacheStore
import com.nexio.tv.data.remote.api.TvdbApi
import com.nexio.tv.data.remote.api.TvdbRemoteId as ApiTvdbRemoteId
import com.nexio.tv.data.remote.api.TvdbSearchResult
import com.nexio.tv.data.remote.api.TvdbSeriesBaseRecord
import com.nexio.tv.data.remote.api.TvdbSeriesExtendedRecord
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "TvdbIdentity"

@Singleton
class TvdbIdentityService @Inject constructor(
    private val tvdbApi: TvdbApi,
    private val authService: TvdbAuthService,
    private val identityCacheStore: TvdbIdentityCacheStore? = null
) {
    private val inFlightLookups = ConcurrentHashMap<String, CompletableDeferred<TvdbSeriesIdentity?>>()

    suspend fun resolveSeriesByTvdbId(tvdbId: Int): TvdbSeriesIdentity? = withContext(Dispatchers.IO) {
        if (tvdbId <= 0) return@withContext null

        identityCacheStore?.read(TvdbRemoteIdSource.TVDB, tvdbId.toString())?.let { return@withContext it }

        val authorization = authService.bearerToken() ?: run {
            Log.w(TAG, "TVDB identity lookup failed reason=auth_unavailable")
            return@withContext null
        }

        fetchSeriesIdentity(authorization = authorization, tvdbId = tvdbId)?.also { identity ->
            identityCacheStore?.write(TvdbRemoteIdSource.TVDB, tvdbId.toString(), identity)
        }
    }

    suspend fun resolveSeriesByRemoteId(
        remoteId: String,
        source: TvdbRemoteIdSource
    ): TvdbSeriesIdentity? = withContext(Dispatchers.IO) {
        val normalizedValue = normalizeTvdbRemoteIdValue(remoteId, source)
        if (normalizedValue.isBlank()) return@withContext null

        identityCacheStore?.read(source, normalizedValue)?.let { return@withContext it }

        val inFlightKey = "$source:$normalizedValue"
        inFlightLookups[inFlightKey]?.let { return@withContext it.await() }

        val deferred = CompletableDeferred<TvdbSeriesIdentity?>()
        val existingDeferred = inFlightLookups.putIfAbsent(inFlightKey, deferred)
        if (existingDeferred != null) {
            return@withContext existingDeferred.await()
        }

        try {
            val authorization = authService.bearerToken() ?: run {
                Log.w(TAG, "TVDB identity lookup failed reason=auth_unavailable")
                deferred.complete(null)
                return@withContext null
            }

            val identity = searchRemoteId(authorization, normalizedValue)
                ?: searchSeriesRemoteIdFallback(authorization, normalizedValue)

            if (identity != null) {
                identityCacheStore?.write(source, normalizedValue, identity)
            }
            deferred.complete(identity)
            identity
        } catch (error: Exception) {
            Log.w(TAG, "TVDB identity lookup failed reason=${error.javaClass.simpleName} message=${error.message}")
            deferred.complete(null)
            null
        } finally {
            inFlightLookups.remove(inFlightKey, deferred)
        }
    }

    private suspend fun searchRemoteId(
        authorization: String,
        remoteId: String
    ): TvdbSeriesIdentity? {
        val response = tvdbApi.searchByRemoteId(authorization, remoteId)
        if (!response.isSuccessful) {
            Log.w(TAG, "TVDB remote ID lookup failed status=${response.code()} reason=http-${response.code()}")
            return null
        }

        val series = response.body()
            ?.data
            .orEmpty()
            .firstOrNull { result -> result.series != null }
            ?.series
            ?: return null

        return series.id?.let { tvdbId ->
            fetchSeriesIdentity(authorization = authorization, tvdbId = tvdbId, baseRecord = series)
        }
    }

    private suspend fun searchSeriesRemoteIdFallback(
        authorization: String,
        remoteId: String
    ): TvdbSeriesIdentity? {
        val response = tvdbApi.search(
            authorization = authorization,
            remoteId = remoteId,
            type = "series"
        )
        if (!response.isSuccessful) {
            Log.w(TAG, "TVDB search fallback failed status=${response.code()} reason=http-${response.code()}")
            return null
        }

        return response.body()
            ?.data
            .orEmpty()
            .firstNotNullOfOrNull { result ->
                result.toSeriesIdentity()
            }
    }

    private suspend fun fetchSeriesIdentity(
        authorization: String,
        tvdbId: Int,
        baseRecord: TvdbSeriesBaseRecord? = null
    ): TvdbSeriesIdentity? {
        val extendedResponse = tvdbApi.getSeriesExtended(authorization, tvdbId)
        if (extendedResponse.isSuccessful) {
            extendedResponse.body()?.data?.toSeriesIdentity()?.let { return it }
        } else {
            Log.w(TAG, "TVDB series extended lookup failed status=${extendedResponse.code()} reason=http-${extendedResponse.code()}")
        }

        val base = baseRecord ?: run {
            val baseResponse = tvdbApi.getSeriesBase(authorization, tvdbId)
            if (!baseResponse.isSuccessful) {
                Log.w(TAG, "TVDB series base lookup failed status=${baseResponse.code()} reason=http-${baseResponse.code()}")
                return null
            }
            baseResponse.body()?.data
        }

        return base?.toSeriesIdentity()
    }

    private fun TvdbSeriesExtendedRecord.toSeriesIdentity(): TvdbSeriesIdentity? {
        val id = id ?: return null
        return TvdbSeriesIdentity(
            tvdbId = id,
            name = name,
            year = firstAired?.take(4),
            remoteIds = remoteIds.orEmpty().toRemoteIdMap(id)
        )
    }

    private fun TvdbSeriesBaseRecord.toSeriesIdentity(): TvdbSeriesIdentity? {
        val id = id ?: return null
        return TvdbSeriesIdentity(
            tvdbId = id,
            name = name,
            year = firstAired?.take(4),
            remoteIds = mapOf(TvdbRemoteIdSource.TVDB to setOf(id.toString()))
        )
    }

    private fun TvdbSearchResult.toSeriesIdentity(): TvdbSeriesIdentity? {
        if (type != null && !type.equals("series", ignoreCase = true)) return null
        val id = parsedTvdbId() ?: return null
        return TvdbSeriesIdentity(
            tvdbId = id,
            name = name ?: title,
            year = year ?: firstAirTime?.take(4),
            remoteIds = remoteIds.orEmpty().toRemoteIdMap(id)
        )
    }

    private fun TvdbSearchResult.parsedTvdbId(): Int? {
        tvdbId?.toIntOrNull()?.let { return it }
        id?.toIntOrNull()?.let { return it }
        return id
            ?.removePrefix("series-")
            ?.toIntOrNull()
    }

    private fun List<ApiTvdbRemoteId>.toRemoteIdMap(tvdbId: Int): Map<TvdbRemoteIdSource, Set<String>> {
        val grouped = linkedMapOf<TvdbRemoteIdSource, MutableSet<String>>(
            TvdbRemoteIdSource.TVDB to linkedSetOf(tvdbId.toString())
        )

        forEach { remoteId ->
            val value = remoteId.id?.trim().orEmpty()
            if (value.isBlank()) return@forEach

            val source = normalizeTvdbRemoteIdSource(remoteId.sourceName)
            val normalizedValue = normalizeTvdbRemoteIdValue(value, source)
            if (normalizedValue.isBlank()) return@forEach

            grouped.getOrPut(source) { linkedSetOf() }.add(normalizedValue)
        }

        return grouped.mapValues { (_, values) -> values.toSet() }
    }
}

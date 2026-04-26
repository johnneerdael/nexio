package com.nexio.tv.core.tmdb

import com.nexio.tv.data.integration.tmdb.TmdbIntegrationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbPosterLookupService @Inject constructor(
    private val tmdbIntegrationProvider: TmdbIntegrationProvider
) {
    private val cache = ConcurrentHashMap<String, CacheLookupResult>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    suspend fun lookupPosterUrl(tconst: String, titleType: String, size: String = POSTER_SIZE): String? {
        if (!tconst.startsWith("tt")) return null
        cache[tconst]?.let { result ->
            return when (result) {
                is CacheLookupResult.Miss -> null
                is CacheLookupResult.Path -> absolutize(result.path, size)
            }
        }

        inFlight[tconst]?.let { return absolutize(it.await(), size) }

        val deferred = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(tconst, deferred)
        if (existing != null) return absolutize(existing.await(), size)

        return try {
            var cacheableResult = true
            val deferredResult: String? = try {
                withContext(Dispatchers.IO) { fetchPosterPath(tconst, titleType) }
            } catch (e: CancellationException) {
                deferred.completeExceptionally(e)
                throw e
            } catch (_: Exception) {
                cacheableResult = false
                null
            }
            if (cacheableResult) {
                cache[tconst] = if (deferredResult == null) {
                    CacheLookupResult.Miss
                } else {
                    CacheLookupResult.Path(deferredResult)
                }
            }
            if (!deferred.isCompleted) {
                deferred.complete(deferredResult)
            }
            absolutize(deferredResult, size)
        } finally {
            inFlight.remove(tconst, deferred)
        }
    }

    private suspend fun fetchPosterPath(tconst: String, titleType: String): String? {
        val body = tmdbIntegrationProvider.findByExternalId(tconst) ?: return null
        val preferred = if (titleType.equals("movie", ignoreCase = true)) {
            body.movieResults?.firstOrNull()?.posterPath
        } else {
            body.tvResults?.firstOrNull()?.posterPath
        }
        return preferred
            ?: body.movieResults?.firstOrNull()?.posterPath
            ?: body.tvResults?.firstOrNull()?.posterPath
    }

    private fun absolutize(path: String?, size: String): String? {
        val clean = path?.trim()?.takeIf { it.isNotBlank() } ?: return null
        return "https://image.tmdb.org/t/p/$size$clean"
    }

    companion object {
        const val POSTER_SIZE = "w154"
    }
}

private sealed interface CacheLookupResult {
    data class Path(val path: String) : CacheLookupResult
    object Miss : CacheLookupResult
}

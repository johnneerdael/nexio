package com.nexio.tv.core.tmdb

import com.nexio.tv.core.metadata.MetadataApiKeyResolver
import com.nexio.tv.data.remote.api.TmdbApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImdbPosterLookupService @Inject constructor(
    private val tmdbApi: TmdbApi,
    private val metadataApiKeyResolver: MetadataApiKeyResolver
) {
    private val cache = ConcurrentHashMap<String, String?>()
    private val inFlight = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    suspend fun lookupPosterUrl(tconst: String, titleType: String, size: String = POSTER_SIZE): String? {
        if (!tconst.startsWith("tt")) return null
        cache[tconst]?.let { return absolutize(it, size) }

        inFlight[tconst]?.let { return absolutize(it.await(), size) }

        val deferred = CompletableDeferred<String?>()
        val existing = inFlight.putIfAbsent(tconst, deferred)
        if (existing != null) return absolutize(existing.await(), size)

        val path: String? = try {
            withContext(Dispatchers.IO) { fetchPosterPath(tconst, titleType) }
        } catch (_: Exception) {
            null
        }
        cache[tconst] = path
        deferred.complete(path)
        inFlight.remove(tconst, deferred)
        return absolutize(path, size)
    }

    private suspend fun fetchPosterPath(tconst: String, titleType: String): String? {
        val credential = metadataApiKeyResolver.tmdbCredential()
        if (credential.missing) return null
        val response = tmdbApi.findByExternalId(
            externalId = tconst,
            apiKey = credential.apiKey,
            externalSource = "imdb_id"
        )
        if (!response.isSuccessful) return null
        val body = response.body() ?: return null
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

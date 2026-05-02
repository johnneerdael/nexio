package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.data.integration.addon.AddonSubtitleIntegrationProvider
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieResultMapper
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieSourceRouter
import com.nexio.tv.data.integration.subtitles.wyzie.WyzieSubtitleIntegrationProvider
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Subtitle
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.repository.SubtitleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import kotlin.coroutines.cancellation.CancellationException

class SubtitleRepositoryImpl @Inject constructor(
    private val addonSubtitleIntegrationProvider: AddonSubtitleIntegrationProvider,
    private val addonRepository: AddonRepositoryImpl,
    private val wyzieSubtitleIntegrationProvider: WyzieSubtitleIntegrationProvider,
) : SubtitleRepository {

    companion object {
        private const val TAG = "SubtitleRepository"
        private const val PER_ADDON_TIMEOUT_MS = 8_000L
        private const val WYZIE_TIMEOUT_MS = 8_000L
    }

    override suspend fun getSubtitles(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
        wyzieHints: WyzieIdHints,
        season: Int?,
        episode: Int?,
    ): List<Subtitle> = withContext(Dispatchers.IO) {
        val requestType = canonicalSubtitleType(type)
        val requestId = if (requestType == "series" && videoId != null) videoId else id
        val startedAtMs = System.currentTimeMillis()
        Log.d(TAG, "Fetching subtitles for type=$requestType, id=$requestId, videoId=$videoId")

        coroutineScope {
            val addonDeferred = async {
                runCatching { fetchAddonLane(type, id, videoId, videoHash, videoSize, filename) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Addon lane failed", e)
                        emptyList()
                    }
            }
            val wyzieDeferred = async {
                runCatching { fetchWyzieLane(type, wyzieHints, season, episode) }
                    .getOrElse { e ->
                        if (e is CancellationException) throw e
                        Log.e(TAG, "Wyzie lane failed", e)
                        emptyList()
                    }
            }
            val merged = listOf(addonDeferred, wyzieDeferred).awaitAll().flatten()
            Log.d(
                TAG,
                "Subtitle fetch completed total=${merged.size} in ${System.currentTimeMillis() - startedAtMs}ms",
            )
            merged
        }
    }

    private suspend fun fetchAddonLane(
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
    ): List<Subtitle> {
        val addons = try {
            addonRepository.getInstalledAddons().first()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Failed to get installed addons", e)
            return emptyList()
        }
        val requestType = canonicalSubtitleType(type)
        val requestId = if (requestType == "series" && videoId != null) videoId else id

        val subtitleAddons = addons.filter { addon ->
            addon.resources.any { resource ->
                isSubtitleResource(resource.name) && supportsType(resource, requestType, requestId)
            }
        }
        if (subtitleAddons.isEmpty()) return emptyList()

        return coroutineScope {
            subtitleAddons.map { addon ->
                async {
                    val started = System.currentTimeMillis()
                    val result = withTimeoutOrNull(PER_ADDON_TIMEOUT_MS) {
                        fetchSubtitlesFromAddon(addon, type, id, videoId, videoHash, videoSize, filename)
                    }
                    if (result == null) {
                        Log.w(TAG, "Subtitle fetch timed out for addon=${addon.name}")
                        emptyList()
                    } else {
                        Log.d(TAG, "Addon subs ok addon=${addon.name} count=${result.size} ms=${System.currentTimeMillis() - started}")
                        result
                    }
                }
            }.awaitAll().flatten()
        }
    }

    private suspend fun fetchWyzieLane(
        type: String,
        hints: WyzieIdHints,
        season: Int?,
        episode: Int?,
    ): List<Subtitle> {
        if (hints == WyzieIdHints.EMPTY) return emptyList()
        val contentType = ContentType.fromString(type)
        val sources = WyzieSourceRouter.sourcesFor(contentType, hints)
        if (sources.isEmpty()) return emptyList()

        val dtos = withTimeoutOrNull(WYZIE_TIMEOUT_MS) {
            wyzieSubtitleIntegrationProvider.search(contentType, hints, sources, season, episode)
        } ?: run {
            Log.w(TAG, "WYZIE_SUBS timed out")
            emptyList()
        }
        return WyzieResultMapper.mapAll(dtos)
    }

    private fun canonicalSubtitleType(type: String): String =
        if (type.equals("tv", ignoreCase = true)) "series" else type.lowercase()

    private fun supportsType(
        resource: com.nexio.tv.domain.model.AddonResource,
        type: String,
        id: String,
    ): Boolean {
        if (resource.types.isNotEmpty() && resource.types.none { it.equals(type, ignoreCase = true) }) {
            return false
        }
        val idPrefixes = resource.idPrefixes
        if (idPrefixes != null && idPrefixes.isNotEmpty()) {
            return idPrefixes.any { prefix -> id.startsWith(prefix) }
        }
        return true
    }

    private fun isSubtitleResource(name: String): Boolean =
        name.equals("subtitles", ignoreCase = true) || name.equals("subtitle", ignoreCase = true)

    private suspend fun fetchSubtitlesFromAddon(
        addon: Addon,
        type: String,
        id: String,
        videoId: String?,
        videoHash: String?,
        videoSize: Long?,
        filename: String?,
    ): List<Subtitle> {
        val normalizedType = canonicalSubtitleType(type)
        val actualId = if (normalizedType == "series" && videoId != null) videoId else id
        val extraParams = buildExtraParams(videoHash, videoSize, filename)
        val subtitleUrl = if (extraParams.isNotEmpty()) {
            buildAddonRequestUrl(addon.baseUrl, "subtitles/$normalizedType/$actualId/$extraParams.json")
        } else {
            buildAddonRequestUrl(addon.baseUrl, "subtitles/$normalizedType/$actualId.json")
        }
        Log.d(TAG, "Fetching subtitles from ${addon.name}: ${sanitizeUrlForLogs(subtitleUrl)}")

        return try {
            when (val result = addonSubtitleIntegrationProvider.getSubtitles(addon, subtitleUrl)) {
                is IntegrationCallResult.Success -> result.value.subtitles?.mapNotNull { dto ->
                    Subtitle(
                        id = dto.id ?: "${dto.lang}-${dto.url.hashCode()}",
                        url = dto.url,
                        lang = dto.lang,
                        addonName = addon.displayName,
                        addonLogo = addon.logo,
                    )
                } ?: emptyList()
                is IntegrationCallResult.HttpError -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}: http=${result.statusCode} reason=${result.reason}")
                    emptyList()
                }
                is IntegrationCallResult.NetworkError -> {
                    Log.e(TAG, "Failed to fetch subtitles from ${addon.name}", result.throwable)
                    emptyList()
                }
                IntegrationCallResult.Missing -> emptyList()
            }
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Log.e(TAG, "Exception fetching subtitles from ${addon.name}", e)
            emptyList()
        }
    }

    private fun buildExtraParams(videoHash: String?, videoSize: Long?, filename: String?): String {
        val params = mutableListOf<String>()
        videoHash?.let { params.add("videoHash=$it") }
        videoSize?.let { params.add("videoSize=$it") }
        filename?.let { params.add("filename=$it") }
        return if (params.isNotEmpty()) params.joinToString("&") else ""
    }
}

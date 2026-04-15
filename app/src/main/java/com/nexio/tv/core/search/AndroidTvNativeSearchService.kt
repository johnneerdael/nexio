package com.nexio.tv.core.search

import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.CatalogDescriptor
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.skipStep
import com.nexio.tv.domain.model.supportsExtra
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.CatalogRepository
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

private const val CINEMETA_BASE_URL = "https://v3-cinemeta.strem.io"
private const val DEFAULT_NATIVE_SEARCH_TIMEOUT_MS = 750L
private const val MIN_NATIVE_SEARCH_QUERY_LENGTH = 2
private const val DEFAULT_NATIVE_SEARCH_LIMIT = 20

@Singleton
class AndroidTvNativeSearchService(
    private val addonRepository: AddonRepository,
    private val catalogRepository: CatalogRepository,
    private val localSearchCorpus: AndroidTvLocalSearchCorpus? = null,
    private val timeoutMs: Long = DEFAULT_NATIVE_SEARCH_TIMEOUT_MS
) {
    @Inject
    constructor(
        addonRepository: AddonRepository,
        catalogRepository: CatalogRepository,
        localSearchCorpus: AndroidTvLocalSearchCorpus
    ) : this(
        addonRepository = addonRepository,
        catalogRepository = catalogRepository,
        localSearchCorpus = localSearchCorpus,
        timeoutMs = DEFAULT_NATIVE_SEARCH_TIMEOUT_MS
    )

    suspend fun search(
        query: String,
        limit: Int = DEFAULT_NATIVE_SEARCH_LIMIT
    ): List<AndroidTvNativeSearchResult> {
        val normalizedQuery = query.trim()
        if (normalizedQuery.length < MIN_NATIVE_SEARCH_QUERY_LENGTH) return emptyList()

        return withTimeoutOrNull(timeoutMs) {
            runCatching {
                searchLocalThenCinemeta(normalizedQuery, limit.coerceAtLeast(1))
            }.getOrElse { emptyList() }
        }.orEmpty()
    }

    private suspend fun searchLocalThenCinemeta(
        query: String,
        limit: Int
    ): List<AndroidTvNativeSearchResult> {
        val localScored = score(query, localSearchCorpus?.candidates().orEmpty(), limit)
        if (localScored.any(AndroidTvSearchCandidateScorer::isStrongLocal)) {
            return localScored.toResults()
        }

        val liveCandidates = searchCinemetaCandidates(query)
        return score(query, localScored.map { it.candidate } + liveCandidates, limit)
            .toResults()
    }

    private suspend fun searchCinemeta(
        query: String,
        limit: Int
    ): List<AndroidTvNativeSearchResult> = score(query, searchCinemetaCandidates(query), limit).toResults()

    private suspend fun searchCinemetaCandidates(
        query: String
    ): List<AndroidTvSearchCandidate> {
        val source = resolveCinemetaSource() ?: return emptyList()
        val targets = source.searchTargets()
        if (targets.isEmpty()) return emptyList()

        val rows = coroutineScope {
            targets.map { target ->
                async { fetchSearchRow(source.addon, target, query) }
            }.mapNotNull { deferred ->
                runCatching { deferred.await() }.getOrNull()
            }
        }

        return rows
            .flatMap { row -> row.items.mapNotNull { item -> item.toSearchCandidate(row.addonBaseUrl) } }
            .distinctBy { result -> result.identityKey }
    }

    private suspend fun resolveCinemetaSource(): CinemetaSource? {
        val cachedAddons = runCatching { addonRepository.getCachedInstalledAddons() }.getOrDefault(emptyList())
        cachedAddons.findCinemetaAddon()?.let { return CinemetaSource(it) }

        val installedAddons = runCatching { addonRepository.getInstalledAddons().first() }.getOrDefault(emptyList())
        installedAddons.findCinemetaAddon()?.let { return CinemetaSource(it) }

        return when (val fetched = runCatching { addonRepository.fetchAddon(CINEMETA_BASE_URL) }.getOrNull()) {
            is NetworkResult.Success -> CinemetaSource(fetched.data)
            else -> null
        }
    }

    private suspend fun fetchSearchRow(
        addon: Addon,
        catalog: CatalogDescriptor,
        query: String
    ): CatalogRow? {
        val result = catalogRepository.getCatalog(
            addonBaseUrl = addon.baseUrl,
            addonId = addon.id,
            addonName = addon.displayName,
            catalogId = catalog.id,
            catalogName = catalog.name,
            type = catalog.apiType,
            skip = 0,
            skipStep = catalog.skipStep(),
            extraArgs = mapOf("search" to query),
            supportsSkip = catalog.supportsExtra("skip")
        ).first { networkResult -> networkResult !is NetworkResult.Loading }

        return when (result) {
            is NetworkResult.Success -> result.data
            else -> null
        }
    }

    private data class CinemetaSource(val addon: Addon) {
        fun searchTargets(): List<CatalogDescriptor> {
            return addon.catalogs.filter { catalog ->
                catalog.apiType.lowercase() in setOf("movie", "series") &&
                    catalog.supportsExtra("search")
            }
        }
    }

    private fun List<Addon>.findCinemetaAddon(): Addon? {
        return firstOrNull { addon ->
            addon.baseUrl.trimEnd('/').equals(CINEMETA_BASE_URL, ignoreCase = true)
        }
    }

    private fun score(
        query: String,
        candidates: List<AndroidTvSearchCandidate>,
        limit: Int
    ): List<AndroidTvSearchScoredCandidate> {
        return AndroidTvSearchCandidateScorer.score(query, candidates, limit)
    }

    private fun List<AndroidTvSearchScoredCandidate>.toResults(): List<AndroidTvNativeSearchResult> {
        return map { scored ->
            scored.candidate.toNativeSearchResult(
                score = scored.score,
                explanation = scored.explanation
            )
        }
    }

    private fun MetaPreview.toSearchCandidate(addonBaseUrl: String?): AndroidTvSearchCandidate? {
        val id = id.trim()
        val title = name.trim()
        val contentType = apiType.trim()
        if (id.isEmpty() || title.isEmpty() || contentType.isEmpty()) return null
        return AndroidTvSearchCandidate(
            id = id,
            contentType = contentType,
            title = title,
            poster = poster,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            runtime = runtime,
            addonBaseUrl = addonBaseUrl,
            source = AndroidTvSearchCandidateSource.LIVE_CINEMETA
        )
    }

    private fun AndroidTvSearchCandidate.toNativeSearchResult(
        score: Int,
        explanation: String
    ): AndroidTvNativeSearchResult {
        return AndroidTvNativeSearchResult(
            id = id,
            contentType = contentType,
            title = title,
            poster = poster,
            background = background,
            description = description,
            releaseInfo = releaseInfo,
            runtime = runtime,
            addonBaseUrl = addonBaseUrl,
            source = source,
            score = score,
            matchExplanation = explanation
        )
    }
}

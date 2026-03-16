package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.network.safeApiCall
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.data.local.CatalogDiskCacheStore
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.data.remote.api.AddonApi
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.repository.CatalogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class CatalogRepositoryImpl @Inject constructor(
    private val api: AddonApi,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val catalogDiskCacheStore: CatalogDiskCacheStore
) : CatalogRepository {
    companion object {
        private const val TAG = "CatalogRepository"
    }

    private val catalogCache = ConcurrentHashMap<String, CatalogRow>()

    override fun getCatalog(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): Flow<NetworkResult<CatalogRow>> = getCatalogCachedFirst(
        addonBaseUrl = addonBaseUrl,
        addonId = addonId,
        addonName = addonName,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        skip = skip,
        skipStep = skipStep,
        extraArgs = extraArgs,
        supportsSkip = supportsSkip,
        allowNetworkRefresh = true
    )

    override fun getCatalogCachedFirst(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        allowNetworkRefresh: Boolean
    ): Flow<NetworkResult<CatalogRow>> = flow {
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerCacheToken = if (activePosterProvider == null) {
            "native"
        } else {
            "${activePosterProvider.provider.name}:${activePosterProvider.apiKey.hashCode()}"
        }
        val cacheKey = buildCacheKey(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs,
            providerCacheToken = providerCacheToken
        )

        val memoryCached = catalogCache[cacheKey]
        val diskCached = if (memoryCached == null) {
            catalogDiskCacheStore.read(cacheKey)?.catalogRow?.also { catalogCache[cacheKey] = it }
        } else {
            null
        }
        val cached = memoryCached ?: diskCached
        cached?.let { emit(NetworkResult.Success(it)) } ?: emit(NetworkResult.Loading)

        if (!allowNetworkRefresh) {
            if (cached == null) {
                emit(NetworkResult.Error("Catalog refresh deferred by startup gate"))
            }
            return@flow
        }

        val refreshed = fetchCatalogFromNetwork(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            addonName = addonName,
            catalogId = catalogId,
            catalogName = catalogName,
            type = type,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs,
            supportsSkip = supportsSkip,
            activePosterProvider = activePosterProvider
        )

        when (refreshed) {
            is Result.Success -> {
                val catalogRow = refreshed.row
                catalogCache[cacheKey] = catalogRow
                catalogDiskCacheStore.write(
                    cacheKey = cacheKey,
                    row = catalogRow,
                    catalogVersionHash = buildCatalogVersionHash(catalogRow)
                )
                if (cached == null || cached.items != catalogRow.items) emit(NetworkResult.Success(catalogRow))
            }

            is Result.Failure -> {
                val result = refreshed.error
                Log.w(
                    TAG,
                    "Catalog fetch failed addonId=$addonId type=$type catalogId=$catalogId code=${result.code} message=${result.message}"
                )
                if (cached == null) emit(result)
            }
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun refreshCatalogToDisk(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean
    ): kotlin.Result<CatalogRow> {
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerCacheToken = if (activePosterProvider == null) {
            "native"
        } else {
            "${activePosterProvider.provider.name}:${activePosterProvider.apiKey.hashCode()}"
        }
        val cacheKey = buildCacheKey(
            addonBaseUrl = addonBaseUrl,
            addonId = addonId,
            type = type,
            catalogId = catalogId,
            skip = skip,
            skipStep = skipStep,
            extraArgs = extraArgs,
            providerCacheToken = providerCacheToken
        )

        return when (
            val refreshed = fetchCatalogFromNetwork(
                addonBaseUrl = addonBaseUrl,
                addonId = addonId,
                addonName = addonName,
                catalogId = catalogId,
                catalogName = catalogName,
                type = type,
                skip = skip,
                skipStep = skipStep,
                extraArgs = extraArgs,
                supportsSkip = supportsSkip,
                activePosterProvider = activePosterProvider
            )
        ) {
            is Result.Success -> {
                catalogCache[cacheKey] = refreshed.row
                catalogDiskCacheStore.write(
                    cacheKey = cacheKey,
                    row = refreshed.row,
                    catalogVersionHash = buildCatalogVersionHash(refreshed.row)
                )
                kotlin.Result.success(refreshed.row)
            }

            is Result.Failure -> kotlin.Result.failure(
                IllegalStateException(refreshed.error.message ?: "Catalog refresh failed")
            )
        }
    }

    private fun buildCatalogUrl(
        baseUrl: String,
        type: String,
        catalogId: String,
        skip: Int,
        extraArgs: Map<String, String>
    ): String {
        if (extraArgs.isEmpty()) {
            val relativePath = if (skip > 0) {
                "catalog/$type/$catalogId/skip=$skip.json"
            } else {
                "catalog/$type/$catalogId.json"
            }
            return buildAddonRequestUrl(baseUrl, relativePath)
        }

        val allArgs = LinkedHashMap<String, String>()
        allArgs.putAll(extraArgs)

        // For Stremio catalogs, pagination is controlled by `skip` inside extraArgs.
        if (!allArgs.containsKey("skip") && skip > 0) {
            allArgs["skip"] = skip.toString()
        }

        val encodedArgs = allArgs.entries.joinToString("&") { (key, value) ->
            "${encodeArg(key)}=${encodeArg(value)}"
        }

        return buildAddonRequestUrl(baseUrl, "catalog/$type/$catalogId/$encodedArgs.json")
    }

    private fun encodeArg(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }

    private fun buildCacheKey(
        addonBaseUrl: String,
        addonId: String,
        type: String,
        catalogId: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        providerCacheToken: String
    ): String {
        val normalizedArgs = extraArgs.entries
            .sortedBy { it.key }
            .joinToString("&") { "${it.key}=${it.value}" }
        val normalizedBaseUrl = addonBaseUrl.trim().trimEnd('/').lowercase()
        return "${normalizedBaseUrl}_${addonId}_${type}_${catalogId}_${skip}_${skipStep}_${normalizedArgs}_${providerCacheToken}"
    }

    override fun clearCache() {
        catalogCache.clear()
    }

    private suspend fun fetchCatalogFromNetwork(
        addonBaseUrl: String,
        addonId: String,
        addonName: String,
        catalogId: String,
        catalogName: String,
        type: String,
        skip: Int,
        skipStep: Int,
        extraArgs: Map<String, String>,
        supportsSkip: Boolean,
        activePosterProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): Result {
        val url = buildCatalogUrl(addonBaseUrl, type, catalogId, skip, extraArgs)
        Log.d(
            TAG,
            "Fetching catalog addonId=$addonId addonName=$addonName type=$type catalogId=$catalogId skip=$skip skipStep=$skipStep supportsSkip=$supportsSkip url=${sanitizeUrlForLogs(url)}"
        )
        return when (val result = safeApiCall { api.getCatalog(url) }) {
            is NetworkResult.Success -> {
                val items = result.data.metas.map { meta ->
                    posterRatingsUrlResolver.apply(meta.toDomain(), activePosterProvider)
                }
                Log.d(
                    TAG,
                    "Catalog fetch success addonId=$addonId type=$type catalogId=$catalogId items=${items.size}"
                )
                Result.Success(
                    CatalogRow(
                        addonId = addonId,
                        addonName = addonName,
                        addonBaseUrl = addonBaseUrl,
                        catalogId = catalogId,
                        catalogName = catalogName,
                        type = ContentType.fromString(type),
                        rawType = type,
                        items = items,
                        isLoading = false,
                        hasMore = supportsSkip && items.isNotEmpty(),
                        currentPage = if (skipStep > 0) skip / skipStep else 0,
                        supportsSkip = supportsSkip,
                        skipStep = skipStep
                    )
                )
            }
            is NetworkResult.Error -> Result.Failure(result)
            NetworkResult.Loading -> Result.Failure(NetworkResult.Error("Catalog refresh unresolved"))
        }
    }

    private fun buildCatalogVersionHash(row: CatalogRow): String {
        return buildString {
            append(row.addonId)
            append('|')
            append(row.apiType)
            append('|')
            append(row.catalogId)
            append('|')
            append(row.items.size)
            row.items.forEach { item ->
                append('|')
                append(item.apiType)
                append(':')
                append(item.id)
                append(':')
                append(item.poster.orEmpty())
                append(':')
                append(item.background.orEmpty())
            }
        }.hashCode().toString()
    }

    private sealed interface Result {
        data class Success(val row: CatalogRow) : Result
        data class Failure(val error: NetworkResult.Error) : Result
    }
}

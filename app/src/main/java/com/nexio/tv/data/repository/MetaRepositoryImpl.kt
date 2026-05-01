package com.nexio.tv.data.repository

import android.content.Context
import android.util.Log
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.sync.buildAddonRequestUrl
import com.nexio.tv.core.logging.sanitizeUrlForLogs
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.integration.addon.AddonMetaIntegrationProvider
import com.nexio.tv.data.local.MetadataDiskCacheStore
import com.nexio.tv.data.mapper.toDomain
import com.nexio.tv.domain.model.Addon
import com.nexio.tv.domain.model.AddonResource
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MetaRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val addonMetaIntegrationProvider: AddonMetaIntegrationProvider,
    private val addonRepository: AddonRepository,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val metadataDiskCacheStore: MetadataDiskCacheStore
) : MetaRepository {
    companion object {
        private const val TAG = "MetaRepository"
    }

    // In-memory cache: "type:id" -> Meta
    private val metaCache = ConcurrentHashMap<String, Meta>()
    // Separate cache for full meta fetched from addons (bypasses catalog-level cache)
    private val addonMetaCache = ConcurrentHashMap<String, Meta>()

    override fun getMeta(
        addonBaseUrl: String,
        type: String,
        id: String,
        cacheOnDisk: Boolean,
        writeToDisk: Boolean,
        origin: String
    ): Flow<NetworkResult<Meta>> = flow {
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activePosterProvider)
        val typeCandidates = buildMetaTypeCandidates(type, id)
        val idCandidates = buildMetaIdCandidates(id)
        val primaryType = typeCandidates.firstOrNull() ?: type.trim()
        val primaryId = idCandidates.firstOrNull() ?: id.trim()
        val cacheKey = "$primaryType:$primaryId:$providerToken"
        metaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
        val itemKeys = buildMetaItemKeys(typeCandidates, idCandidates, type, id)
        if (cacheOnDisk) {
            readCachedMeta(itemKeys, languageTag, providerToken)?.let { cached ->
                val safeCached = cached.sanitizeCastMembers()
                metaCache[cacheKey] = safeCached
                Log.d(TAG, "Meta disk cache hit origin=$origin itemKey=${itemKeys.firstOrNull().orEmpty()}")
                emit(NetworkResult.Success(safeCached))
                return@flow
            }
        }

        emit(NetworkResult.Loading)

        for (candidateType in typeCandidates) {
            for (candidateId in idCandidates) {
                val url = buildMetaUrl(addonBaseUrl, candidateType, candidateId)
                when (
                    val result = addonMetaIntegrationProvider.getMeta(
                        addonId = addonBaseUrl.trim().trimEnd('/'),
                        metaUrl = url
                    )
                ) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = posterRatingsUrlResolver
                                .apply(metaDto.toDomain(episodeLabel), activePosterProvider)
                                .sanitizeCastMembers()
                            metaCache[cacheKey] = meta
                            if (cacheOnDisk && writeToDisk) {
                                writeMetaDiskAliases(
                                    candidateType = candidateType,
                                    candidateId = candidateId,
                                    languageTag = languageTag,
                                    providerToken = providerToken,
                                    meta = meta
                                )
                            } else {
                                Log.d(TAG, "Meta disk cache bypassed origin=$origin itemKey=$candidateType:$candidateId")
                            }
                            emit(NetworkResult.Success(meta))
                            return@flow
                        }
                    }
                    is NetworkResult.Error -> {
                        Log.w(
                            TAG,
                            "Meta fetch failed type=$candidateType id=$candidateId code=${result.code} message=${result.message}"
                        )
                    }
                    NetworkResult.Loading -> { /* Already emitted */ }
                }
            }
        }
        emit(NetworkResult.Error("Meta not found"))
    }

    override fun hydrateAddonOriginItem(
        addon: Addon,
        type: String,
        id: String,
        cacheOnDisk: Boolean,
        writeToDisk: Boolean,
        origin: String
    ): Flow<NetworkResult<Meta>> = flow {
        val activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val providerToken = posterProviderCacheToken(activePosterProvider)
        val typeCandidates = buildMetaTypeCandidates(type, id)
        val idCandidates = buildMetaIdCandidates(id)
        val primaryType = typeCandidates.firstOrNull() ?: type.trim()
        val primaryId = idCandidates.firstOrNull() ?: id.trim()
        val cacheKey = "$primaryType:$primaryId:$providerToken"
        addonMetaCache[cacheKey]?.let { cached ->
            emit(NetworkResult.Success(cached))
            return@flow
        }
        val languageTag = AppLocaleResolver.resolveEffectiveAppLanguageTag(context)
        val itemKeys = buildMetaItemKeys(typeCandidates, idCandidates, type, id)
        if (cacheOnDisk) {
            readCachedMeta(itemKeys, languageTag, providerToken)?.let { cached ->
                val safeCached = cached.sanitizeCastMembers()
                addonMetaCache[cacheKey] = safeCached
                metaCache[cacheKey] = safeCached
                Log.d(TAG, "Meta disk cache hit origin=$origin itemKey=${itemKeys.firstOrNull().orEmpty()}")
                emit(NetworkResult.Success(safeCached))
                return@flow
            }
        }

        emit(NetworkResult.Loading)

        for (candidateType in typeCandidates) {
            for (candidateId in idCandidates) {
                val url = buildMetaUrl(addon.baseUrl, candidateType, candidateId)
                Log.d(
                    TAG,
                    "hydrateAddonOriginItem addonId=${addon.id} addonName=${addon.name} type=$candidateType id=$candidateId url=${sanitizeUrlForLogs(url)}"
                )
                when (
                    val result = addonMetaIntegrationProvider.getMeta(
                        addonId = addon.id,
                        metaUrl = url
                    )
                ) {
                    is NetworkResult.Success -> {
                        val metaDto = result.data.meta
                        if (metaDto != null) {
                            val episodeLabel = context.getString(R.string.episodes_episode)
                            val meta = posterRatingsUrlResolver
                                .apply(metaDto.toDomain(episodeLabel), activePosterProvider)
                                .sanitizeCastMembers()
                            addonMetaCache[cacheKey] = meta
                            metaCache[cacheKey] = meta
                            if (cacheOnDisk && writeToDisk) {
                                writeMetaDiskAliases(
                                    candidateType = candidateType,
                                    candidateId = candidateId,
                                    languageTag = languageTag,
                                    providerToken = providerToken,
                                    meta = meta
                                )
                            } else {
                                Log.d(TAG, "Meta disk cache bypassed origin=$origin itemKey=$candidateType:$candidateId")
                            }
                            Log.d(
                                TAG,
                                "hydrateAddonOriginItem fetch success addonId=${addon.id} type=$candidateType id=$candidateId"
                            )
                            emit(NetworkResult.Success(meta))
                            return@flow
                        }
                        Log.d(
                            TAG,
                            "hydrateAddonOriginItem meta response was null addonId=${addon.id} type=$candidateType id=$candidateId"
                        )
                    }
                    is NetworkResult.Error -> {
                        Log.w(
                            TAG,
                            "hydrateAddonOriginItem fetch failed addonId=${addon.id} type=$candidateType id=$candidateId code=${result.code} message=${result.message}"
                        )
                    }
                    NetworkResult.Loading -> { /* no-op */ }
                }
            }
        }

        emit(NetworkResult.Error("Meta not found via addon ${addon.id}"))
    }

    private fun writeMetaDiskAliases(
        candidateType: String,
        candidateId: String,
        languageTag: String,
        providerToken: String,
        meta: Meta
    ) {
        buildMetaDiskAliasKeys(candidateType, candidateId, meta).forEach { itemKey ->
            metadataDiskCacheStore.writeMeta(
                itemKey = itemKey,
                languageTag = languageTag,
                providerToken = providerToken,
                meta = meta
            )
        }
    }

    private fun readCachedMeta(
        itemKeys: List<String>,
        languageTag: String,
        providerToken: String
    ): Meta? {
        itemKeys.forEach { itemKey ->
            metadataDiskCacheStore.readMeta(
                itemKey = itemKey,
                languageTag = languageTag,
                providerToken = providerToken
            )?.let { return it }
        }
        return null
    }

    private fun buildMetaUrl(baseUrl: String, type: String, id: String): String {
        val encodedType = encodePathSegment(type)
        val encodedId = encodePathSegment(id)
        return buildAddonRequestUrl(baseUrl, "meta/$encodedType/$encodedId.json")
    }

    private fun Addon.supportsMetaType(type: String): Boolean {
        val target = type.trim()
        if (target.isBlank()) return false
        return resources.any { resource ->
            resource.name == "meta" && resource.supportsType(target)
        }
    }

    private fun AddonResource.supportsType(type: String): Boolean {
        if (types.isEmpty()) return true
        return types.any { it.equals(type, ignoreCase = true) }
    }

    private fun inferCanonicalType(type: String, id: String): String {
        val normalizedType = normalizeMetaLookupType(type)
        val known = setOf("movie", "series", "channel", "anime")
        if (normalizedType.lowercase() in known) return normalizedType

        val normalizedId = id.lowercase()
        return when {
            ":movie:" in normalizedId -> "movie"
            ":series:" in normalizedId -> "series"
            ":tv:" in normalizedId -> "series"
            ":anime:" in normalizedId -> "anime"
            else -> normalizedType
        }
    }

    private fun buildMetaTypeCandidates(type: String, id: String): List<String> {
        val normalized = normalizeMetaLookupType(type)
        val inferred = inferCanonicalType(normalized, id)
        return buildList {
            if (normalized.isNotBlank()) add(normalized)
            if (inferred.isNotBlank()) add(inferred)
            if (normalized.equals("series", ignoreCase = true) || inferred.equals("series", ignoreCase = true)) {
                add("tv")
            }
        }.distinctBy { it.lowercase() }
    }

    private fun normalizeMetaLookupType(type: String): String {
        val trimmed = type.trim()
        return if (trimmed.equals("tv", ignoreCase = true)) "series" else trimmed
    }

    private fun buildMetaIdCandidates(id: String): List<String> {
        val trimmed = id.trim()
        if (trimmed.isBlank()) return emptyList()
        return buildList {
            add(trimmed)
            when {
                trimmed.startsWith("tmdb:", ignoreCase = true) ||
                    trimmed.startsWith("trakt:", ignoreCase = true) ||
                    trimmed.startsWith("imdb:", ignoreCase = true) -> {
                    val plain = trimmed.substringAfter(':').trim()
                    if (plain.isNotEmpty()) add(plain)
                }
                trimmed.startsWith("tt", ignoreCase = true) -> add("imdb:$trimmed")
                trimmed.toIntOrNull() != null -> {
                    add("tmdb:$trimmed")
                    add("trakt:$trimmed")
                }
            }
        }.distinct()
    }

    private fun buildMetaItemKeys(
        typeCandidates: List<String>,
        idCandidates: List<String>,
        originalType: String,
        originalId: String
    ): List<String> {
        return buildList {
            add("${originalType.trim()}:${originalId.trim()}")
            typeCandidates.forEach { type ->
                idCandidates.forEach { id -> add("$type:$id") }
            }
        }.filterNot { it == ":" }.distinct()
    }

    private fun encodePathSegment(value: String): String {
        return URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
    
    override fun clearCache() {
        metaCache.clear()
        addonMetaCache.clear()
    }

    private fun Meta.sanitizeCastMembers(): Meta {
        val safeMembers = (castMembers as List<*>)
            .mapNotNull { raw ->
                when (raw) {
                    is MetaCastMember -> raw
                    is Map<*, *> -> {
                        val name = (raw["name"] as? String)?.trim().orEmpty()
                        if (name.isBlank()) return@mapNotNull null
                        MetaCastMember(
                            name = name,
                            character = (raw["character"] as? String)?.takeIf { it.isNotBlank() },
                            photo = (raw["photo"] as? String)?.takeIf { it.isNotBlank() },
                            tmdbId = when (val tmdbRaw = raw["tmdbId"]) {
                                is Number -> tmdbRaw.toInt()
                                is String -> tmdbRaw.toIntOrNull()
                                else -> null
                            }
                        )
                    }
                    else -> null
                }
            }
            .distinctBy { it.tmdbId ?: (it.name.lowercase() + "|" + (it.character ?: "")) }
        val fallback = if (safeMembers.isNotEmpty()) {
            safeMembers
        } else {
            (cast as List<*>).mapNotNull { raw ->
                val trimmed = (raw as? String)?.trim().orEmpty()
                if (trimmed.isBlank()) null else MetaCastMember(name = trimmed)
            }
        }
        return copy(castMembers = fallback)
    }

    private fun posterProviderCacheToken(
        activeProvider: PosterRatingsUrlResolver.ActiveProvider?
    ): String {
        if (activeProvider == null) return "native"
        return "${activeProvider.provider.name}:${activeProvider.apiKey.hashCode()}"
    }
}

internal fun buildMetaDiskAliasKeys(
    candidateType: String,
    candidateId: String,
    meta: Meta
): Set<String> {
    val keys = linkedSetOf("$candidateType:$candidateId")
    val resolvedType = meta.apiType.trim()
    if (resolvedType.isNotBlank()) {
        keys += "$resolvedType:$candidateId"
    }
    return keys
}

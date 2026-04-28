package com.nexio.tv.data.repository

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.nexio.tv.core.network.NetworkResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.integration.railpreview.SimklRailPreviewMapper
import com.nexio.tv.data.integration.simkl.SimklIntegrationProvider
import com.nexio.tv.data.local.SimklCatalogIds
import com.nexio.tv.data.local.SimklCatalogPreferences
import com.nexio.tv.data.local.SimklDiscoverySnapshotStore
import com.nexio.tv.data.local.SimklSettingsDataStore
import com.nexio.tv.data.remote.dto.simkl.SimklDiscoveryItemDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreviews
import com.nexio.tv.domain.model.toMetaPreview
import com.nexio.tv.domain.repository.MetaRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import javax.inject.Inject
import javax.inject.Singleton

data class SimklDiscoverySnapshot(
    val itemRecordsByCatalog: Map<String, List<RailItemPreview>> = emptyMap(),
    val updatedAtMs: Long = 0L
) {
    constructor(
        itemsByCatalog: Map<String, List<MetaPreview>>,
        updatedAtMs: Long = 0L,
        fromLegacyItems: Boolean = true
    ) : this(
        itemRecordsByCatalog = itemsByCatalog.mapValues { (catalogId, items) ->
            items.toLegacyRailItemPreviews(railId = catalogId)
        },
        updatedAtMs = updatedAtMs
    )

    val itemsByCatalog get() = itemRecordsByCatalog.mapValues { (_, items) -> items.map { it.toMetaPreview() } }
}

internal fun shouldPreserveLastNonEmptySimklDiscoverySnapshot(
    previousSnapshot: SimklDiscoverySnapshot,
    refreshedSnapshot: SimklDiscoverySnapshot,
    prefs: SimklCatalogPreferences
): Boolean {
    if (!previousSnapshot.hasConfiguredDiscoveryContent(prefs)) return false
    return !refreshedSnapshot.hasConfiguredDiscoveryContent(prefs)
}

private fun SimklDiscoverySnapshot.hasConfiguredDiscoveryContent(
    prefs: SimklCatalogPreferences
): Boolean {
    return prefs.enabledCatalogs.any { id -> itemRecordsByCatalog[id]?.isNotEmpty() == true }
}

internal fun extractSimklDiscoveryMediaObject(dto: JsonObject, kind: String): JsonObject {
    return when (kind) {
        "tv" -> dto.getAsJsonObject("show") ?: dto
        "anime" -> dto.getAsJsonObject("anime") ?: dto
        "movies" -> dto.getAsJsonObject("movie") ?: dto
        else -> dto
    }
}

internal fun preferredSimklExternalContentId(ids: JsonObject): String? {
    val imdb = ids.stringValue("imdb")?.takeIf { it.isNotBlank() }
    if (imdb != null) return imdb
    val tmdb = ids.stringValue("tmdb")?.takeIf { it.isNotBlank() }
        ?: ids.numberValue("tmdb")?.toString()
    if (!tmdb.isNullOrBlank()) return "tmdb:$tmdb"
    return null
}

internal suspend fun resolveSimklCanonicalContentId(
    kind: String,
    ids: JsonObject,
    externalIdCache: MutableMap<String, String?>,
    fetchExternalContentId: suspend (kind: String, simklId: String) -> String?
): String? {
    preferredSimklExternalContentId(ids)?.let { return it }
    val simklId = ids.stringValue("simkl_id")
        ?: ids.stringValue("simkl")
        ?: return null
    val cacheKey = "$kind:$simklId"
    if (externalIdCache.containsKey(cacheKey)) {
        return externalIdCache[cacheKey] ?: "simkl:$simklId"
    }
    val resolved = fetchExternalContentId(kind, simklId)
    externalIdCache[cacheKey] = resolved
    return resolved ?: "simkl:$simklId"
}

internal fun JsonObject.stringValue(key: String): String? {
    return runCatching { get(key) }
        .getOrNull()
        ?.takeIf { !it.isJsonNull }
        ?.asString
        ?.trim()
        ?.takeIf { it.isNotBlank() }
}

internal fun JsonObject.numberValue(key: String): Number? {
    return runCatching { get(key) }
        .getOrNull()
        ?.takeIf { !it.isJsonNull }
        ?.asNumber
}

private data class SimklCatalogSource(
    val url: String,
    val kind: String
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class SimklDiscoveryService @Inject constructor(
    private val simklIntegrationProvider: SimklIntegrationProvider,
    private val metaRepository: MetaRepository,
    private val simklSettingsDataStore: SimklSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: SimklDiscoverySnapshotStore,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profileSnapshots = MutableStateFlow<Map<Int, SimklDiscoverySnapshot>>(emptyMap())
    private val refreshMutex = Mutex()
    private val gson = Gson()
    private val railPreviewMapper = SimklRailPreviewMapper()
    private val lastRefreshByProfile = mutableMapOf<Int, Long>()
    private val snapshotTtlMs = 6L * 60L * 60L * 1_000L
    private val minRefreshIntervalMs = 30_000L
    private val maxItemsPerRail = 20
    @Volatile private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null
    private val externalIdCache = object : LinkedHashMap<String, String?>(200, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, String?>?) = size > 200
    }
    private val catalogSources = linkedMapOf(
        SimklCatalogIds.TV_TRENDING_TODAY to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/tv/today_100.json",
            kind = "tv"
        ),
        SimklCatalogIds.TV_TRENDING_WEEK to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/tv/week_100.json",
            kind = "tv"
        ),
        SimklCatalogIds.TV_TRENDING_MONTH to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/tv/month_100.json",
            kind = "tv"
        ),
        SimklCatalogIds.ANIME_TRENDING_TODAY to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/anime/today_100.json",
            kind = "anime"
        ),
        SimklCatalogIds.ANIME_TRENDING_WEEK to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/anime/week_100.json",
            kind = "anime"
        ),
        SimklCatalogIds.ANIME_TRENDING_MONTH to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/anime/month_100.json",
            kind = "anime"
        ),
        SimklCatalogIds.MOVIE_TRENDING_TODAY to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/movies/today_100.json",
            kind = "movies"
        ),
        SimklCatalogIds.MOVIE_TRENDING_WEEK to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/movies/week_100.json",
            kind = "movies"
        ),
        SimklCatalogIds.MOVIE_TRENDING_MONTH to SimklCatalogSource(
            url = "https://data.simkl.in/discover/trending/movies/month_100.json",
            kind = "movies"
        ),
        SimklCatalogIds.DVD_RELEASES to SimklCatalogSource(
            url = "https://data.simkl.in/discover/dvd/releases_100.json",
            kind = "movies"
        )
    )

    private fun snapshotForProfile(profileId: Int): SimklDiscoverySnapshot =
        profileSnapshots.value[profileId] ?: SimklDiscoverySnapshot()

    private fun setProfileSnapshot(profileId: Int, snapshot: SimklDiscoverySnapshot) {
        profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
    }

    init {
        check(catalogSources.keys.toSet() == SimklCatalogIds.BUILT_IN_ORDER.toSet()) {
            "catalogSources keys must match BUILT_IN_ORDER"
        }
        scope.launch {
            val profileId = profileManager.activeProfileId.value
            snapshotStore.read(profileId = profileId)?.let { persisted ->
                setProfileSnapshot(profileId, persisted)
                lastRefreshByProfile[profileId] = persisted.updatedAtMs
            }
            externalIdCache.putAll(snapshotStore.readExternalIdCache(profileId))
        }
    }

    fun observeSnapshot(autoRefreshOnStart: Boolean = true): Flow<SimklDiscoverySnapshot> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            profileSnapshots
                .map { snapshots -> snapshots[profileId] ?: SimklDiscoverySnapshot() }
                .onStart {
                    var hadPersistedSnapshot = false
                    snapshotStore.read(profileId = profileId)?.let { persisted ->
                        hadPersistedSnapshot = true
                        setProfileSnapshot(profileId, persisted)
                        lastRefreshByProfile[profileId] = persisted.updatedAtMs
                    }
                    if (autoRefreshOnStart && !hadPersistedSnapshot) {
                        scope.launch {
                            runCatching { ensureFresh(force = false, profileId = profileId) }
                                .onFailure { Log.w("SimklDiscovery", "Failed to refresh SIMKL discovery snapshot", it) }
                        }
                    }
                }
        }
    }

    /** Consistent with other discovery services. Simkl has no startup gate. */
    suspend fun priorityFetch() = ensureFresh(force = true)

    suspend fun ensureFresh(
        force: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) = withContext(Dispatchers.IO) {
        val prefs = simklSettingsDataStore.catalogPreferences.first()
        val now = System.currentTimeMillis()
        val snapshot = snapshotForProfile(profileId)
        val isFresh = snapshot.updatedAtMs > 0L && (now - snapshot.updatedAtMs) < snapshotTtlMs
        val hasConfiguredContent = snapshot.hasConfiguredDiscoveryContent(prefs)
        if (!force && isFresh && hasConfiguredContent) return@withContext
        val lastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
        if (!force && now - lastRefreshMs < minRefreshIntervalMs && snapshot.updatedAtMs > 0L && hasConfiguredContent) {
            return@withContext
        }
        activePosterProvider = posterRatingsUrlResolver.getActiveProvider()

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedSnapshot = snapshotForProfile(profileId)
            val lockedFresh = lockedSnapshot.updatedAtMs > 0L && (lockedNow - lockedSnapshot.updatedAtMs) < snapshotTtlMs
            val lockedHasConfiguredContent = lockedSnapshot.hasConfiguredDiscoveryContent(prefs)
            if (!force && lockedFresh && lockedHasConfiguredContent) return@withLock
            val lockedLastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
            if (!force && lockedNow - lockedLastRefreshMs < minRefreshIntervalMs && lockedSnapshot.updatedAtMs > 0L && lockedHasConfiguredContent) {
                return@withLock
            }

            val results = linkedMapOf<String, List<RailItemPreview>>()
            prefs.enabledCatalogs.forEach { catalogId ->
                val source = catalogSources[catalogId] ?: return@forEach
                results[catalogId] = fetchCatalog(railId = catalogId, source = source)
            }
            val refreshed = SimklDiscoverySnapshot(
                itemRecordsByCatalog = results,
                updatedAtMs = System.currentTimeMillis()
            )
            val snapshotToPersist = if (
                shouldPreserveLastNonEmptySimklDiscoverySnapshot(lockedSnapshot, refreshed, prefs)
            ) {
                lockedSnapshot
            } else {
                refreshed
            }
            setProfileSnapshot(profileId, snapshotToPersist)
            snapshotStore.write(snapshotToPersist, profileId = profileId)
            snapshotStore.writeExternalIdCache(externalIdCache, profileId = profileId)
            lastRefreshByProfile[profileId] = lockedNow
        }
    }

    private suspend fun fetchCatalog(railId: String, source: SimklCatalogSource): List<RailItemPreview> {
        val body = executeGet(source.url) ?: return emptyList()
        val array = runCatching { gson.fromJson(body, JsonArray::class.java) }.getOrNull() ?: return emptyList()
        val generatedAtMs = System.currentTimeMillis()
        return array.mapIndexedNotNull { index, element ->
            mapDiscoveryItem(
                railId = railId,
                dto = element.asJsonObject,
                kind = source.kind,
                position = index,
                generatedAtMs = generatedAtMs
            )
        }
            .take(maxItemsPerRail)
    }

    private suspend fun executeGet(url: String): String? = withContext(Dispatchers.IO) {
        simklIntegrationProvider.fetchDiscoveryBody(url)
    }

    private fun mapDiscoveryItem(
        railId: String,
        dto: JsonObject,
        kind: String,
        position: Int,
        generatedAtMs: Long
    ): RailItemPreview? {
        val media = extractSimklDiscoveryMediaObject(dto, kind)
        val isMovie = kind == "movies" ||
            media.get("anime_type")?.asString?.equals("movie", ignoreCase = true) == true
        val type = if (isMovie) ContentType.MOVIE else ContentType.SERIES
        val discoveryItem = mapSimklDiscoveryItemDto(dto = dto, media = media) ?: return null

        return railPreviewMapper.mapDiscoveryItem(
            railId = railId,
            item = discoveryItem,
            itemType = type,
            position = position,
            generatedAtMs = generatedAtMs
        )
    }

    private fun mapSimklDiscoveryItemDto(
        dto: JsonObject,
        media: JsonObject
    ): SimklDiscoveryItemDto? {
        val merged = JsonObject()
        dto.entrySet().forEach { (key, value) ->
            if (key != "show" && key != "anime" && key != "movie") {
                merged.add(key, value)
            }
        }
        media.entrySet().forEach { (key, value) ->
            merged.add(key, value)
        }
        return runCatching { gson.fromJson(merged, SimklDiscoveryItemDto::class.java) }.getOrNull()
    }

    private suspend fun fetchExternalContentId(
        kind: String,
        simklId: String
    ): String? {
        // Most CDN items already include imdb/tmdb ids, but a small subset of anime entries only
        // ships MAL/AniList/Kitsu ids. SIMKL detail endpoints still resolve those items without auth,
        // so keep this fallback for canonical metadata hydration.
        val endpoint = when (kind) {
            "tv" -> "https://api.simkl.com/tv/$simklId?extended=full"
            "anime" -> "https://api.simkl.com/anime/$simklId?extended=full"
            "movies" -> "https://api.simkl.com/movies/$simklId?extended=full"
            else -> return null
        }
        val body = executeGet(endpoint) ?: return null
        val obj = runCatching { gson.fromJson(body, JsonObject::class.java) }.getOrNull() ?: return null
        val detailIds = obj.getAsJsonObject("ids") ?: return null
        return preferredSimklExternalContentId(detailIds)
    }

    private suspend fun resolveMetaPreview(type: String, contentId: String): MetaPreview? {
        val result = withTimeoutOrNull(2_000L) {
            metaRepository.getMetaFromAllAddons(type = type, id = contentId)
                .filter { it !is NetworkResult.Loading }
                .first()
        } ?: return null
        val meta = (result as? NetworkResult.Success<Meta>)?.data ?: return null
        return posterRatingsUrlResolver.apply(meta.toMetaPreview(), activePosterProvider)
    }

    private fun Meta.toMetaPreview(): MetaPreview {
        return MetaPreview(
            id = id,
            type = type,
            rawType = rawType,
            name = name,
            poster = poster,
            posterShape = posterShape,
            background = background,
            logo = logo,
            description = description,
            releaseInfo = releaseInfo,
            imdbRating = imdbRating,
            genres = genres,
            trailerYtIds = trailerYtIds,
            language = language
        )
    }
}

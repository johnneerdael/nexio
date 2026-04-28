package com.nexio.tv.data.repository

import android.os.SystemClock
import android.util.Log
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.data.integration.mdblist.MDBListIntegrationProvider
import com.nexio.tv.data.integration.railpreview.MDBListRailPreviewMapper
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.RailItemPreview
import com.nexio.tv.domain.model.toLegacyRailItemPreviews
import com.nexio.tv.domain.model.toMetaPreview
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

data class MDBListListOption(
    val key: String,
    val owner: String,
    val listId: String,
    val itemListIds: List<String> = emptyList(),
    val title: String,
    val itemCount: Int,
    val isPersonal: Boolean
)

data class MDBListCustomCatalog(
    val key: String,
    val catalogId: String,
    val catalogName: String,
    val type: ContentType,
    val itemRecords: List<RailItemPreview> = emptyList()
) {
    constructor(
        key: String,
        catalogId: String,
        catalogName: String,
        type: ContentType,
        items: List<MetaPreview>,
        fromLegacyItems: Boolean = true
    ) : this(
        key = key,
        catalogId = catalogId,
        catalogName = catalogName,
        type = type,
        itemRecords = items.toLegacyRailItemPreviews(railId = catalogId)
    )

    val items get() = itemRecords.map { it.toMetaPreview() }
}

fun legacyMDBListCustomCatalog(
    key: String,
    catalogId: String,
    catalogName: String,
    type: ContentType,
    items: List<MetaPreview>
): MDBListCustomCatalog = MDBListCustomCatalog(
    key = key,
    catalogId = catalogId,
    catalogName = catalogName,
    type = type,
    itemRecords = items.toLegacyRailItemPreviews(railId = catalogId)
)

data class MDBListDiscoverySnapshot(
    val personalLists: List<MDBListListOption> = emptyList(),
    val topLists: List<MDBListListOption> = emptyList(),
    val customListCatalogs: List<MDBListCustomCatalog> = emptyList(),
    val updatedAtMs: Long = 0L
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class MDBListDiscoveryService @Inject constructor(
    private val mdbListIntegrationProvider: MDBListIntegrationProvider,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: MDBListDiscoverySnapshotStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val profileManager: ProfileManager
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val profileSnapshots = MutableStateFlow<Map<Int, MDBListDiscoverySnapshot>>(emptyMap())
    private val refreshMutex = Mutex()
    private val railPreviewMapper = MDBListRailPreviewMapper()
    private val lastRefreshByProfile = mutableMapOf<Int, Long>()
    private val minRefreshIntervalMs = 30_000L
    private val maxItemsPerRail = 20
    @Volatile
    private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null

    private fun snapshotForProfile(profileId: Int): MDBListDiscoverySnapshot =
        profileSnapshots.value[profileId] ?: MDBListDiscoverySnapshot()

    private fun setProfileSnapshot(profileId: Int, snapshot: MDBListDiscoverySnapshot) {
        profileSnapshots.value = profileSnapshots.value + (profileId to snapshot)
    }

    init {
        scope.launch {
            val profileId = profileManager.activeProfileId.value
            snapshotStore.read(profileId = profileId)?.let { persisted ->
                setProfileSnapshot(profileId, persisted)
                lastRefreshByProfile[profileId] = persisted.updatedAtMs
            }
        }
    }

    fun observeSnapshot(autoRefreshOnStart: Boolean = true): Flow<MDBListDiscoverySnapshot> {
        return profileManager.activeProfileId.flatMapLatest { profileId ->
            profileSnapshots
                .map { snapshots -> snapshots[profileId] ?: MDBListDiscoverySnapshot() }
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
                                .onFailure { error ->
                                    Log.w("MDBListDiscovery", "Failed to refresh MDBList discovery snapshot", error)
                                }
                        }
                    }
                }
        }
    }

    suspend fun priorityFetch() {
        ensureFresh(force = true)
    }

    suspend fun ensureFresh(
        force: Boolean,
        profileId: Int = profileManager.activeProfileId.value
    ) = withContext(Dispatchers.IO) {
        val settings = mdbListSettingsDataStore.settings.first()
        activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) {
            Log.d("MDBListDiscovery", "Skipping refresh enabled=${settings.enabled} apiKeyPresent=${apiKey.isNotBlank()}")
            setProfileSnapshot(profileId, MDBListDiscoverySnapshot())
            snapshotStore.clear(profileId = profileId)
            return@withContext
        }

        val now = System.currentTimeMillis()
        val lastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
        if (now - lastRefreshMs < minRefreshIntervalMs && snapshotForProfile(profileId).updatedAtMs > 0L) {
            return@withContext
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            val lockedLastRefreshMs = lastRefreshByProfile[profileId] ?: 0L
            if (lockedNow - lockedLastRefreshMs < minRefreshIntervalMs &&
                snapshotForProfile(profileId).updatedAtMs > 0L
            ) {
                return@withLock
            }

            val personalLists = fetchPersonalLists(apiKey, profileId)
            val topLists = fetchTopLists(apiKey, profileId)
            val catalogPrefs = mdbListSettingsDataStore.catalogPreferences.first()
            val customCatalogs = fetchSelectedCatalogs(
                apiKey = apiKey,
                profileId = profileId,
                personalLists = personalLists,
                topLists = topLists,
                catalogPrefs = catalogPrefs
            )

            Log.d(
                "MDBListDiscovery",
                "Refreshed personal=${personalLists.size} top=${topLists.size} custom=${customCatalogs.size}"
            )

            val snapshotState = MDBListDiscoverySnapshot(
                personalLists = personalLists,
                topLists = topLists,
                customListCatalogs = customCatalogs,
                updatedAtMs = System.currentTimeMillis()
            )
            setProfileSnapshot(profileId, snapshotState)
            snapshotStore.write(snapshotState, profileId = profileId)
            lastRefreshByProfile[profileId] = System.currentTimeMillis()
        }
    }

    private suspend fun fetchPersonalLists(apiKey: String, profileId: Int): List<MDBListListOption> {
        val arrays = listOfNotNull(
            requestArray(apiKey = apiKey, profileId = profileId, relativeUrl = "lists/user"),
            requestArray(apiKey = apiKey, profileId = profileId, relativeUrl = "my/lists"),
            requestArray(apiKey = apiKey, profileId = profileId, relativeUrl = "lists/me")
        )
        return arrays.asSequence()
            .flatMap { array -> parseListOptions(array, isPersonal = true).asSequence() }
            .distinctBy { it.key }
            .toList()
    }

    private suspend fun fetchTopLists(apiKey: String, profileId: Int): List<MDBListListOption> {
        val arrays = listOfNotNull(
            requestArray(apiKey = apiKey, profileId = profileId, relativeUrl = "lists/top"),
            requestArray(apiKey = apiKey, profileId = profileId, relativeUrl = "top/lists")
        )
        return arrays.asSequence()
            .flatMap { array -> parseListOptions(array, isPersonal = false).asSequence() }
            .distinctBy { it.key }
            .toList()
    }

    private suspend fun fetchSelectedCatalogs(
        apiKey: String,
        profileId: Int,
        personalLists: List<MDBListListOption>,
        topLists: List<MDBListListOption>,
        catalogPrefs: MDBListCatalogPreferences
    ): List<MDBListCustomCatalog> {
        val personalEnabled = personalLists
            .filter { catalogPrefs.isPersonalListEnabled(it.key) }
            .associateBy { it.key }
        val topSelected = topLists
            .filter { catalogPrefs.isTopListSelected(it.key) }
            .associateBy { it.key }
        val activeOptions = linkedMapOf<String, MDBListListOption>().apply {
            putAll(personalEnabled)
            putAll(topSelected)
            catalogPrefs.selectedTopListKeys.forEach { key ->
                if (!containsKey(key)) {
                    parseTopListKeyFallback(key)?.let { put(it.key, it) }
                }
            }
        }
        if (activeOptions.isEmpty()) return emptyList()

        val orderedKeys = mdbListSettingsDataStore.sanitizeCatalogOrder(
            rawOrder = catalogPrefs.catalogOrder,
            availableKeys = activeOptions.keys
        )

        return orderedKeys.flatMap { key ->
            val option = activeOptions[key] ?: return@flatMap emptyList()
            fetchCatalogForList(apiKey = apiKey, profileId = profileId, option = option)
        }
    }

    private suspend fun fetchCatalogForList(
        apiKey: String,
        profileId: Int,
        option: MDBListListOption
    ): List<MDBListCustomCatalog> {
        val detailBody = requestListDetailBody(apiKey = apiKey, profileId = profileId, option = option)
        val detailOptions = detailBody
            ?.let(::parseJsonArray)
            ?.let { parseListOptions(it, isPersonal = option.isPersonal) }
            .orEmpty()
        val resolvedListIds = if (option.itemListIds.isNotEmpty()) {
            option.itemListIds
        } else {
            detailBody
                ?.let(::parseResolvedListIds)
                .orEmpty()
                .ifEmpty { listOf(option.listId).filter { it.isNumericListId() } }
        }
        val displayTitle = firstNonBlank(
            detailOptions.firstOrNull()?.title,
            option.title
        )
        val catalogBase = "mdblist_list_${slugify(option.key)}"
        val movieRailId = "${catalogBase}_movies"
        val showRailId = "${catalogBase}_shows"
        val generatedAtMs = System.currentTimeMillis()
        val payloads = listOfNotNull(
            *resolvedListIds
                .mapNotNull { resolvedId ->
                    requestBodyWithQuery(
                        relativeUrl = "lists/$resolvedId/items",
                        profileId = profileId,
                        accountCredential = apiKey,
                        query = mapOf(
                            "apikey" to apiKey,
                            "limit" to maxItemsPerRail.toString(),
                            "offset" to "0",
                            "append_to_response" to "genres,poster,description,ratings"
                        )
                    )
                }
                .toTypedArray(),
            detailBody,
            requestAbsoluteBody(
                url = "https://mdblist.com/lists/${option.owner}/${option.listId}/json",
                profileId = profileId,
                accountCredential = apiKey
            )
        )

        val parsedItems = payloads.asSequence()
            .flatMap { payload ->
                parseListItemsPayload(
                    raw = payload,
                    movieRailId = movieRailId,
                    showRailId = showRailId,
                    generatedAtMs = generatedAtMs
                ).asSequence()
            }
            .distinctBy { "${it.type}:${it.preview.toMetaPreview().id}" }
            .take(maxItemsPerRail * 2)
            .toList()

        Log.d(
            "MDBListDiscovery",
            "Catalog ${option.key} listIds=${resolvedListIds.joinToString(",")} payloads=${payloads.size} parsed=${parsedItems.size}"
        )

        val movies = parsedItems
            .filter { it.type == ContentType.MOVIE }
            .map { it.preview }
            .take(maxItemsPerRail)
        val shows = parsedItems
            .filter { it.type == ContentType.SERIES }
            .map { it.preview }
            .take(maxItemsPerRail)

        val catalogs = mutableListOf<MDBListCustomCatalog>()
        if (movies.isNotEmpty()) {
            catalogs += MDBListCustomCatalog(
                key = option.key,
                catalogId = movieRailId,
                catalogName = "$displayTitle (Movies)",
                type = ContentType.MOVIE,
                itemRecords = movies
            )
        }
        if (shows.isNotEmpty()) {
            catalogs += MDBListCustomCatalog(
                key = option.key,
                catalogId = showRailId,
                catalogName = "$displayTitle (Shows)",
                type = ContentType.SERIES,
                itemRecords = shows
            )
        }
        return catalogs
    }

    private suspend fun requestListDetailBody(
        apiKey: String,
        profileId: Int,
        option: MDBListListOption
    ): String? {
        val byName = requestBody(apiKey = apiKey, profileId = profileId, relativeUrl = "lists/${option.owner}/${option.listId}")
        if (!byName.isNullOrBlank() || !option.listId.isNumericListId()) {
            return byName
        }
        return requestBody(apiKey = apiKey, profileId = profileId, relativeUrl = "lists/${option.listId}")
    }

    private fun parseTopListKeyFallback(key: String): MDBListListOption? {
        val payload = key.trim().substringAfter("top:", missingDelimiterValue = "").trim()
        if (payload.isBlank()) return null
        val owner = payload.substringBefore('/').trim()
        val listId = payload.substringAfter('/', missingDelimiterValue = "").trim()
        if (owner.isBlank() || listId.isBlank()) return null
        return MDBListListOption(
            key = "top:$owner/$listId",
            owner = owner,
            listId = listId,
            title = "$owner/$listId",
            itemCount = 0,
            isPersonal = false
        )
    }

    private suspend fun requestArray(apiKey: String, profileId: Int, relativeUrl: String): JSONArray? {
        return requestBody(apiKey, profileId, relativeUrl)?.let(::parseJsonArray)
    }

    private suspend fun requestBody(apiKey: String, profileId: Int, relativeUrl: String): String? {
        return try {
            when (val result = mdbListIntegrationProvider.getRaw(relativeUrl = relativeUrl, apiKey = apiKey, profileId = profileId)) {
                is IntegrationCallResult.Success -> result.value.trim()
                is IntegrationCallResult.HttpError -> {
                    Log.d("MDBListDiscovery", "Request failed: $relativeUrl code=${result.statusCode}")
                    null
                }
                else -> null
            }
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $relativeUrl (${error.message})")
            null
        }
    }

    private suspend fun requestBodyWithQuery(
        relativeUrl: String,
        profileId: Int,
        accountCredential: String,
        query: Map<String, String>
    ): String? {
        return try {
            when (
                val result = mdbListIntegrationProvider.getRawWithQuery(
                    relativeUrl = relativeUrl,
                    query = query,
                    profileId = profileId,
                    accountCredential = accountCredential
                )
            ) {
                is IntegrationCallResult.Success -> result.value.trim()
                is IntegrationCallResult.HttpError -> {
                    Log.d("MDBListDiscovery", "Request failed: $relativeUrl code=${result.statusCode} query=${query.keys.joinToString(",")}")
                    null
                }
                else -> null
            }
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $relativeUrl (${error.message})")
            null
        }
    }

    private suspend fun requestAbsoluteBody(
        url: String,
        profileId: Int,
        accountCredential: String
    ): String? {
        return try {
            when (
                val result = mdbListIntegrationProvider.getRawWithQuery(
                    relativeUrl = url,
                    query = emptyMap(),
                    profileId = profileId,
                    accountCredential = accountCredential
                )
            ) {
                is IntegrationCallResult.Success -> result.value.trim()
                is IntegrationCallResult.HttpError -> {
                    Log.d("MDBListDiscovery", "Request failed: $url code=${result.statusCode}")
                    null
                }
                else -> null
            }
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $url (${error.message})")
            null
        }
    }

    private fun parseJsonArray(raw: String): JSONArray? {
        if (raw.isBlank()) return null
        return try {
            when {
                raw.startsWith("[") -> JSONArray(raw)
                raw.startsWith("{") -> {
                    val obj = JSONObject(raw)
                    when {
                        obj.has("lists") -> obj.optJSONArray("lists")
                        obj.has("results") -> obj.optJSONArray("results")
                        obj.has("items") -> obj.optJSONArray("items")
                        obj.has("data") -> when (val data = obj.opt("data")) {
                            is JSONArray -> data
                            is JSONObject -> {
                                data.optJSONArray("items")
                                    ?: data.optJSONArray("results")
                                    ?: data.optJSONArray("lists")
                            }
                            else -> null
                        }
                        else -> null
                    }
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parseListOptions(array: JSONArray, isPersonal: Boolean): List<MDBListListOption> {
        val prefix = if (isPersonal) "personal" else "top"
        return buildList {
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                val listObj = obj.optJSONObject("list") ?: obj
                val ownerObj = obj.optJSONObject("user") ?: listObj.optJSONObject("user")
                val owner = firstNonBlank(
                    ownerObj?.optString("slug"),
                    ownerObj?.optString("username"),
                    ownerObj?.optString("username"),
                    listObj.optString("user_name"),
                    listObj.optString("owner"),
                    "mdblist"
                )
                val listId = firstNonBlank(
                    listObj.optString("slug"),
                    listObj.optString("id"),
                    listObj.optString("uuid"),
                    listObj.optString("list_id")
                )
                if (listId.isBlank()) continue
                val title = firstNonBlank(
                    listObj.optString("name"),
                    listObj.optString("title"),
                    "$owner/$listId"
                )
                val itemCount = positiveInt(
                    listObj.optInt("item_count", -1),
                    listObj.optInt("items", -1),
                    listObj.optInt("count", -1)
                )
                val itemListIds = buildList {
                    listObj.opt("id")?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                    listObj.optJSONArray("ids")?.let { ids ->
                        for (idIndex in 0 until ids.length()) {
                            ids.opt(idIndex)?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }.distinct()
                add(
                    MDBListListOption(
                        key = "$prefix:$owner/$listId",
                        owner = owner,
                        listId = listId,
                        itemListIds = itemListIds,
                        title = title,
                        itemCount = itemCount,
                        isPersonal = isPersonal
                    )
                )
            }
        }
    }

    private data class ParsedListItem(
        val type: ContentType,
        val preview: RailItemPreview
    )

    private fun parseListItems(
        array: JSONArray,
        movieRailId: String,
        showRailId: String,
        generatedAtMs: Long
    ): List<ParsedListItem> {
        var moviePosition = 0
        var showPosition = 0
        return buildList {
            for (i in 0 until array.length()) {
                val raw = array.optJSONObject(i) ?: continue
                val movieObj = raw.optJSONObject("movie")
                val showObj = raw.optJSONObject("show")
                val itemObj = movieObj ?: showObj ?: raw
                val rawType = firstNonBlank(
                    raw.optString("media_type"),
                    raw.optString("mediatype"),
                    raw.optString("type"),
                    if (movieObj != null) "movie" else if (showObj != null) "show" else ""
                ).lowercase()

                val type = when {
                    rawType.contains("movie") || rawType == "film" -> ContentType.MOVIE
                    rawType.contains("show") || rawType.contains("series") || rawType == "tv" -> ContentType.SERIES
                    movieObj != null -> ContentType.MOVIE
                    showObj != null -> ContentType.SERIES
                    else -> continue
                }

                val idsObj = itemObj.optJSONObject("ids")
                val imdbId = firstNonBlank(
                    itemObj.optString("imdb_id"),
                    itemObj.optString("imdb"),
                    idsObj?.optString("imdb"),
                    raw.optString("imdb_id"),
                    raw.optString("imdb")
                ).takeIf { it.startsWith("tt", ignoreCase = true) }
                val tmdbId = firstNonBlank(
                    itemObj.optString("tmdb_id"),
                    itemObj.optString("tmdb"),
                    idsObj?.optString("tmdb"),
                    raw.optString("tmdb_id"),
                    raw.optString("tmdb")
                )
                val tvdbId = firstNonBlank(
                    itemObj.optString("tvdb_id"),
                    itemObj.optString("tvdb"),
                    idsObj?.optString("tvdb"),
                    raw.optString("tvdb_id"),
                    raw.optString("tvdb")
                )
                val contentId = imdbId
                    ?: tmdbId.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
                    ?: tvdbId.takeIf { it.isNotBlank() }?.let { "tvdb:$it" }
                    ?: firstNonBlank(
                        itemObj.optString("id"),
                        itemObj.optString("slug"),
                        idsObj?.optString("slug"),
                        idsObj?.optString("trakt"),
                        raw.optString("id"),
                        raw.optString("slug")
                    )
                if (contentId.isBlank()) continue

                val railId = if (type == ContentType.MOVIE) movieRailId else showRailId
                val position = if (type == ContentType.MOVIE) moviePosition++ else showPosition++
                val itemJson = normalizedMdbListItemJson(
                    raw = raw,
                    itemObj = itemObj,
                    type = type,
                    contentId = contentId,
                    imdbId = imdbId,
                    tmdbId = tmdbId,
                    tvdbId = tvdbId
                )
                val preview = railPreviewMapper.mapJsonObject(
                    railId = railId,
                    item = itemJson,
                    position = position,
                    generatedAtMs = generatedAtMs
                ) ?: continue

                add(
                    ParsedListItem(
                        type = type,
                        preview = preview
                    )
                )
            }
        }
    }

    private fun parseListItemsPayload(
        raw: String,
        movieRailId: String,
        showRailId: String,
        generatedAtMs: Long
    ): List<ParsedListItem> {
        if (raw.isBlank()) return emptyList()

        return try {
            when {
                raw.startsWith("[") -> parseListItems(JSONArray(raw), movieRailId, showRailId, generatedAtMs)
                raw.startsWith("{") -> {
                    val obj = JSONObject(raw)
                    val groupedItems = buildList {
                        obj.optJSONArray("movies")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                        obj.optJSONArray("shows")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                        obj.optJSONArray("items")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                        obj.optJSONArray("results")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                        when (val data = obj.opt("data")) {
                            is JSONArray -> addAll(parseListItems(data, movieRailId, showRailId, generatedAtMs))
                            is JSONObject -> {
                                data.optJSONArray("movies")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                                data.optJSONArray("shows")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                                data.optJSONArray("items")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                                data.optJSONArray("results")?.let { addAll(parseListItems(it, movieRailId, showRailId, generatedAtMs)) }
                            }
                        }
                    }

                    if (groupedItems.isNotEmpty()) {
                        groupedItems
                    } else {
                        parseJsonArray(raw)
                            ?.let { parseListItems(it, movieRailId, showRailId, generatedAtMs) }
                            .orEmpty()
                    }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizedMdbListItemJson(
        raw: JSONObject,
        itemObj: JSONObject,
        type: ContentType,
        contentId: String,
        imdbId: String?,
        tmdbId: String,
        tvdbId: String
    ): JsonObject {
        val json = runCatching {
            JsonParser.parseString(itemObj.toString()).asJsonObject
        }.getOrElse { JsonObject() }

        setStringIfMissing(json, "id", firstNonBlank(itemObj.optString("id"), raw.optString("id"), contentId))
        json.addProperty("type", if (type == ContentType.MOVIE) "movie" else "series")
        setStringIfMissing(
            json,
            "title",
            firstNonBlank(
                itemObj.optString("title"),
                itemObj.optString("name"),
                raw.optString("title"),
                raw.optString("name"),
                contentId
            )
        )
        setIntIfMissing(json, "year", positiveInt(itemObj.optInt("year", -1), raw.optInt("year", -1)))
        setStringIfMissing(
            json,
            "poster",
            firstNonBlank(
                itemObj.optString("poster"),
                itemObj.optString("poster_url"),
                itemObj.optString("image"),
                itemObj.optString("backdrop"),
                raw.optString("poster"),
                raw.optString("poster_url"),
                raw.optString("image"),
                raw.optString("backdrop")
            )
        )
        setStringIfMissing(
            json,
            "description",
            firstNonBlank(
                itemObj.optString("description"),
                itemObj.optString("overview"),
                raw.optString("description"),
                raw.optString("overview")
            )
        )
        setStringIfMissing(json, "imdb_id", imdbId.orEmpty())
        setStringIfMissing(json, "tmdb_id", tmdbId)
        setStringIfMissing(json, "tvdb_id", tvdbId)

        return json
    }

    private fun setStringIfMissing(json: JsonObject, key: String, value: String) {
        if (!json.hasNonBlankPrimitive(key) && value.isNotBlank()) {
            json.addProperty(key, value)
        }
    }

    private fun setIntIfMissing(json: JsonObject, key: String, value: Int) {
        if (!json.has(key) && value > 0) {
            json.addProperty(key, value)
        }
    }

    private fun JsonObject.hasNonBlankPrimitive(key: String): Boolean {
        val element = get(key) ?: return false
        if (!element.isJsonPrimitive || element.isJsonNull) return false
        val primitive = element.asJsonPrimitive
        return when {
            primitive.isString -> primitive.asString.isNotBlank()
            primitive.isNumber -> true
            primitive.isBoolean -> true
            else -> false
        }
    }

    private fun parseResolvedListIds(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            val array = when {
                raw.startsWith("[") -> JSONArray(raw)
                raw.startsWith("{") -> parseJsonArray(raw)
                else -> null
            } ?: return emptyList()

            buildList {
                for (index in 0 until array.length()) {
                    val obj = array.optJSONObject(index) ?: continue
                    obj.opt("id")?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                    obj.optJSONArray("ids")?.let { ids ->
                        for (idIndex in 0 until ids.length()) {
                            ids.opt(idIndex)?.toString()?.takeIf { it.isNotBlank() }?.let(::add)
                        }
                    }
                }
            }.distinct()
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun firstNonBlank(vararg values: String?): String {
        return values.firstOrNull { !it.isNullOrBlank() }?.trim().orEmpty()
    }

    private fun positiveInt(vararg candidates: Int): Int {
        return candidates.firstOrNull { it >= 0 } ?: 0
    }

    private fun slugify(value: String): String {
        return value.lowercase()
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .ifBlank { "custom" }
    }

    private fun String.isNumericListId(): Boolean {
        return isNotBlank() && all { it.isDigit() }
    }
}

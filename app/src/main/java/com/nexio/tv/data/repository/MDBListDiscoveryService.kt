package com.nexio.tv.data.repository

import android.util.Log
import com.nexio.tv.core.poster.PosterRatingsUrlResolver
import com.nexio.tv.data.local.MDBListDiscoverySnapshotStore
import com.nexio.tv.data.local.MDBListCatalogPreferences
import com.nexio.tv.data.local.MDBListSettingsDataStore
import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
    val items: List<MetaPreview>
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
    private val mdbListApi: MDBListApi,
    private val mdbListSettingsDataStore: MDBListSettingsDataStore,
    private val posterRatingsUrlResolver: PosterRatingsUrlResolver,
    private val snapshotStore: MDBListDiscoverySnapshotStore
) {
    private val snapshotState = MutableStateFlow(MDBListDiscoverySnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshMs = 0L
    private val minRefreshIntervalMs = 30_000L
    private val maxItemsPerRail = 20
    @Volatile
    private var activePosterProvider: PosterRatingsUrlResolver.ActiveProvider? = null

    init {
        snapshotStore.read()?.let { persisted ->
            snapshotState.value = persisted
            lastRefreshMs = persisted.updatedAtMs
        }
    }

    fun observeSnapshot(): Flow<MDBListDiscoverySnapshot> {
        return snapshotState.onStart { ensureFresh(force = false) }
    }

    suspend fun ensureFresh(force: Boolean) {
        val settings = mdbListSettingsDataStore.settings.first()
        activePosterProvider = posterRatingsUrlResolver.getActiveProvider()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) {
            Log.d("MDBListDiscovery", "Skipping refresh enabled=${settings.enabled} apiKeyPresent=${apiKey.isNotBlank()}")
            snapshotState.value = MDBListDiscoverySnapshot()
            snapshotStore.clear()
            return
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastRefreshMs < minRefreshIntervalMs && snapshotState.value.updatedAtMs > 0L) {
            return
        }

        refreshMutex.withLock {
            val lockedNow = System.currentTimeMillis()
            if (!force &&
                lockedNow - lastRefreshMs < minRefreshIntervalMs &&
                snapshotState.value.updatedAtMs > 0L
            ) {
                return
            }

            val personalLists = fetchPersonalLists(apiKey)
            val topLists = fetchTopLists(apiKey)
            val catalogPrefs = mdbListSettingsDataStore.catalogPreferences.first()
            val customCatalogs = fetchSelectedCatalogs(
                apiKey = apiKey,
                personalLists = personalLists,
                topLists = topLists,
                catalogPrefs = catalogPrefs
            )

            Log.d(
                "MDBListDiscovery",
                "Refreshed personal=${personalLists.size} top=${topLists.size} custom=${customCatalogs.size}"
            )

            snapshotState.value = MDBListDiscoverySnapshot(
                personalLists = personalLists,
                topLists = topLists,
                customListCatalogs = customCatalogs,
                updatedAtMs = System.currentTimeMillis()
            )
            snapshotStore.write(snapshotState.value)
            lastRefreshMs = System.currentTimeMillis()
        }
    }

    private suspend fun fetchPersonalLists(apiKey: String): List<MDBListListOption> {
        val arrays = listOfNotNull(
            requestArray(apiKey = apiKey, relativeUrl = "lists/user"),
            requestArray(apiKey = apiKey, relativeUrl = "my/lists"),
            requestArray(apiKey = apiKey, relativeUrl = "lists/me")
        )
        return arrays.asSequence()
            .flatMap { array -> parseListOptions(array, isPersonal = true).asSequence() }
            .distinctBy { it.key }
            .toList()
    }

    private suspend fun fetchTopLists(apiKey: String): List<MDBListListOption> {
        val arrays = listOfNotNull(
            requestArray(apiKey = apiKey, relativeUrl = "lists/top"),
            requestArray(apiKey = apiKey, relativeUrl = "top/lists")
        )
        return arrays.asSequence()
            .flatMap { array -> parseListOptions(array, isPersonal = false).asSequence() }
            .distinctBy { it.key }
            .toList()
    }

    private suspend fun fetchSelectedCatalogs(
        apiKey: String,
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
        }
        if (activeOptions.isEmpty()) return emptyList()

        val orderedKeys = mdbListSettingsDataStore.sanitizeCatalogOrder(
            rawOrder = catalogPrefs.catalogOrder,
            availableKeys = activeOptions.keys
        )

        return orderedKeys.flatMap { key ->
            val option = activeOptions[key] ?: return@flatMap emptyList()
            fetchCatalogForList(apiKey = apiKey, option = option)
        }
    }

    private suspend fun fetchCatalogForList(
        apiKey: String,
        option: MDBListListOption
    ): List<MDBListCustomCatalog> {
        val resolvedListIds = resolveItemListIds(apiKey = apiKey, option = option)
        val payloads = listOfNotNull(
            *resolvedListIds
                .mapNotNull { resolvedId ->
                    requestBodyWithQuery(
                        relativeUrl = "lists/$resolvedId/items",
                        query = mapOf(
                            "apikey" to apiKey,
                            "limit" to maxItemsPerRail.toString(),
                            "offset" to "0",
                            "append_to_response" to "poster"
                        )
                    )
                }
                .toTypedArray(),
            requestBody(apiKey = apiKey, relativeUrl = "lists/${option.owner}/${option.listId}"),
            requestAbsoluteBody("https://mdblist.com/lists/${option.owner}/${option.listId}/json")
        )

        val parsedItems = payloads.asSequence()
            .flatMap { payload -> parseListItemsPayload(payload).asSequence() }
            .distinctBy { "${it.type}:${it.preview.id}" }
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
        val catalogBase = "mdblist_list_${slugify(option.key)}"
        if (movies.isNotEmpty()) {
            catalogs += MDBListCustomCatalog(
                key = option.key,
                catalogId = "${catalogBase}_movies",
                catalogName = "${option.title} (Movies)",
                type = ContentType.MOVIE,
                items = movies
            )
        }
        if (shows.isNotEmpty()) {
            catalogs += MDBListCustomCatalog(
                key = option.key,
                catalogId = "${catalogBase}_shows",
                catalogName = "${option.title} (Shows)",
                type = ContentType.SERIES,
                items = shows
            )
        }
        return catalogs
    }

    private suspend fun requestArray(apiKey: String, relativeUrl: String): JSONArray? {
        return requestBody(apiKey, relativeUrl)?.let(::parseJsonArray)
    }

    private suspend fun requestBody(apiKey: String, relativeUrl: String): String? {
        return try {
            val response = mdbListApi.getRaw(relativeUrl = relativeUrl, apiKey = apiKey)
            if (!response.isSuccessful) {
                Log.d("MDBListDiscovery", "Request failed: $relativeUrl code=${response.code()}")
                return null
            }
            response.body()?.string()?.trim().orEmpty()
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $relativeUrl (${error.message})")
            null
        }
    }

    private suspend fun requestBodyWithQuery(relativeUrl: String, query: Map<String, String>): String? {
        return try {
            val response = mdbListApi.getRawWithQuery(relativeUrl = relativeUrl, query = query)
            if (!response.isSuccessful) {
                Log.d("MDBListDiscovery", "Request failed: $relativeUrl code=${response.code()} query=${query.keys.joinToString(",")}")
                return null
            }
            response.body()?.string()?.trim().orEmpty()
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $relativeUrl (${error.message})")
            null
        }
    }

    private suspend fun resolveItemListIds(apiKey: String, option: MDBListListOption): List<String> {
        if (option.itemListIds.isNotEmpty()) {
            return option.itemListIds
        }

        val detailBody = requestBody(apiKey = apiKey, relativeUrl = "lists/${option.owner}/${option.listId}")
            ?: return emptyList()
        return parseResolvedListIds(detailBody)
    }

    private suspend fun requestAbsoluteBody(url: String): String? {
        return try {
            val response = mdbListApi.getRawWithQuery(relativeUrl = url, query = emptyMap())
            if (!response.isSuccessful) {
                Log.d("MDBListDiscovery", "Request failed: $url code=${response.code()}")
                return null
            }
            response.body()?.string()?.trim().orEmpty()
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
        val preview: MetaPreview
    )

    private fun parseListItems(array: JSONArray): List<ParsedListItem> {
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
                    idsObj?.optString("imdb")
                ).takeIf { it.startsWith("tt", ignoreCase = true) }
                val tmdbId = firstNonBlank(
                    itemObj.optString("tmdb_id"),
                    idsObj?.optString("tmdb")
                )
                val contentId = imdbId
                    ?: tmdbId.takeIf { it.isNotBlank() }?.let { "tmdb:$it" }
                    ?: firstNonBlank(
                        itemObj.optString("id"),
                        itemObj.optString("slug"),
                        idsObj?.optString("slug"),
                        idsObj?.optString("trakt")
                    )
                if (contentId.isBlank()) continue

                val title = firstNonBlank(
                    itemObj.optString("title"),
                    itemObj.optString("name"),
                    contentId
                )
                val year = itemObj.optInt("year", 0).takeIf { it > 0 }?.toString()
                val poster = firstNonBlank(
                    itemObj.optString("poster"),
                    itemObj.optString("poster_url"),
                    itemObj.optString("image"),
                    itemObj.optString("backdrop")
                ).takeIf { it.isNotBlank() }

                add(
                    ParsedListItem(
                        type = type,
                        preview = posterRatingsUrlResolver.apply(
                            MetaPreview(
                            id = contentId,
                            type = type,
                            rawType = if (type == ContentType.MOVIE) "movie" else "series",
                            name = title,
                            poster = poster,
                            posterShape = PosterShape.POSTER,
                            background = null,
                            logo = null,
                            description = null,
                            releaseInfo = year,
                            imdbRating = null,
                            genres = emptyList()
                        ),
                            activePosterProvider
                        )
                    )
                )
            }
        }
    }

    private fun parseListItemsPayload(raw: String): List<ParsedListItem> {
        if (raw.isBlank()) return emptyList()

        return try {
            when {
                raw.startsWith("[") -> parseListItems(JSONArray(raw))
                raw.startsWith("{") -> {
                    val obj = JSONObject(raw)
                    val groupedItems = buildList {
                        obj.optJSONArray("movies")?.let { addAll(parseListItems(it)) }
                        obj.optJSONArray("shows")?.let { addAll(parseListItems(it)) }
                        obj.optJSONArray("items")?.let { addAll(parseListItems(it)) }
                        obj.optJSONArray("results")?.let { addAll(parseListItems(it)) }
                        when (val data = obj.opt("data")) {
                            is JSONArray -> addAll(parseListItems(data))
                            is JSONObject -> {
                                data.optJSONArray("movies")?.let { addAll(parseListItems(it)) }
                                data.optJSONArray("shows")?.let { addAll(parseListItems(it)) }
                                data.optJSONArray("items")?.let { addAll(parseListItems(it)) }
                                data.optJSONArray("results")?.let { addAll(parseListItems(it)) }
                            }
                        }
                    }

                    if (groupedItems.isNotEmpty()) {
                        groupedItems
                    } else {
                        parseJsonArray(raw)?.let(::parseListItems).orEmpty()
                    }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
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
}

package com.nexio.tv.data.repository

import android.util.Log
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
    private val mdbListSettingsDataStore: MDBListSettingsDataStore
) {
    private val snapshotState = MutableStateFlow(MDBListDiscoverySnapshot())
    private val refreshMutex = Mutex()
    private var lastRefreshMs = 0L
    private val minRefreshIntervalMs = 30_000L
    private val maxItemsPerRail = 20

    fun observeSnapshot(): Flow<MDBListDiscoverySnapshot> {
        return snapshotState.onStart { ensureFresh(force = false) }
    }

    suspend fun ensureFresh(force: Boolean) {
        val settings = mdbListSettingsDataStore.settings.first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) {
            snapshotState.value = MDBListDiscoverySnapshot()
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

            snapshotState.value = MDBListDiscoverySnapshot(
                personalLists = personalLists,
                topLists = topLists,
                customListCatalogs = customCatalogs,
                updatedAtMs = System.currentTimeMillis()
            )
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
        val arrays = listOfNotNull(
            requestArray(apiKey = apiKey, relativeUrl = "lists/${option.listId}/items"),
            requestArray(apiKey = apiKey, relativeUrl = "lists/${option.owner}/${option.listId}/items"),
            requestArray(apiKey = apiKey, relativeUrl = "lists/${option.listId}"),
            requestArray(apiKey = apiKey, relativeUrl = "lists/${option.owner}/${option.listId}")
        )

        val parsedItems = arrays.asSequence()
            .flatMap { array -> parseListItems(array).asSequence() }
            .take(maxItemsPerRail * 2)
            .toList()

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
        return try {
            val response = mdbListApi.getRaw(relativeUrl = relativeUrl, apiKey = apiKey)
            if (!response.isSuccessful) return null
            val body = response.body()?.string()?.trim().orEmpty()
            parseJsonArray(body)
        } catch (error: Exception) {
            Log.w("MDBListDiscovery", "Request failed: $relativeUrl (${error.message})")
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
                add(
                    MDBListListOption(
                        key = "$prefix:$owner/$listId",
                        owner = owner,
                        listId = listId,
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
                        preview = MetaPreview(
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
                        )
                    )
                )
            }
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

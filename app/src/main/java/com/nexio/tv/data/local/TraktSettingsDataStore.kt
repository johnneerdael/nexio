package com.nexio.tv.data.local

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

object TraktCatalogIds {
    const val UP_NEXT = "trakt_up_next"
    const val TRENDING_MOVIES = "trakt_trending_movies"
    const val TRENDING_SHOWS = "trakt_trending_shows"
    const val POPULAR_MOVIES = "trakt_popular_movies"
    const val POPULAR_SHOWS = "trakt_popular_shows"
    const val RECOMMENDED_MOVIES = "trakt_recommended_movies"
    const val RECOMMENDED_SHOWS = "trakt_recommended_shows"
    const val CALENDAR = "trakt_calendar_next_7_days"

    val BUILT_IN_ORDER: List<String> = listOf(
        UP_NEXT,
        TRENDING_MOVIES,
        TRENDING_SHOWS,
        POPULAR_MOVIES,
        POPULAR_SHOWS,
        RECOMMENDED_MOVIES,
        RECOMMENDED_SHOWS,
        CALENDAR
    )

    val DEFAULT_ENABLED: Set<String> = emptySet()
}

data class TraktCatalogPreferences(
    val enabledCatalogs: Set<String> = TraktCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = TraktCatalogIds.BUILT_IN_ORDER,
    val selectedPopularListKeys: Set<String> = emptySet()
)

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TraktSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "trakt_settings"
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val dismissedNextUpKeysKey = stringSetPreferencesKey("dismissed_next_up_keys")
    private val dismissedRecommendationKeysKey = stringSetPreferencesKey("dismissed_recommendation_keys")
    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")
    private val selectedPopularListKeysKey = stringSetPreferencesKey("selected_popular_list_keys")

    val dismissedNextUpKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            prefs[dismissedNextUpKeysKey] ?: emptySet()
        }
    }

    val dismissedRecommendationKeys: Flow<Set<String>> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            prefs[dismissedRecommendationKeysKey] ?: emptySet()
        }
    }

    val catalogPreferences: Flow<TraktCatalogPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            val enabled = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: TraktCatalogIds.DEFAULT_ENABLED)
            val order = sanitizeCatalogOrder(
                prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TraktCatalogIds.BUILT_IN_ORDER
            )
            val selectedListKeys = prefs[selectedPopularListKeysKey] ?: emptySet()
            TraktCatalogPreferences(
                enabledCatalogs = enabled,
                catalogOrder = order,
                selectedPopularListKeys = selectedListKeys
            )
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            prefs[dismissedNextUpKeysKey] = current + key
        }
    }

    suspend fun addDismissedRecommendationKey(key: String) {
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[dismissedRecommendationKeysKey] ?: emptySet()
            prefs[dismissedRecommendationKeysKey] = current + key
        }
    }

    suspend fun clearDismissedRecommendationKey(key: String) {
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[dismissedRecommendationKeysKey] ?: emptySet()
            prefs[dismissedRecommendationKeysKey] = current - key
        }
    }

    suspend fun setCatalogEnabled(catalogId: String, enabled: Boolean) {
        if (catalogId !in TraktCatalogIds.BUILT_IN_ORDER) return
        store().edit { prefs ->
            val current = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: TraktCatalogIds.DEFAULT_ENABLED)
            prefs[catalogEnabledSetKey] = if (enabled) current + catalogId else current - catalogId
        }
    }

    suspend fun moveCatalog(catalogId: String, direction: Int) {
        if (catalogId !in TraktCatalogIds.BUILT_IN_ORDER) return
        if (direction == 0) return
        store().edit { prefs ->
            val currentOrder = sanitizeCatalogOrder(
                prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TraktCatalogIds.BUILT_IN_ORDER
            ).toMutableList()
            val index = currentOrder.indexOf(catalogId)
            if (index == -1) return@edit
            val target = (index + direction).coerceIn(0, currentOrder.lastIndex)
            if (target == index) return@edit
            currentOrder.removeAt(index)
            currentOrder.add(target, catalogId)
            prefs[catalogOrderCsvKey] = sanitizeCatalogOrder(currentOrder).joinToString(",")
        }
    }

    suspend fun setPopularListSelected(listKey: String, selected: Boolean) {
        if (listKey.isBlank()) return
        store().edit { prefs ->
            val current = prefs[selectedPopularListKeysKey] ?: emptySet()
            prefs[selectedPopularListKeysKey] = if (selected) current + listKey else current - listKey
        }
    }

    suspend fun setCatalogPreferences(
        enabledCatalogs: Set<String>,
        catalogOrder: List<String>,
        selectedPopularListKeys: Set<String>
    ) {
        store().edit { prefs ->
            prefs[catalogEnabledSetKey] = sanitizeEnabledCatalogs(enabledCatalogs)
            prefs[catalogOrderCsvKey] = sanitizeCatalogOrder(catalogOrder).joinToString(",")
            prefs[selectedPopularListKeysKey] = selectedPopularListKeys.filter { it.isNotBlank() }.toSet()
        }
    }

    private fun sanitizeEnabledCatalogs(value: Set<String>): Set<String> {
        val known = TraktCatalogIds.BUILT_IN_ORDER.toSet()
        return value.filterTo(linkedSetOf()) { it in known }
    }

    private fun sanitizeCatalogOrder(raw: List<String>): List<String> {
        val known = TraktCatalogIds.BUILT_IN_ORDER.toSet()
        val uniqueKnown = raw.filter { it in known }.distinct()
        return uniqueKnown + TraktCatalogIds.BUILT_IN_ORDER.filterNot { it in uniqueKnown }
    }
}

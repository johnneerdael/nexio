package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.traktSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "trakt_settings"
)

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

    // Preserve current behavior by default and let users opt-in to the extra rails.
    val DEFAULT_ENABLED: Set<String> = setOf(
        UP_NEXT,
        RECOMMENDED_MOVIES,
        RECOMMENDED_SHOWS,
        CALENDAR
    )
}

data class TraktCatalogPreferences(
    val enabledCatalogs: Set<String> = TraktCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = TraktCatalogIds.BUILT_IN_ORDER,
    val selectedPopularListKeys: Set<String> = emptySet()
)

@Singleton
class TraktSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        const val CONTINUE_WATCHING_DAYS_CAP_ALL = 0
        const val DEFAULT_CONTINUE_WATCHING_DAYS_CAP = 60
        const val DEFAULT_SHOW_UNAIRED_NEXT_UP = true
        const val MIN_CONTINUE_WATCHING_DAYS_CAP = 7
        const val MAX_CONTINUE_WATCHING_DAYS_CAP = 365
    }

    private val dataStore = context.traktSettingsDataStore
    private fun store() = dataStore

    private val continueWatchingDaysCapKey = intPreferencesKey("continue_watching_days_cap")
    private val dismissedNextUpKeysKey = stringSetPreferencesKey("dismissed_next_up_keys")
    private val dismissedRecommendationKeysKey = stringSetPreferencesKey("dismissed_recommendation_keys")
    private val showUnairedNextUpKey = booleanPreferencesKey("show_unaired_next_up")
    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")
    private val selectedPopularListKeysKey = stringSetPreferencesKey("selected_popular_list_keys")

    val continueWatchingDaysCap: Flow<Int> = dataStore.data.map { prefs ->
        normalizeContinueWatchingDaysCap(
            prefs[continueWatchingDaysCapKey] ?: DEFAULT_CONTINUE_WATCHING_DAYS_CAP
        )
    }

    val dismissedNextUpKeys: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[dismissedNextUpKeysKey] ?: emptySet()
    }

    val dismissedRecommendationKeys: Flow<Set<String>> = dataStore.data.map { prefs ->
        prefs[dismissedRecommendationKeysKey] ?: emptySet()
    }

    val showUnairedNextUp: Flow<Boolean> = dataStore.data.map { prefs ->
        prefs[showUnairedNextUpKey] ?: DEFAULT_SHOW_UNAIRED_NEXT_UP
    }

    val catalogPreferences: Flow<TraktCatalogPreferences> = dataStore.data.map { prefs ->
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

    suspend fun setContinueWatchingDaysCap(days: Int) {
        store().edit { prefs ->
            prefs[continueWatchingDaysCapKey] = normalizeContinueWatchingDaysCap(days)
        }
    }

    private fun normalizeContinueWatchingDaysCap(days: Int): Int {
        return if (days == CONTINUE_WATCHING_DAYS_CAP_ALL) {
            CONTINUE_WATCHING_DAYS_CAP_ALL
        } else {
            days.coerceIn(MIN_CONTINUE_WATCHING_DAYS_CAP, MAX_CONTINUE_WATCHING_DAYS_CAP)
        }
    }

    suspend fun addDismissedNextUpKey(key: String) {
        if (key.isBlank()) return
        store().edit { prefs ->
            val current = prefs[dismissedNextUpKeysKey] ?: emptySet()
            prefs[dismissedNextUpKeysKey] = current + key
        }
    }

    suspend fun setShowUnairedNextUp(enabled: Boolean) {
        store().edit { prefs ->
            prefs[showUnairedNextUpKey] = enabled
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

package com.nexio.tv.data.local

import androidx.datastore.preferences.core.booleanPreferencesKey
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

object TmdbCatalogIds {
    const val TRENDING_MOVIES = "tmdb_trending_movies"
    const val TRENDING_SERIES = "tmdb_trending_series"
    const val POPULAR_MOVIES = "tmdb_popular_movies"
    const val POPULAR_SERIES = "tmdb_popular_series"
    const val YEAR_MOVIES = "tmdb_year_movies"
    const val YEAR_SERIES = "tmdb_year_series"
    const val LANGUAGE_MOVIES = "tmdb_language_movies"
    const val LANGUAGE_SERIES = "tmdb_language_series"

    val BUILT_IN_ORDER: List<String> = listOf(
        TRENDING_MOVIES,
        TRENDING_SERIES,
        POPULAR_MOVIES,
        POPULAR_SERIES,
        YEAR_MOVIES,
        YEAR_SERIES,
        LANGUAGE_MOVIES,
        LANGUAGE_SERIES
    )

    val DEFAULT_ENABLED: Set<String> = setOf(
        TRENDING_MOVIES,
        TRENDING_SERIES,
        POPULAR_MOVIES,
        POPULAR_SERIES
    )
}

data class TmdbCatalogPreferences(
    val enabledCatalogs: Set<String> = TmdbCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = TmdbCatalogIds.BUILT_IN_ORDER,
    val includeAdult: Boolean = false
) {
    fun enabledCatalogIds(): Set<String> {
        val sanitized = sanitized()
        return sanitized.catalogOrder
            .filterTo(linkedSetOf()) { it in sanitized.enabledCatalogs }
    }

    fun sanitized(): TmdbCatalogPreferences {
        val known = TmdbCatalogIds.BUILT_IN_ORDER.toSet()
        val sanitizedEnabled = enabledCatalogs.filterTo(linkedSetOf()) { it in known }
        val sanitizedOrder = catalogOrder.filter { it in known }.distinct()
        return copy(
            enabledCatalogs = sanitizedEnabled,
            catalogOrder = sanitizedOrder + TmdbCatalogIds.BUILT_IN_ORDER.filterNot { it in sanitizedOrder }
        )
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class TmdbCatalogSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "tmdb_catalog_settings"
    }

    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")
    private val includeAdultKey = booleanPreferencesKey("include_adult")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val catalogPreferences: Flow<TmdbCatalogPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            TmdbCatalogPreferences(
                enabledCatalogs = prefs[catalogEnabledSetKey] ?: TmdbCatalogIds.DEFAULT_ENABLED,
                catalogOrder = prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TmdbCatalogIds.BUILT_IN_ORDER,
                includeAdult = prefs[includeAdultKey] ?: false
            ).sanitized()
        }
    }

    suspend fun setCatalogEnabled(catalogId: String, enabled: Boolean) {
        if (catalogId !in TmdbCatalogIds.BUILT_IN_ORDER) return
        store().edit { prefs ->
            val current = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: TmdbCatalogIds.DEFAULT_ENABLED)
            prefs[catalogEnabledSetKey] = if (enabled) current + catalogId else current - catalogId
        }
    }

    suspend fun moveCatalog(catalogId: String, direction: Int) {
        if (catalogId !in TmdbCatalogIds.BUILT_IN_ORDER) return
        if (direction == 0) return
        store().edit { prefs ->
            val currentOrder = sanitizeCatalogOrder(
                prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: TmdbCatalogIds.BUILT_IN_ORDER
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

    suspend fun setIncludeAdult(enabled: Boolean) {
        store().edit { prefs ->
            prefs[includeAdultKey] = enabled
        }
    }

    private fun sanitizeEnabledCatalogs(value: Set<String>): Set<String> {
        val known = TmdbCatalogIds.BUILT_IN_ORDER.toSet()
        return value.filterTo(linkedSetOf()) { it in known }
    }

    private fun sanitizeCatalogOrder(raw: List<String>): List<String> {
        val known = TmdbCatalogIds.BUILT_IN_ORDER.toSet()
        val uniqueKnown = raw.filter { it in known }.distinct()
        return uniqueKnown + TmdbCatalogIds.BUILT_IN_ORDER.filterNot { it in uniqueKnown }
    }
}

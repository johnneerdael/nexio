package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.simklSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "simkl_settings"
)

object SimklCatalogIds {
    const val TV_TRENDING_TODAY = "simkl_tv_trending_today"
    const val TV_TRENDING_WEEK = "simkl_tv_trending_week"
    const val TV_TRENDING_MONTH = "simkl_tv_trending_month"
    const val ANIME_TRENDING_TODAY = "simkl_anime_trending_today"
    const val ANIME_TRENDING_WEEK = "simkl_anime_trending_week"
    const val ANIME_TRENDING_MONTH = "simkl_anime_trending_month"
    const val MOVIE_TRENDING_TODAY = "simkl_movie_trending_today"
    const val MOVIE_TRENDING_WEEK = "simkl_movie_trending_week"
    const val MOVIE_TRENDING_MONTH = "simkl_movie_trending_month"
    const val DVD_RELEASES = "simkl_dvd_releases"

    val BUILT_IN_ORDER: List<String> = listOf(
        TV_TRENDING_TODAY,
        TV_TRENDING_WEEK,
        TV_TRENDING_MONTH,
        MOVIE_TRENDING_TODAY,
        MOVIE_TRENDING_WEEK,
        MOVIE_TRENDING_MONTH,
        ANIME_TRENDING_TODAY,
        ANIME_TRENDING_WEEK,
        ANIME_TRENDING_MONTH,
        DVD_RELEASES
    )

    val DEFAULT_ENABLED: Set<String> = emptySet()
}

data class SimklCatalogPreferences(
    val enabledCatalogs: Set<String> = SimklCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = SimklCatalogIds.BUILT_IN_ORDER
)

@Singleton
class SimklSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.simklSettingsDataStore
    private fun store() = dataStore

    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")

    val catalogPreferences: Flow<SimklCatalogPreferences> = dataStore.data.map { prefs ->
        val enabled = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: SimklCatalogIds.DEFAULT_ENABLED)
        val order = sanitizeCatalogOrder(
            prefs[catalogOrderCsvKey]
                ?.split(',')
                ?.map { it.trim() }
                ?.filter { it.isNotBlank() }
                ?: SimklCatalogIds.BUILT_IN_ORDER
        )
        SimklCatalogPreferences(
            enabledCatalogs = enabled,
            catalogOrder = order
        )
    }

    suspend fun setCatalogEnabled(catalogId: String, enabled: Boolean) {
        if (catalogId !in SimklCatalogIds.BUILT_IN_ORDER) return
        store().edit { prefs ->
            val current = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: SimklCatalogIds.DEFAULT_ENABLED)
            prefs[catalogEnabledSetKey] = if (enabled) current + catalogId else current - catalogId
        }
    }

    suspend fun setCatalogPreferences(enabledCatalogs: Set<String>, catalogOrder: List<String>) {
        store().edit { prefs ->
            prefs[catalogEnabledSetKey] = sanitizeEnabledCatalogs(enabledCatalogs)
            prefs[catalogOrderCsvKey] = sanitizeCatalogOrder(catalogOrder).joinToString(",")
        }
    }

    private fun sanitizeEnabledCatalogs(value: Set<String>): Set<String> {
        val known = SimklCatalogIds.BUILT_IN_ORDER.toSet()
        return value.filterTo(linkedSetOf()) { it in known }
    }

    private fun sanitizeCatalogOrder(raw: List<String>): List<String> {
        val known = SimklCatalogIds.BUILT_IN_ORDER.toSet()
        val uniqueKnown = raw.filter { it in known }.distinct()
        return uniqueKnown + SimklCatalogIds.BUILT_IN_ORDER.filterNot { it in uniqueKnown }
    }
}

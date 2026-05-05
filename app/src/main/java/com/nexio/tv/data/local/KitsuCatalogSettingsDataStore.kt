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

object KitsuCatalogIds {
    const val TRENDING_ANIME = "kitsu_trending_anime"
    const val HIGHEST_RATED_ANIME = "kitsu_highest_rated_anime"
    const val POPULAR_ANIME = "kitsu_popular_anime"
    const val POPULAR_ACTION_ANIME = "kitsu_popular_action_anime"
    const val POPULAR_DRAMA_ANIME = "kitsu_popular_drama_anime"
    const val POPULAR_COMEDY_ANIME = "kitsu_popular_comedy_anime"
    const val POPULAR_FANTASY_ANIME = "kitsu_popular_fantasy_anime"
    const val POPULAR_ROMANCE_ANIME = "kitsu_popular_romance_anime"
    const val POPULAR_ADVENTURE_ANIME = "kitsu_popular_adventure_anime"

    val BUILT_IN_ORDER: List<String> = listOf(
        TRENDING_ANIME,
        HIGHEST_RATED_ANIME,
        POPULAR_ANIME,
        POPULAR_ACTION_ANIME,
        POPULAR_DRAMA_ANIME,
        POPULAR_COMEDY_ANIME,
        POPULAR_FANTASY_ANIME,
        POPULAR_ROMANCE_ANIME,
        POPULAR_ADVENTURE_ANIME
    )

    val DEFAULT_ENABLED: Set<String> = emptySet()
}

data class KitsuCatalogPreferences(
    val enabledCatalogs: Set<String> = KitsuCatalogIds.DEFAULT_ENABLED,
    val catalogOrder: List<String> = KitsuCatalogIds.BUILT_IN_ORDER
) {
    fun enabledCatalogIds(): Set<String> {
        val sanitized = sanitized()
        return sanitized.catalogOrder
            .filterTo(linkedSetOf()) { it in sanitized.enabledCatalogs }
    }

    fun sanitized(): KitsuCatalogPreferences {
        val known = KitsuCatalogIds.BUILT_IN_ORDER.toSet()
        val sanitizedEnabled = enabledCatalogs.filterTo(linkedSetOf()) { it in known }
        val sanitizedOrder = catalogOrder.filter { it in known }.distinct()
        return copy(
            enabledCatalogs = sanitizedEnabled,
            catalogOrder = sanitizedOrder + KitsuCatalogIds.BUILT_IN_ORDER.filterNot { it in sanitizedOrder }
        )
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class KitsuCatalogSettingsDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "kitsu_catalog_settings"
    }

    private val catalogEnabledSetKey = stringSetPreferencesKey("catalog_enabled_set")
    private val catalogOrderCsvKey = stringPreferencesKey("catalog_order_csv")

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    val catalogPreferences: Flow<KitsuCatalogPreferences> = profileManager.activeProfileId.flatMapLatest { pid ->
        store(pid).data.map { prefs ->
            KitsuCatalogPreferences(
                enabledCatalogs = prefs[catalogEnabledSetKey] ?: KitsuCatalogIds.DEFAULT_ENABLED,
                catalogOrder = prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: KitsuCatalogIds.BUILT_IN_ORDER
            ).sanitized()
        }
    }

    suspend fun setCatalogEnabled(catalogId: String, enabled: Boolean) {
        if (catalogId !in KitsuCatalogIds.BUILT_IN_ORDER) return
        store().edit { prefs ->
            val current = sanitizeEnabledCatalogs(prefs[catalogEnabledSetKey] ?: KitsuCatalogIds.DEFAULT_ENABLED)
            prefs[catalogEnabledSetKey] = if (enabled) current + catalogId else current - catalogId
        }
    }

    suspend fun setEnabledCatalogs(enabled: Set<String>) {
        store().edit { prefs ->
            prefs[catalogEnabledSetKey] = sanitizeEnabledCatalogs(enabled)
        }
    }

    suspend fun setCatalogOrder(order: List<String>) {
        store().edit { prefs ->
            prefs[catalogOrderCsvKey] = sanitizeCatalogOrder(order).joinToString(",")
        }
    }

    suspend fun moveCatalog(catalogId: String, direction: Int) {
        if (catalogId !in KitsuCatalogIds.BUILT_IN_ORDER) return
        if (direction == 0) return
        store().edit { prefs ->
            val currentOrder = sanitizeCatalogOrder(
                prefs[catalogOrderCsvKey]
                    ?.split(',')
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?: KitsuCatalogIds.BUILT_IN_ORDER
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

    private fun sanitizeEnabledCatalogs(value: Set<String>): Set<String> {
        val known = KitsuCatalogIds.BUILT_IN_ORDER.toSet()
        return value.filterTo(linkedSetOf()) { it in known }
    }

    private fun sanitizeCatalogOrder(raw: List<String>): List<String> {
        val known = KitsuCatalogIds.BUILT_IN_ORDER.toSet()
        val uniqueKnown = raw.filter { it in known }.distinct()
        return uniqueKnown + KitsuCatalogIds.BUILT_IN_ORDER.filterNot { it in uniqueKnown }
    }
}

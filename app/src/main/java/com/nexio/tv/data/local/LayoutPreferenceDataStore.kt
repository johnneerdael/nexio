package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.domain.model.HomeLayout
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.layoutPreferenceDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "layout_settings"
)

@Singleton
class LayoutPreferenceDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val DEFAULT_POSTER_CARD_WIDTH_DP = 126
        private const val DEFAULT_POSTER_CARD_HEIGHT_DP = 189
        private const val DEFAULT_POSTER_CARD_CORNER_RADIUS_DP = 12
        private const val DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 3
        private const val MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 0
    }

    private val dataStore = context.layoutPreferenceDataStore
    private fun store() = dataStore

    private val gson = Gson()

    private val layoutKey = stringPreferencesKey("selected_layout")
    private val hasChosenKey = booleanPreferencesKey("has_chosen_layout")
    private val heroCatalogKey = stringPreferencesKey("hero_catalog_key")
    private val heroCatalogKeysKey = stringPreferencesKey("hero_catalog_keys")
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val sidebarCollapsedKey = booleanPreferencesKey("sidebar_collapsed_by_default")
    private val modernSidebarEnabledKey = booleanPreferencesKey("modern_sidebar_enabled")
    private val legacyModernSidebarEnabledKey = booleanPreferencesKey("glass_sidepanel_enabled")
    private val modernSidebarBlurEnabledKey = booleanPreferencesKey("modern_sidebar_blur_enabled")
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    private val heroSectionEnabledKey = booleanPreferencesKey("hero_section_enabled")
    private val searchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
    private val posterLabelsEnabledKey = booleanPreferencesKey("poster_labels_enabled")
    private val catalogAddonNameEnabledKey = booleanPreferencesKey("catalog_addon_name_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    private val focusedPosterBackdropExpandEnabledKey = booleanPreferencesKey("focused_poster_backdrop_expand_enabled")
    private val focusedPosterBackdropExpandDelaySecondsKey = intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")
    private val posterCardWidthDpKey = intPreferencesKey("poster_card_width_dp")
    private val posterCardHeightDpKey = intPreferencesKey("poster_card_height_dp")
    private val posterCardCornerRadiusDpKey = intPreferencesKey("poster_card_corner_radius_dp")
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")

    private fun <T> profileFlow(extract: (prefs: Preferences) -> T): Flow<T> =
        dataStore.data.map { prefs -> extract(prefs) }

    val selectedLayout: Flow<HomeLayout> = profileFlow { prefs ->
        val layoutName = prefs[layoutKey] ?: HomeLayout.MODERN.name
        try {
            HomeLayout.valueOf(layoutName)
        } catch (e: IllegalArgumentException) {
            HomeLayout.MODERN
        }
    }

    val hasChosenLayout: Flow<Boolean> = profileFlow { prefs ->
        prefs[hasChosenKey] ?: false
    }

    val heroCatalogSelections: Flow<List<String>> = profileFlow { prefs ->
        val multiSelection = parseCatalogKeys(prefs[heroCatalogKeysKey])
        if (multiSelection.isNotEmpty()) {
            multiSelection
        } else {
            prefs[heroCatalogKey]
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
                ?.let(::listOf)
                .orEmpty()
        }
    }

    val heroCatalogSelection: Flow<String?> = heroCatalogSelections.map { selections ->
        selections.firstOrNull()
    }

    val homeCatalogOrderKeys: Flow<List<String>> = profileFlow { prefs ->
        parseCatalogKeys(prefs[homeCatalogOrderKeysKey])
    }

    val disabledHomeCatalogKeys: Flow<List<String>> = profileFlow { prefs ->
        parseCatalogKeys(prefs[disabledHomeCatalogKeysKey])
    }

    val sidebarCollapsedByDefault: Flow<Boolean> = profileFlow { prefs ->
        val modernSidebarEnabled =
            prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
        if (modernSidebarEnabled) {
            false
        } else {
            prefs[sidebarCollapsedKey] ?: false
        }
    }

    val modernSidebarEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
    }

    val modernSidebarBlurEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernSidebarBlurEnabledKey] ?: false
    }

    val modernLandscapePostersEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[modernLandscapePostersEnabledKey] ?: false
    }

    val heroSectionEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[heroSectionEnabledKey] ?: true
    }

    val searchDiscoverEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[searchDiscoverEnabledKey] ?: true
    }

    val posterLabelsEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[posterLabelsEnabledKey] ?: true
    }

    val catalogAddonNameEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogAddonNameEnabledKey] ?: true
    }

    val catalogTypeSuffixEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[catalogTypeSuffixEnabledKey] ?: true
    }

    val focusedPosterBackdropExpandEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropExpandEnabledKey] ?: false
    }

    val focusedPosterBackdropExpandDelaySeconds: Flow<Int> = profileFlow { prefs ->
        (prefs[focusedPosterBackdropExpandDelaySecondsKey]
            ?: DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
            .coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
    }

    val posterCardWidthDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardWidthDpKey] ?: DEFAULT_POSTER_CARD_WIDTH_DP
    }

    val posterCardHeightDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardHeightDpKey] ?: DEFAULT_POSTER_CARD_HEIGHT_DP
    }

    val posterCardCornerRadiusDp: Flow<Int> = profileFlow { prefs ->
        prefs[posterCardCornerRadiusDpKey] ?: DEFAULT_POSTER_CARD_CORNER_RADIUS_DP
    }

    val blurUnwatchedEpisodes: Flow<Boolean> = profileFlow { prefs ->
        prefs[blurUnwatchedEpisodesKey] ?: false
    }

    val preferExternalMetaAddonDetail: Flow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: false
    }

    val hideUnreleasedContent: Flow<Boolean> = profileFlow { prefs ->
        prefs[hideUnreleasedContentKey] ?: false
    }

    suspend fun setLayout(layout: HomeLayout) {
        store().edit { prefs ->
            prefs[layoutKey] = layout.name
            prefs[hasChosenKey] = true
        }
    }

    suspend fun setHeroCatalogKeys(catalogKeys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(catalogKeys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(heroCatalogKeysKey)
                prefs.remove(heroCatalogKey)
            } else {
                prefs[heroCatalogKeysKey] = gson.toJson(normalizedKeys)
                prefs[heroCatalogKey] = normalizedKeys.first()
            }
        }
    }

    suspend fun setHeroCatalogKey(catalogKey: String) {
        setHeroCatalogKeys(listOf(catalogKey))
    }

    suspend fun setHomeCatalogOrderKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(homeCatalogOrderKeysKey)
            } else {
                prefs[homeCatalogOrderKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setDisabledHomeCatalogKeys(keys: List<String>) {
        val normalizedKeys = normalizeCatalogOrderKeys(keys)
        store().edit { prefs ->
            if (normalizedKeys.isEmpty()) {
                prefs.remove(disabledHomeCatalogKeysKey)
            } else {
                prefs[disabledHomeCatalogKeysKey] = gson.toJson(normalizedKeys)
            }
        }
    }

    suspend fun setSidebarCollapsedByDefault(collapsed: Boolean) {
        store().edit { prefs ->
            val modernSidebarEnabled =
                prefs[modernSidebarEnabledKey] ?: prefs[legacyModernSidebarEnabledKey] ?: false
            prefs[sidebarCollapsedKey] = if (modernSidebarEnabled) false else collapsed
        }
    }

    suspend fun setModernSidebarEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarEnabledKey] = enabled
            prefs.remove(legacyModernSidebarEnabledKey)
            if (enabled) {
                prefs[sidebarCollapsedKey] = false
            }
        }
    }

    suspend fun setModernSidebarBlurEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernSidebarBlurEnabledKey] = enabled
        }
    }

    suspend fun setModernLandscapePostersEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[modernLandscapePostersEnabledKey] = enabled
        }
    }

    suspend fun setHeroSectionEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[heroSectionEnabledKey] = enabled
        }
    }

    suspend fun setSearchDiscoverEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[searchDiscoverEnabledKey] = enabled
        }
    }

    suspend fun setPosterLabelsEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[posterLabelsEnabledKey] = enabled
        }
    }

    suspend fun setCatalogAddonNameEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogAddonNameEnabledKey] = enabled
        }
    }

    suspend fun setCatalogTypeSuffixEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[catalogTypeSuffixEnabledKey] = enabled
        }
    }

    suspend fun setFocusedPosterBackdropExpandEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandEnabledKey] = enabled
        }
    }

    suspend fun setFocusedPosterBackdropExpandDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropExpandDelaySecondsKey] =
                seconds.coerceAtLeast(MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS)
        }
    }

    suspend fun setPosterCardWidthDp(widthDp: Int) {
        store().edit { prefs ->
            prefs[posterCardWidthDpKey] = widthDp
        }
    }

    suspend fun setPosterCardHeightDp(heightDp: Int) {
        store().edit { prefs ->
            prefs[posterCardHeightDpKey] = heightDp
        }
    }

    suspend fun setPosterCardCornerRadiusDp(cornerRadiusDp: Int) {
        store().edit { prefs ->
            prefs[posterCardCornerRadiusDpKey] = cornerRadiusDp
        }
    }

    suspend fun setBlurUnwatchedEpisodes(enabled: Boolean) {
        store().edit { prefs ->
            prefs[blurUnwatchedEpisodesKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
        }
    }

    suspend fun setHideUnreleasedContent(enabled: Boolean) {
        store().edit { prefs ->
            prefs[hideUnreleasedContentKey] = enabled
        }
    }

    private fun parseCatalogKeys(json: String?): List<String> {
        if (json.isNullOrBlank()) return emptyList()
        return try {
            val type = object : TypeToken<List<String>>() {}.type
            val parsed = gson.fromJson<List<String>>(json, type).orEmpty()
            normalizeCatalogOrderKeys(parsed)
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun normalizeCatalogOrderKeys(keys: List<String>): List<String> {
        return keys.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .toList()
    }
}

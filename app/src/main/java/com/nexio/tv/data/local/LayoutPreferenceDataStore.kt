package com.nexio.tv.data.local

import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget
import com.nexio.tv.domain.model.HomeLayout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import javax.inject.Inject
import javax.inject.Singleton

private val migrationHideUnreleasedDefaultEnabledKey =
    booleanPreferencesKey("migration_hide_unreleased_default_enabled")
private val hideUnreleasedContentKey = booleanPreferencesKey("hide_unreleased_content")

internal fun coerceScreensaverDelaySeconds(seconds: Int): Int = seconds.coerceIn(
    LayoutPreferenceDataStore.MIN_SCREENSAVER_DELAY_SECONDS,
    LayoutPreferenceDataStore.MAX_SCREENSAVER_DELAY_SECONDS
)

internal fun applyLayoutPreferenceMigrations(prefs: MutablePreferences) {
    val hideUnreleasedDefaultMigrated = prefs[migrationHideUnreleasedDefaultEnabledKey] ?: false
    if (!hideUnreleasedDefaultMigrated) {
        // This preference is retired from UX; keep unreleased titles visible by default.
        prefs[hideUnreleasedContentKey] = false
        prefs[migrationHideUnreleasedDefaultEnabledKey] = true
    }
}

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class LayoutPreferenceDataStore @Inject constructor(
    private val factory: ProfileDataStoreFactory,
    private val profileManager: ProfileManager
) {
    companion object {
        private const val FEATURE = "layout_settings"
        private const val DEFAULT_POSTER_CARD_WIDTH_DP = 126
        private const val DEFAULT_POSTER_CARD_HEIGHT_DP = 189
        private const val DEFAULT_POSTER_CARD_CORNER_RADIUS_DP = 12
        private const val DEFAULT_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 3
        private const val MIN_FOCUSED_POSTER_BACKDROP_EXPAND_DELAY_SECONDS = 0
        internal const val DEFAULT_SCREENSAVER_DELAY_SECONDS = 300
        internal const val MIN_SCREENSAVER_DELAY_SECONDS = 60
        internal const val MAX_SCREENSAVER_DELAY_SECONDS = 600
    }

    private fun store(profileId: Int = profileManager.activeProfileId.value) =
        factory.get(profileId, FEATURE)

    private val gson = Gson()

    private val layoutKey = stringPreferencesKey("selected_layout")
    private val hasChosenKey = booleanPreferencesKey("has_chosen_layout")
    private val heroCatalogKey = stringPreferencesKey("hero_catalog_key")
    private val heroCatalogKeysKey = stringPreferencesKey("hero_catalog_keys")
    private val homeCatalogOrderKeysKey = stringPreferencesKey("home_catalog_order_keys")
    private val disabledHomeCatalogKeysKey = stringPreferencesKey("disabled_home_catalog_keys")
    private val modernLandscapePostersEnabledKey = booleanPreferencesKey("modern_landscape_posters_enabled")
    private val heroSectionEnabledKey = booleanPreferencesKey("hero_section_enabled")
    private val searchDiscoverEnabledKey = booleanPreferencesKey("search_discover_enabled")
    private val posterLabelsEnabledKey = booleanPreferencesKey("poster_labels_enabled")
    private val catalogAddonNameEnabledKey = booleanPreferencesKey("catalog_addon_name_enabled")
    private val catalogTypeSuffixEnabledKey = booleanPreferencesKey("catalog_type_suffix_enabled")
    private val focusedPosterBackdropExpandEnabledKey = booleanPreferencesKey("focused_poster_backdrop_expand_enabled")
    private val focusedPosterBackdropExpandDelaySecondsKey = intPreferencesKey("focused_poster_backdrop_expand_delay_seconds")
    private val focusedPosterBackdropTrailerEnabledKey = booleanPreferencesKey("focused_poster_backdrop_trailer_enabled")
    private val focusedPosterBackdropTrailerMutedKey = booleanPreferencesKey("focused_poster_backdrop_trailer_muted")
    private val focusedPosterBackdropTrailerPlaybackTargetKey =
        stringPreferencesKey("focused_poster_backdrop_trailer_playback_target")
    private val trailerScreensaverEnabledKey = booleanPreferencesKey("trailer_screensaver_enabled")
    private val screensaverDelaySecondsKey = intPreferencesKey("screensaver_delay_seconds")
    private val posterCardWidthDpKey = intPreferencesKey("poster_card_width_dp")
    private val posterCardHeightDpKey = intPreferencesKey("poster_card_height_dp")
    private val posterCardCornerRadiusDpKey = intPreferencesKey("poster_card_corner_radius_dp")
    private val blurUnwatchedEpisodesKey = booleanPreferencesKey("blur_unwatched_episodes")
    private val detailPageTrailerButtonEnabledKey = booleanPreferencesKey("detail_page_trailer_button_enabled")
    private val preferExternalMetaAddonDetailKey = booleanPreferencesKey("prefer_external_meta_addon_detail")
    private fun <T> profileFlow(extract: (prefs: Preferences) -> T): Flow<T> =
        profileManager.activeProfileId.flatMapLatest { pid ->
            store(pid).data
                .onStart { store(pid).edit { applyLayoutPreferenceMigrations(it) } }
                .map { prefs -> extract(prefs) }
        }

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

    val focusedPosterBackdropTrailerEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerEnabledKey] ?: false
    }

    val focusedPosterBackdropTrailerMuted: Flow<Boolean> = profileFlow { prefs ->
        prefs[focusedPosterBackdropTrailerMutedKey] ?: true
    }

    val focusedPosterBackdropTrailerPlaybackTarget: Flow<FocusedPosterTrailerPlaybackTarget> =
        profileFlow { prefs ->
            val stored = prefs[focusedPosterBackdropTrailerPlaybackTargetKey]
            runCatching { FocusedPosterTrailerPlaybackTarget.valueOf(stored.orEmpty()) }
                .getOrDefault(FocusedPosterTrailerPlaybackTarget.HERO_MEDIA)
        }

    val trailerScreensaverEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[trailerScreensaverEnabledKey] ?: false
    }

    val screensaverDelaySeconds: Flow<Int> = profileFlow { prefs ->
        val stored = prefs[screensaverDelaySecondsKey] ?: DEFAULT_SCREENSAVER_DELAY_SECONDS
        coerceScreensaverDelaySeconds(stored)
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

    val detailPageTrailerButtonEnabled: Flow<Boolean> = profileFlow { prefs ->
        prefs[detailPageTrailerButtonEnabledKey] ?: false
    }

    val preferExternalMetaAddonDetail: Flow<Boolean> = profileFlow { prefs ->
        prefs[preferExternalMetaAddonDetailKey] ?: false
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

    suspend fun setFocusedPosterBackdropTrailerEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerEnabledKey] = enabled
            if (!enabled) {
                prefs[focusedPosterBackdropTrailerMutedKey] = true
                if (prefs[focusedPosterBackdropTrailerPlaybackTargetKey] == null) {
                    prefs[focusedPosterBackdropTrailerPlaybackTargetKey] =
                        FocusedPosterTrailerPlaybackTarget.HERO_MEDIA.name
                }
            }
        }
    }

    suspend fun setFocusedPosterBackdropTrailerMuted(muted: Boolean) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerMutedKey] = muted
        }
    }

    suspend fun setFocusedPosterBackdropTrailerPlaybackTarget(
        target: FocusedPosterTrailerPlaybackTarget
    ) {
        store().edit { prefs ->
            prefs[focusedPosterBackdropTrailerPlaybackTargetKey] = target.name
        }
    }

    suspend fun setTrailerScreensaverEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[trailerScreensaverEnabledKey] = enabled
        }
    }

    suspend fun setScreensaverDelaySeconds(seconds: Int) {
        store().edit { prefs ->
            prefs[screensaverDelaySecondsKey] = coerceScreensaverDelaySeconds(seconds)
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

    suspend fun setDetailPageTrailerButtonEnabled(enabled: Boolean) {
        store().edit { prefs ->
            prefs[detailPageTrailerButtonEnabledKey] = enabled
        }
    }

    suspend fun setPreferExternalMetaAddonDetail(enabled: Boolean) {
        store().edit { prefs ->
            prefs[preferExternalMetaAddonDetailKey] = enabled
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

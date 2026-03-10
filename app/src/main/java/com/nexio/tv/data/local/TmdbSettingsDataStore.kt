package com.nexio.tv.data.local

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.TmdbSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.tmdbSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "tmdb_settings"
)

@Singleton
class TmdbSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.tmdbSettingsDataStore
    private fun store() = dataStore

    private val enabledKey = booleanPreferencesKey("tmdb_enabled")
    private val apiKeyKey = stringPreferencesKey("tmdb_api_key")
    private val useArtworkKey = booleanPreferencesKey("tmdb_use_artwork")
    private val useBasicInfoKey = booleanPreferencesKey("tmdb_use_basic_info")
    private val useDetailsKey = booleanPreferencesKey("tmdb_use_details")
    private val useCreditsKey = booleanPreferencesKey("tmdb_use_credits")
    private val useProductionsKey = booleanPreferencesKey("tmdb_use_productions")
    private val useNetworksKey = booleanPreferencesKey("tmdb_use_networks")
    private val useEpisodesKey = booleanPreferencesKey("tmdb_use_episodes")
    private val useMoreLikeThisKey = booleanPreferencesKey("tmdb_use_more_like_this")
    private val useReviewsKey = booleanPreferencesKey("tmdb_use_reviews")
    private val useCollectionsKey = booleanPreferencesKey("tmdb_use_collections")

    val settings: Flow<TmdbSettings> = dataStore.data.map { prefs ->
        TmdbSettings(
            enabled = prefs[enabledKey] ?: false,
            apiKey = prefs[apiKeyKey] ?: "",
            useArtwork = prefs[useArtworkKey] ?: true,
            useBasicInfo = prefs[useBasicInfoKey] ?: true,
            useDetails = prefs[useDetailsKey] ?: true,
            useCredits = prefs[useCreditsKey] ?: true,
            useProductions = prefs[useProductionsKey] ?: true,
            useNetworks = prefs[useNetworksKey] ?: true,
            useEpisodes = prefs[useEpisodesKey] ?: true,
            useMoreLikeThis = prefs[useMoreLikeThisKey] ?: true,
            useReviews = prefs[useReviewsKey] ?: true,
            useCollections = prefs[useCollectionsKey] ?: true
        )
    }

    suspend fun setEnabled(enabled: Boolean) {
        store().edit { it[enabledKey] = enabled }
    }

    suspend fun setApiKey(apiKey: String) {
        store().edit { it[apiKeyKey] = apiKey.trim() }
    }

    suspend fun setUseArtwork(enabled: Boolean) {
        store().edit { it[useArtworkKey] = enabled }
    }

    suspend fun setUseBasicInfo(enabled: Boolean) {
        store().edit { it[useBasicInfoKey] = enabled }
    }

    suspend fun setUseDetails(enabled: Boolean) {
        store().edit { it[useDetailsKey] = enabled }
    }

    suspend fun setUseCredits(enabled: Boolean) {
        store().edit { it[useCreditsKey] = enabled }
    }

    suspend fun setUseProductions(enabled: Boolean) {
        store().edit { it[useProductionsKey] = enabled }
    }

    suspend fun setUseNetworks(enabled: Boolean) {
        store().edit { it[useNetworksKey] = enabled }
    }

    suspend fun setUseEpisodes(enabled: Boolean) {
        store().edit { it[useEpisodesKey] = enabled }
    }

    suspend fun setUseMoreLikeThis(enabled: Boolean) {
        store().edit { it[useMoreLikeThisKey] = enabled }
    }

    suspend fun setUseReviews(enabled: Boolean) {
        store().edit { it[useReviewsKey] = enabled }
    }

    suspend fun setUseCollections(enabled: Boolean) {
        store().edit { it[useCollectionsKey] = enabled }
    }
}

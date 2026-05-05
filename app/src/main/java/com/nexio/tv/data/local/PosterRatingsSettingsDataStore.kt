package com.nexio.tv.data.local

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nexio.tv.domain.model.ArtworkProviderChoiceKey
import com.nexio.tv.domain.model.ArtworkProviderSelectionSettings
import com.nexio.tv.domain.model.ArtworkProviderSettings
import com.nexio.tv.domain.model.ArtworkTypeKey
import com.nexio.tv.domain.model.PosterRatingsSettings
import com.nexio.tv.domain.model.TopPostersEntitlementSnapshot
import com.nexio.tv.domain.model.toArtworkProviderSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.posterRatingsSettingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "poster_ratings_settings"
)

@Singleton
class PosterRatingsSettingsDataStore @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val dataStore = context.posterRatingsSettingsDataStore

    private val rpdbEnabledKey = booleanPreferencesKey("poster_ratings_rpdb_enabled")
    private val rpdbApiKeyKey = stringPreferencesKey("poster_ratings_rpdb_api_key")
    private val topPostersEnabledKey = booleanPreferencesKey("poster_ratings_top_enabled")
    private val topPostersApiKeyKey = stringPreferencesKey("poster_ratings_top_api_key")

    private val posterProviderKey = stringPreferencesKey("artwork_provider_poster")
    private val logoProviderKey = stringPreferencesKey("artwork_provider_logo")
    private val backdropProviderKey = stringPreferencesKey("artwork_provider_backdrop")
    private val thumbnailProviderKey = stringPreferencesKey("artwork_provider_thumbnail")

    private val topPostersEntitlementValidKey = booleanPreferencesKey("topposters_entitlement_valid")
    private val topPostersEntitlementActiveKey = booleanPreferencesKey("topposters_entitlement_active")
    private val topPostersEntitlementTierKey = intPreferencesKey("topposters_entitlement_tier")
    private val topPostersEntitlementTierNameKey = stringPreferencesKey("topposters_entitlement_tier_name")
    private val topPostersEntitlementEpisodeThumbnailsKey =
        booleanPreferencesKey("topposters_entitlement_episode_thumbnails")
    private val topPostersEntitlementVerifiedAtMsKey =
        longPreferencesKey("topposters_entitlement_verified_at_ms")
    private val topPostersEntitlementExpiresAtMsKey =
        longPreferencesKey("topposters_entitlement_expires_at_ms")

    val settings: Flow<ArtworkProviderSettings> = dataStore.data.map { prefs ->
        val legacySettings = PosterRatingsSettings(
            rpdbEnabled = prefs[rpdbEnabledKey] ?: false,
            rpdbApiKey = prefs[rpdbApiKeyKey] ?: "",
            topPostersEnabled = prefs[topPostersEnabledKey] ?: false,
            topPostersApiKey = prefs[topPostersApiKeyKey] ?: ""
        )
        val migratedSettings = legacySettings.toArtworkProviderSettings()

        migratedSettings.copy(
            selection = ArtworkProviderSelectionSettings(
                posterProvider = prefs.providerChoiceOrNull(posterProviderKey)
                    ?: migratedSettings.selection.posterProvider,
                logoProvider = prefs.providerChoiceOrNull(logoProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT,
                backdropProvider = prefs.providerChoiceOrNull(backdropProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT,
                thumbnailProvider = prefs.providerChoiceOrNull(thumbnailProviderKey)
                    ?: ArtworkProviderChoiceKey.DEFAULT
            ),
            topPostersEntitlement = prefs.topPostersEntitlement()
        )
    }

    suspend fun setRpdbEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[rpdbEnabledKey] = enabled
        }
    }

    suspend fun setRpdbApiKey(apiKey: String) {
        dataStore.edit { it[rpdbApiKeyKey] = apiKey.trim() }
    }

    suspend fun setTopPostersEnabled(enabled: Boolean) {
        dataStore.edit { prefs ->
            prefs[topPostersEnabledKey] = enabled
        }
    }

    suspend fun setTopPostersApiKey(apiKey: String) {
        dataStore.edit { it[topPostersApiKeyKey] = apiKey.trim() }
    }

    suspend fun setProviderSelection(type: ArtworkTypeKey, provider: ArtworkProviderChoiceKey) {
        dataStore.edit { prefs ->
            prefs[type.providerPreferenceKey()] = provider.value
        }
    }

    suspend fun setTopPostersEntitlement(snapshot: TopPostersEntitlementSnapshot?) {
        dataStore.edit { prefs ->
            if (snapshot == null) {
                prefs.remove(topPostersEntitlementValidKey)
                prefs.remove(topPostersEntitlementActiveKey)
                prefs.remove(topPostersEntitlementTierKey)
                prefs.remove(topPostersEntitlementTierNameKey)
                prefs.remove(topPostersEntitlementEpisodeThumbnailsKey)
                prefs.remove(topPostersEntitlementVerifiedAtMsKey)
                prefs.remove(topPostersEntitlementExpiresAtMsKey)
            } else {
                prefs[topPostersEntitlementValidKey] = snapshot.valid
                prefs[topPostersEntitlementActiveKey] = snapshot.isActive
                prefs[topPostersEntitlementTierKey] = snapshot.tier
                prefs[topPostersEntitlementTierNameKey] = snapshot.tierName
                prefs[topPostersEntitlementEpisodeThumbnailsKey] = snapshot.episodeThumbnails
                prefs[topPostersEntitlementVerifiedAtMsKey] = snapshot.verifiedAtMs
                prefs[topPostersEntitlementExpiresAtMsKey] = snapshot.expiresAtMs
            }
        }
    }

    @VisibleForTesting
    suspend fun writeLegacyForTest(
        rpdbEnabled: Boolean,
        rpdbApiKey: String,
        topPostersEnabled: Boolean,
        topPostersApiKey: String
    ) {
        dataStore.edit { prefs ->
            prefs[rpdbEnabledKey] = rpdbEnabled
            prefs[rpdbApiKeyKey] = rpdbApiKey
            prefs[topPostersEnabledKey] = topPostersEnabled
            prefs[topPostersApiKeyKey] = topPostersApiKey
            prefs.remove(posterProviderKey)
            prefs.remove(logoProviderKey)
            prefs.remove(backdropProviderKey)
            prefs.remove(thumbnailProviderKey)
        }
    }

    private fun Preferences.providerChoiceOrNull(
        key: Preferences.Key<String>
    ): ArtworkProviderChoiceKey? {
        val stored = this[key] ?: return null
        return ArtworkProviderChoiceKey.fromStored(stored)
    }

    private fun Preferences.topPostersEntitlement(): TopPostersEntitlementSnapshot? {
        return TopPostersEntitlementSnapshot(
            valid = this[topPostersEntitlementValidKey] ?: return null,
            isActive = this[topPostersEntitlementActiveKey] ?: return null,
            tier = this[topPostersEntitlementTierKey] ?: return null,
            tierName = this[topPostersEntitlementTierNameKey] ?: return null,
            episodeThumbnails = this[topPostersEntitlementEpisodeThumbnailsKey] ?: return null,
            verifiedAtMs = this[topPostersEntitlementVerifiedAtMsKey] ?: return null,
            expiresAtMs = this[topPostersEntitlementExpiresAtMsKey] ?: return null
        )
    }

    private fun ArtworkTypeKey.providerPreferenceKey(): Preferences.Key<String> = when (this) {
        ArtworkTypeKey.POSTER -> posterProviderKey
        ArtworkTypeKey.LOGO -> logoProviderKey
        ArtworkTypeKey.BACKDROP -> backdropProviderKey
        ArtworkTypeKey.THUMBNAIL -> thumbnailProviderKey
    }
}

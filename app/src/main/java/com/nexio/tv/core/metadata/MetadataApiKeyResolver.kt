package com.nexio.tv.core.metadata

import com.nexio.tv.data.local.TmdbSettingsDataStore
import com.nexio.tv.data.local.TvdbSettingsDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first

@Singleton
class MetadataApiKeyResolver @Inject constructor(
    private val tmdbSettingsDataStore: TmdbSettingsDataStore,
    private val tvdbSettingsDataStore: TvdbSettingsDataStore
) {
    constructor(
        tmdbSettingsDataStore: TmdbSettingsDataStore,
        tvdbSettingsDataStore: TvdbSettingsDataStore,
        builtInTmdbKey: () -> String,
        builtInTvdbKey: () -> String
    ) : this(tmdbSettingsDataStore, tvdbSettingsDataStore) {
        this.builtInTmdbKey = builtInTmdbKey
        this.builtInTvdbKey = builtInTvdbKey
    }

    private var builtInTmdbKey: () -> String = { MetadataProviderConfig.builtInTmdbApiKey() }
    private var builtInTvdbKey: () -> String = { MetadataProviderConfig.builtInTvdbApiKey() }

    suspend fun tmdbCredential(): MetadataProviderCredential {
        val settings = tmdbSettingsDataStore.settings.first()
        return MetadataProviderConfig.resolveCredential(
            customApiKey = settings.apiKey,
            builtInApiKey = builtInTmdbKey()
        )
    }

    suspend fun tvdbCredential(): MetadataProviderCredential {
        val settings = tvdbSettingsDataStore.settings.first()
        return MetadataProviderConfig.resolveCredential(
            customApiKey = settings.apiKey,
            builtInApiKey = builtInTvdbKey(),
            pin = settings.subscriberPin
        )
    }
}

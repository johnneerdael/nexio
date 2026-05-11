package com.nexio.tv.data.invalidation

import com.nexio.tv.core.artwork.ArtworkProviderSettingsSource
import com.nexio.tv.data.local.HydratedHomeOverlayStore
import com.nexio.tv.domain.model.toSettingsSignature
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Observes the artwork provider settings flow. When the settings signature
 * changes after the first emission, fires HydratedHomeOverlayStore.markStaleAll
 * so the home pipeline naturally re-hydrates with the new provider routing.
 *
 * The first emission is intentionally a no-op (lastSignature is null);
 * distinctUntilChanged ensures only material settings changes trigger
 * invalidation (API-key changes etc. don't affect signature — see
 * ArtworkProviderSettings.toSettingsSignature).
 *
 * App-scoped: started once from NexioApplication.onCreate; the launched
 * collector lives for the process lifetime.
 */
@Singleton
class ArtworkSettingsInvalidator @Inject constructor(
    private val settingsSource: ArtworkProviderSettingsSource,
    private val overlayStore: HydratedHomeOverlayStore
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            var lastSignature: String? = null
            settingsSource.settings
                .map { it.toSettingsSignature() }
                .distinctUntilChanged()
                .collect { signature ->
                    if (lastSignature != null && lastSignature != signature) {
                        overlayStore.markStaleAll(reason = "settings_change")
                    }
                    lastSignature = signature
                }
        }
    }
}

package com.nuvio.tv.ui.screens.settings

import androidx.lifecycle.ViewModel
import com.nuvio.tv.data.local.LibassRenderType
import com.nuvio.tv.data.local.PlayerSettings
import com.nuvio.tv.data.local.PlayerSettingsDataStore
import com.nuvio.tv.data.local.TrailerSettings
import com.nuvio.tv.data.local.TrailerSettingsDataStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

@HiltViewModel
class PlaybackSettingsViewModel @Inject constructor(
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val trailerSettingsDataStore: TrailerSettingsDataStore
) : ViewModel() {

    val playerSettings: Flow<PlayerSettings> = playerSettingsDataStore.playerSettings
    val trailerSettings: Flow<TrailerSettings> = trailerSettingsDataStore.settings

    suspend fun setTrailerEnabled(enabled: Boolean) {
        trailerSettingsDataStore.setEnabled(enabled)
    }

    suspend fun setTrailerDelaySeconds(seconds: Int) {
        trailerSettingsDataStore.setDelaySeconds(seconds)
    }

    // Audio settings

    suspend fun setDecoderPriority(priority: Int) {
        playerSettingsDataStore.setDecoderPriority(priority)
    }

    suspend fun setTunnelingEnabled(enabled: Boolean) {
        playerSettingsDataStore.setTunnelingEnabled(enabled)
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        playerSettingsDataStore.setSkipSilence(enabled)
    }

    suspend fun setPreferredAudioLanguage(language: String) {
        playerSettingsDataStore.setPreferredAudioLanguage(language)
    }

    suspend fun setLoadingOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setLoadingOverlayEnabled(enabled)
    }

    suspend fun setPauseOverlayEnabled(enabled: Boolean) {
        playerSettingsDataStore.setPauseOverlayEnabled(enabled)
    }

    suspend fun setFrameRateMatching(enabled: Boolean) {
        playerSettingsDataStore.setFrameRateMatching(enabled)
    }

    /**
     * Set whether to use libass for ASS/SSA subtitle rendering
     */
    suspend fun setUseLibass(enabled: Boolean) {
        playerSettingsDataStore.setUseLibass(enabled)
    }

    /**
     * Set the libass render type
     */
    suspend fun setLibassRenderType(renderType: LibassRenderType) {
        playerSettingsDataStore.setLibassRenderType(renderType)
    }
    
    // Subtitle style settings functions
    
    suspend fun setSubtitlePreferredLanguage(language: String) {
        playerSettingsDataStore.setSubtitlePreferredLanguage(language)
    }
    
    suspend fun setSubtitleSecondaryLanguage(language: String?) {
        playerSettingsDataStore.setSubtitleSecondaryLanguage(language)
    }
    
    suspend fun setSubtitleSize(size: Int) {
        playerSettingsDataStore.setSubtitleSize(size)
    }
    
    suspend fun setSubtitleVerticalOffset(offset: Int) {
        playerSettingsDataStore.setSubtitleVerticalOffset(offset)
    }
    
    suspend fun setSubtitleBold(bold: Boolean) {
        playerSettingsDataStore.setSubtitleBold(bold)
    }
    
    suspend fun setSubtitleTextColor(color: Int) {
        playerSettingsDataStore.setSubtitleTextColor(color)
    }
    
    suspend fun setSubtitleBackgroundColor(color: Int) {
        playerSettingsDataStore.setSubtitleBackgroundColor(color)
    }
    
    suspend fun setSubtitleOutlineEnabled(enabled: Boolean) {
        playerSettingsDataStore.setSubtitleOutlineEnabled(enabled)
    }
    
    suspend fun setSubtitleOutlineColor(color: Int) {
        playerSettingsDataStore.setSubtitleOutlineColor(color)
    }
    
    suspend fun setSubtitleOutlineWidth(width: Int) {
        playerSettingsDataStore.setSubtitleOutlineWidth(width)
    }
}

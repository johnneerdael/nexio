package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.core.mpv.NexioMpvRenderState
import com.nexio.tv.core.mpv.NexioMpvSession
import com.nexio.tv.data.local.GeminiSettingsDataStore
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.repository.GeminiSubtitleTranslationService
import com.nexio.tv.data.repository.SkipIntroRepository
import com.nexio.tv.data.repository.TraktScrobbleService
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: com.nexio.tv.domain.repository.SubtitleRepository,
    private val traktScrobbleService: TraktScrobbleService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val geminiSettingsDataStore: GeminiSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.nexio.tv.data.local.LayoutPreferenceDataStore,
    private val geminiSubtitleTranslationService: GeminiSubtitleTranslationService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val emptyMpvRenderState = MutableStateFlow(NexioMpvRenderState())

    private val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        subtitleRepository = subtitleRepository,
        traktScrobbleService = traktScrobbleService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        geminiSettingsDataStore = geminiSettingsDataStore,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        geminiSubtitleTranslationService = geminiSubtitleTranslationService,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    val mpvSession: NexioMpvSession?
        get() = controller.mpvSession

    val mpvRenderState: StateFlow<NexioMpvRenderState>
        get() = controller.mpvSession?.renderState ?: emptyMpvRenderState

    val usesLibmpvBackend: Boolean
        get() = controller.playerBackendPreference == com.nexio.tv.data.local.PlayerPreference.LIBMPV

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun stopAndRelease() {
        controller.stopAndRelease()
    }

    fun scheduleHideControls() {
        controller.scheduleHideControls()
    }

    fun onUserInteraction() {
        controller.onUserInteraction()
    }

    fun hideControls() {
        controller.hideControls()
    }

    fun attachHostActivity(activity: android.app.Activity?) {
        controller.attachHostActivity(activity)
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun pausePlaybackForLifecycle() {
        controller.pausePlaybackForLifecycle()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    fun setLibmpvSubtitleVisibility(visible: Boolean) {
        controller.setLibmpvSubtitleVisibility(visible)
    }

    override fun onCleared() {
        controller.onCleared()
        super.onCleared()
    }
}

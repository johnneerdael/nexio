package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.core.tvdb.TvMetadataRouter
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.repository.SkipIntroRepository
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderOverlayView
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Named

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: com.nexio.tv.domain.repository.SubtitleRepository,
    private val trackingScrobbleService: TrackingScrobbleService,
    private val skipIntroRepository: SkipIntroRepository,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.nexio.tv.data.local.LayoutPreferenceDataStore,
    private val subtitleTranslationService: SubtitleTranslationService,
    private val tvMetadataRouter: TvMetadataRouter,
    private val playbackIdleGateState: PlaybackIdleGateState,
    @Named("playback") private val playbackOkHttpClient: OkHttpClient,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private val controller = PlayerRuntimeController(
        context = context,
        watchProgressRepository = watchProgressRepository,
        metaRepository = metaRepository,
        streamRepository = streamRepository,
        addonRepository = addonRepository,
        subtitleRepository = subtitleRepository,
        trackingScrobbleService = trackingScrobbleService,
        skipIntroRepository = skipIntroRepository,
        playerSettingsDataStore = playerSettingsDataStore,
        debugSettingsDataStore = debugSettingsDataStore,
        subtitleTranslationSettingsDataStore = subtitleTranslationSettingsDataStore,
        theIntroDbSettingsDataStore = theIntroDbSettingsDataStore,
        streamLinkCacheDataStore = streamLinkCacheDataStore,
        layoutPreferenceDataStore = layoutPreferenceDataStore,
        subtitleTranslationService = subtitleTranslationService,
        tvMetadataRouter = tvMetadataRouter,
        playbackIdleGateState = playbackIdleGateState,
        playbackOkHttpClient = playbackOkHttpClient,
        savedStateHandle = savedStateHandle,
        scope = viewModelScope
    )

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val progressUiState: StateFlow<PlayerPlaybackProgressUiState>
        get() = controller.progressUiState

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    val preferredExternalPlayerPackageName = playerSettingsDataStore.playerSettings
        .map { it.preferredExternalPlayerPackageName }
        .distinctUntilChanged()

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

    fun attachMpvView(view: NexioMpvSurfaceView?) {
        controller.attachMpvView(view)
    }

    internal fun setAssSsaRenderOverlayViewProvider(provider: (() -> AssSsaRenderOverlayView?)?) {
        controller.assSsaOverlayViewProvider = provider
    }

    fun startInitialPlaybackIfNeeded() {
        controller.startInitialPlaybackIfNeeded()
    }

    fun pausePlaybackForLifecycle() {
        controller.pausePlaybackForLifecycle()
    }

    fun resumePlaybackForLifecycle() {
        controller.resumePlaybackForLifecycle()
    }

    fun onEvent(event: PlayerEvent) {
        controller.onEvent(event)
    }

    override fun onCleared() {
        controller.onCleared()
        super.onCleared()
    }
}

package com.nexio.tv.ui.screens.player

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.nexio.tv.core.metadata.router.MetadataRouterFacade
import com.nexio.tv.core.metadata.router.resolver.SkipSegmentResolver
import com.nexio.tv.core.playback.PlaybackSessionRegistry
import com.nexio.tv.core.profile.ProfileManager
import com.nexio.tv.core.player.auth.AuthRecoveryInterceptor
import com.nexio.tv.core.player.auth.EgressIpFingerprint
import com.nexio.tv.core.player.PlaybackActivityTracker
import com.nexio.tv.data.integration.playback.OpenSubtitlesHashIntegrationProvider
import com.nexio.tv.data.integration.playback.PlaybackPreflightIntegrationProvider
import com.nexio.tv.data.integration.playback.transport.PlaybackMediaSourceTransport
import com.nexio.tv.data.integration.subtitles.SubtitleSourceDownloadIntegrationProvider
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.local.StreamLinkCacheDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.local.TheIntroDbSettingsDataStore
import com.nexio.tv.data.repository.SubtitleTranslationService
import com.nexio.tv.data.repository.TraktEpisodeMappingService
import com.nexio.tv.data.repository.TrackingScrobbleService
import com.nexio.tv.domain.repository.AddonRepository
import com.nexio.tv.domain.repository.MetaRepository
import com.nexio.tv.domain.repository.StreamRepository
import com.nexio.tv.domain.repository.WatchProgressRepository
import com.nexio.tv.integrations.hyperhdr.session.HyperHdrSessionState
import com.nexio.tv.integrations.hyperhdr.session.HyperHdrSessionStateHolder
import com.nexio.tv.ui.screens.player.ass.AssSsaRenderOverlayView
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val watchProgressRepository: WatchProgressRepository,
    private val metaRepository: MetaRepository,
    private val streamRepository: StreamRepository,
    private val addonRepository: AddonRepository,
    private val subtitleRepository: com.nexio.tv.domain.repository.SubtitleRepository,
    private val trackingScrobbleService: TrackingScrobbleService,
    private val skipSegmentResolver: SkipSegmentResolver,
    private val playerSettingsDataStore: PlayerSettingsDataStore,
    private val debugSettingsDataStore: DebugSettingsDataStore,
    private val subtitleTranslationSettingsDataStore: SubtitleTranslationSettingsDataStore,
    private val theIntroDbSettingsDataStore: TheIntroDbSettingsDataStore,
    private val streamLinkCacheDataStore: StreamLinkCacheDataStore,
    private val layoutPreferenceDataStore: com.nexio.tv.data.local.LayoutPreferenceDataStore,
    private val subtitleTranslationService: SubtitleTranslationService,
    private val subtitleSourceDownloadIntegrationProvider: SubtitleSourceDownloadIntegrationProvider,
    private val metadataRouterFacade: MetadataRouterFacade,
    private val playbackIdleGateState: PlaybackIdleGateState,
    private val playbackActivityTracker: PlaybackActivityTracker,
    private val playbackMediaSourceTransport: PlaybackMediaSourceTransport,
    private val openSubtitlesHashIntegrationProvider: OpenSubtitlesHashIntegrationProvider,
    private val playbackPreflightIntegrationProvider: PlaybackPreflightIntegrationProvider,
    private val profileManager: ProfileManager,
    private val playbackSessionRegistry: PlaybackSessionRegistry,
    private val egressIpFingerprint: EgressIpFingerprint,
    private val authRecoveryInterceptor: AuthRecoveryInterceptor,
    private val hyperHdrSessionStateHolder: HyperHdrSessionStateHolder,
    private val traktEpisodeMappingService: TraktEpisodeMappingService,
    savedStateHandle: SavedStateHandle
) : ViewModel() {
    private var playbackRegistrationToken: String? = null

    private val controller = run {
        val ownerContext = run {
            val session = profileManager.activeProfileSession.value
            com.nexio.tv.core.playback.PlaybackOwnerContext(
                ownerProfileId = session.profileId,
                ownerSessionId = session.sessionId,
                startedAtEpochMs = System.currentTimeMillis().coerceAtLeast(1L)
            )
        }
        playbackRegistrationToken = playbackSessionRegistry.register(ownerContext)
        PlayerRuntimeController(
            context = context,
            watchProgressRepository = watchProgressRepository,
            metaRepository = metaRepository,
            streamRepository = streamRepository,
            addonRepository = addonRepository,
            subtitleRepository = subtitleRepository,
            trackingScrobbleService = trackingScrobbleService,
            skipSegmentResolver = skipSegmentResolver,
            playerSettingsDataStore = playerSettingsDataStore,
            debugSettingsDataStore = debugSettingsDataStore,
            subtitleTranslationSettingsDataStore = subtitleTranslationSettingsDataStore,
            theIntroDbSettingsDataStore = theIntroDbSettingsDataStore,
            streamLinkCacheDataStore = streamLinkCacheDataStore,
            layoutPreferenceDataStore = layoutPreferenceDataStore,
            subtitleTranslationService = subtitleTranslationService,
            subtitleSourceDownloadIntegrationProvider = subtitleSourceDownloadIntegrationProvider,
            metadataRouterFacade = metadataRouterFacade,
            playbackIdleGateState = playbackIdleGateState,
            playbackActivityTracker = playbackActivityTracker,
            playbackMediaSourceTransport = playbackMediaSourceTransport,
            openSubtitlesHashIntegrationProvider = openSubtitlesHashIntegrationProvider,
            playbackPreflightIntegrationProvider = playbackPreflightIntegrationProvider,
            playbackOwnerContext = ownerContext,
            egressIpFingerprint = egressIpFingerprint,
            authRecoveryInterceptor = authRecoveryInterceptor,
            traktEpisodeMappingService = traktEpisodeMappingService,
            savedStateHandle = savedStateHandle,
            scope = viewModelScope
        )
    }

    val uiState: StateFlow<PlayerUiState>
        get() = controller.uiState

    val progressUiState: StateFlow<PlayerPlaybackProgressUiState>
        get() = controller.progressUiState

    val hyperHdrSessionState: StateFlow<HyperHdrSessionState> =
        hyperHdrSessionStateHolder.state

    val exoPlayer: ExoPlayer?
        get() = controller.exoPlayer

    val preferredExternalPlayerPackageName = playerSettingsDataStore.playerSettings
        .map { it.preferredExternalPlayerPackageName }
        .distinctUntilChanged()

    fun getCurrentStreamUrl(): String = controller.getCurrentStreamUrl()

    fun getCurrentHeaders(): Map<String, String> = controller.getCurrentHeaders()

    fun stopAndRelease() {
        // F2-H-07: Session unregistration consolidated to onCleared() only.
        // onCleared() is guaranteed to run on ViewModel destruction regardless of how playback ends,
        // making it the single authoritative teardown point. Calling unregisterPlaybackSession()
        // here as well was idempotent but created a dual-path. The registry unregister is null-safe.
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

    internal fun setAssSsaRenderOverlayViewProvider(provider: (() -> AssSsaRenderOverlayView?)?) {
        controller.setAssSsaRenderOverlayViewProvider(provider)
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

    /** Called by the loading timeout controller after both retry attempts are exhausted. */
    fun surfaceLoadingTimeout() {
        controller.onEvent(PlayerEvent.OnLoadingTimedOut)
    }

    override fun onCleared() {
        controller.onCleared()
        unregisterPlaybackSession()
        super.onCleared()
    }

    private fun unregisterPlaybackSession() {
        playbackRegistrationToken?.let { playbackSessionRegistry.unregister(it) }
        playbackRegistrationToken = null
    }
}

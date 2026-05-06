package com.nexio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import java.io.File
import android.net.Uri
import android.view.KeyEvent
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.view.Choreographer
import android.view.WindowManager
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalView
import androidx.metrics.performance.JankStats
import androidx.metrics.performance.PerformanceMetricsState
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import java.util.Locale
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.media3.common.C
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.tvprovider.media.tv.TvContractCompat
import androidx.tv.material3.DrawerValue
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.ModalNavigationDrawer
import androidx.tv.material3.Surface
import androidx.tv.material3.SurfaceDefaults
import androidx.tv.material3.Text
import androidx.tv.material3.rememberDrawerState
import com.nexio.tv.core.auth.AuthManager
import com.nexio.tv.core.locale.AppLocaleResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerPlaybackRef
import com.nexio.tv.core.metadata.router.resolver.TrailerResolver
import com.nexio.tv.core.metadata.router.resolver.TrailerResolveRequest
import com.nexio.tv.core.metadata.router.resolver.TrailerResolution
import com.nexio.tv.core.metadata.router.resolver.TrailerSurface
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.core.recommendations.AndroidTvChannelPublisher
import com.nexio.tv.data.local.AppOnboardingDataStore
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.repository.benchmark.DeviceCapabilityReportUploader
import com.nexio.tv.data.repository.device.DeviceCapabilityRepository
import com.nexio.tv.data.repository.IdleScreensaverRepository
import com.nexio.tv.data.repository.TrackingProgressService
import com.nexio.tv.data.trailer.TrailerPlaybackSource
import com.nexio.tv.data.trailer.TrailerResolutionResult
import com.nexio.tv.data.trailer.TrailerService
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.domain.model.UserProfile
import com.nexio.tv.core.sync.StartupSyncService
import com.nexio.tv.ui.navigation.NexioNavHost
import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.components.NexioScrollDefaults
import com.nexio.tv.ui.components.ProfileAvatarCircle
import com.nexio.tv.ui.screens.account.AuthQrSignInScreen
import com.nexio.tv.ui.screensaver.IdleScreensaverController
import com.nexio.tv.ui.screensaver.IdleScreensaverOverlay
import com.nexio.tv.ui.screensaver.IdleScreensaverPresentationMode
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverCandidate
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverOverlay
import com.nexio.tv.ui.screensaver.IdleTrailerScreensaverSessionStart
import com.nexio.tv.ui.screensaver.PlaybackIdleGateState
import com.nexio.tv.ui.screensaver.PlaybackIdleGateSnapshot
import com.nexio.tv.ui.screensaver.chooseIdleTrailerCandidates
import com.nexio.tv.ui.screensaver.extractIdleTrailerReleaseYear
import com.nexio.tv.ui.screensaver.trailerResolverContentId
import com.nexio.tv.ui.screens.profile.ProfileSelectionScreen
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import com.nexio.tv.updater.UpdateViewModel
import com.nexio.tv.updater.ui.UpdatePromptDialog
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import androidx.compose.ui.res.stringResource
import com.nexio.tv.core.search.AndroidTvNativeSearchIntent
import com.nexio.tv.R
import com.nexio.tv.DrawerItem
import kotlin.coroutines.cancellation.CancellationException
import kotlin.coroutines.resume

val LocalSidebarExpanded = compositionLocalOf { false }
val LocalContentFocusRequester = compositionLocalOf { FocusRequester.Default }
private const val STARTUP_SPLASH_FIRST_FRAME_TIMEOUT_MS = 4_000L
private const val STARTUP_SPLASH_LAST_FRAME_HOLD_MS = 150L
private const val STARTUP_SPLASH_HARD_TIMEOUT_MS = 12_000L
private const val IDLE_SCREENSAVER_DEBUG_LOG_TAG = "IdleScreensaverDebug"

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.CRIMSON,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val trailerScreensaverEnabled: Boolean = false
)

internal suspend fun resolveIdleTrailerScreensaverPlaybackSource(
    candidate: IdleTrailerScreensaverCandidate,
    playbackRef: TrailerPlaybackRef,
    resolveTrailer: suspend (TrailerResolveRequest) -> TrailerResolution,
    resolvePlaybackSource: suspend (TrailerPlaybackRef) -> TrailerResolutionResult?
): TrailerPlaybackSource? {
    val request = candidate.toTrailerResolverRequest(playbackRef)
    val selectedRef = resolveTrailer(request).selected ?: return null
    val result = resolvePlaybackSource(selectedRef)
    return (result as? TrailerResolutionResult.Playback)?.source
}

private fun IdleTrailerScreensaverCandidate.toTrailerResolverRequest(
    playbackRef: TrailerPlaybackRef
): TrailerResolveRequest {
    return TrailerResolveRequest(
        itemKey = "${itemType.trim().lowercase()}:$itemId",
        title = title,
        year = extractIdleTrailerReleaseYear(releaseInfo),
        stableIds = stableIds,
        fallbackYtIds = (playbackRef as? TrailerPlaybackRef.ItemLookup)?.fallbackYtIds.orEmpty(),
        surface = TrailerSurface.SCREENSAVER,
        type = itemType,
        contentId = trailerResolverContentId(),
        providerCandidates = if (playbackRef is TrailerPlaybackRef.ItemLookup) {
            emptyList()
        } else {
            listOf(playbackRef)
        }
    )
}

private suspend fun TrailerService.resolveIdleTrailerScreensaverPlaybackSource(
    trailerResolver: TrailerResolver,
    candidate: IdleTrailerScreensaverCandidate,
    playbackRef: TrailerPlaybackRef
): TrailerPlaybackSource? {
    return resolveIdleTrailerScreensaverPlaybackSource(
        candidate = candidate,
        playbackRef = playbackRef,
        resolveTrailer = { request ->
            trailerResolver.resolveTrailer(request)
        },
        resolvePlaybackSource = { ref ->
            resolvePlaybackSource(
                ref = ref,
                title = candidate.title,
                year = extractIdleTrailerReleaseYear(candidate.releaseInfo)
            )
        }
    )
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RECOMMENDATION_FEED_KEY = "recommendation_feed_key"
        const val EXTRA_RECOMMENDATION_CONTENT_ID = "recommendation_content_id"
        const val EXTRA_RECOMMENDATION_CONTENT_TYPE = "recommendation_content_type"
        const val EXTRA_RECOMMENDATION_ADDON_BASE_URL = "recommendation_addon_base_url"
        private const val STARTUP_PERF_WINDOW_MS = 12_000L
        private const val STARTUP_DEFERRED_WORK_MIN_DELAY_MS = 2_000L
        internal const val IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS = 5L * 60 * 1000L
        internal const val IDLE_SCREENSAVER_MIN_TIMEOUT_MS = 60L * 1000L
        internal const val IDLE_SCREENSAVER_MAX_TIMEOUT_MS = 10L * 60 * 1000L
        private const val BROWSABLE_REQUEST_COOLDOWN_MS = 24L * 60 * 60 * 1000

        @Volatile
        private var processUiBootstrapped: Boolean = false

        @Volatile
        private var processDeferredStartupWorkCompleted: Boolean = false

        @Volatile
        private var cachedHasSeenAuthQrOnFirstLaunch: Boolean? = null

        @Volatile
        private var cachedMainUiPrefs: MainUiPrefs? = null

        @Volatile
        private var processProfileSelectionGatePassed: Boolean = false

        @Volatile
        var voiceKeyHandler: (() -> Boolean)? = null
    }

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var trackingProgressService: TrackingProgressService

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var authPresenceDataStore: com.nexio.tv.data.local.AuthPresenceDataStore

    @Inject
    lateinit var catalogPriorityHydrationNotifier: com.nexio.tv.core.sync.CatalogPriorityHydrationNotifier

    @Inject
    lateinit var androidTvRecommendationsDataStore: AndroidTvRecommendationsDataStore

    @Inject
    lateinit var androidTvChannelPublisher: AndroidTvChannelPublisher

    @Inject
    lateinit var debugSettingsDataStore: DebugSettingsDataStore

    @Inject
    lateinit var idleScreensaverRepository: IdleScreensaverRepository

    @Inject
    lateinit var trailerService: TrailerService

    @Inject
    lateinit var trailerResolver: TrailerResolver

    @Inject
    lateinit var idleScreensaverController: IdleScreensaverController

    @Inject
    lateinit var playbackIdleGateState: PlaybackIdleGateState

    @Inject
    lateinit var deviceCapabilityRepository: DeviceCapabilityRepository

    @Inject
    lateinit var deviceCapabilityReportUploader: DeviceCapabilityReportUploader

    @Inject
    lateinit var profileManager: com.nexio.tv.core.profile.ProfileManager

    private lateinit var jankStats: JankStats
    private val pendingRecommendationNavigation = mutableStateOf<RecommendationNavigation?>(null)
    private val pendingFeedNavigation = mutableStateOf<RecommendationFeedNavigation?>(null)
    private var pendingBrowsableChannelId: Long? = null
    private var channelBrowsableRequestInFlight: Boolean = false
    @Volatile
    private var startupPerfTelemetryEnabled: Boolean = false
    @Volatile
    private var startupPerfWindowOpen: Boolean = false
    private var startupWindowOpenedAtMs: Long = 0L
    private var startupDeferralGeneration: Long = 0L
    private var deferredStartupWorkJob: Job? = null
    private var deferredBrowsableRequestJob: Job? = null
    private var startupPerfWindowJob: Job? = null
    private var idleScreensaverColdBootRefreshPending: Boolean = false
    private var shouldRunDeferredStartupWorkThisStart: Boolean = false
    private val channelBrowsableLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val channelId = pendingBrowsableChannelId
        pendingBrowsableChannelId = null
        channelBrowsableRequestInFlight = false
        if (channelId != null) {
            lifecycleScope.launch {
                logStartupPerf("browsable_request_result", "channelId=$channelId code=${result.resultCode}")
                if (result.resultCode == Activity.RESULT_OK) {
                    androidTvRecommendationsDataStore.markBrowsableChannelRequested(channelId)
                } else {
                    androidTvRecommendationsDataStore.markBrowsableChannelCooldown(
                        channelId = channelId,
                        cooldownDurationMs = BROWSABLE_REQUEST_COOLDOWN_MS
                    )
                    logStartupPerf("browsable_request_cooldown_applied", "channelId=$channelId")
                }
                androidTvChannelPublisher.requestSync("channel_browsable_result")
            }
        }
    }

    @OptIn(ExperimentalTvMaterial3Api::class, ExperimentalFoundationApi::class)
    override fun attachBaseContext(newBase: Context) {
        val tag = AppLocaleResolver.getStoredLocaleTag(newBase)
        if (!tag.isNullOrEmpty()) {
            val locale = Locale.forLanguageTag(tag)
            Locale.setDefault(locale)
            val config = Configuration(newBase.resources.configuration)
            config.setLocale(locale)
            super.attachBaseContext(newBase.createConfigurationContext(config))
        } else {
            super.attachBaseContext(newBase)
        }
    }

    private fun switchProfileAndApplyLocale(profileId: Int) {
        lifecycleScope.launch {
            val beforeLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
            try {
                profileManager.setActiveProfile(profileId)
            } catch (e: com.nexio.tv.core.integration.ProfileBoundaryException) {
                if (e.violation == com.nexio.tv.core.integration.ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) {
                    android.widget.Toast.makeText(
                        this@MainActivity,
                        getString(R.string.profile_switch_blocked_by_playback),
                        android.widget.Toast.LENGTH_SHORT
                    ).show()
                    return@launch
                } else {
                    throw e
                }
            }
            val afterLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
            if (beforeLocale != afterLocale) {
                recreate()
            }
        }
    }

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val launchDisposition = resolveStartupLaunchDisposition(
            processUiBootstrapped = processUiBootstrapped,
            hasSeenAuthQrOnFirstLaunch = cachedHasSeenAuthQrOnFirstLaunch,
            hasChosenLayout = cachedMainUiPrefs?.hasChosenLayout
        )
        shouldRunDeferredStartupWorkThisStart = !processDeferredStartupWorkCompleted
        idleScreensaverColdBootRefreshPending = !processDeferredStartupWorkCompleted
        handleRecommendationIntent(intent)
        logStartupPerf(
            "launch_disposition",
            buildString {
                append("mode=").append(launchDisposition.name.lowercase(Locale.US))
                append(" processUiBootstrapped=").append(processUiBootstrapped)
                append(" deferredStartupCompleted=").append(processDeferredStartupWorkCompleted)
                append(" criticalReady=")
                append(
                    isWarmResumeCriticalStateReady(
                        cachedHasSeenAuthQrOnFirstLaunch,
                        cachedMainUiPrefs?.hasChosenLayout
                    )
                )
                append(" savedStateNull=").append(savedInstanceState == null)
            }
        )
        lifecycleScope.launch(Dispatchers.IO) {
            deviceCapabilityRepository.ensureCached()
        }
        lifecycleScope.launch {
            debugSettingsDataStore.startupPerfTelemetryEnabled.collect { enabled ->
                startupPerfTelemetryEnabled = enabled
            }
        }
        lifecycleScope.launch {
            androidTvRecommendationsDataStore.preferences
                .map { it.pendingBrowsableChannelIds }
                .distinctUntilChanged()
                .collect { pendingIds ->
                    if (pendingIds.isNotEmpty()) {
                        scheduleDeferredBrowsableChannelRequest()
                    }
                }
        }
        setContent {
            var onboardingCompletedThisSession by remember { mutableStateOf(false) }
            val hasSeenAuthQrOnFirstLaunch by appOnboardingDataStore
                .hasSeenAuthQrOnFirstLaunch
                .map<Boolean, Boolean?> { it }
                .collectAsState(initial = cachedHasSeenAuthQrOnFirstLaunch)
            val hadAuthenticatedSession by authPresenceDataStore
                .hadAuthenticatedSession
                .map<Boolean, Boolean?> { it }
                .collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                    // Immediately priority-hydrate all enabled catalog feeds on first login.
                    // Boot-safe: hasSeenAuthQrOnFirstLaunch is now true, so this branch
                    // never executes again on subsequent boots.
                    catalogPriorityHydrationNotifier.notifyPriorityHydrationRequired()
                }
            }

            val mainUiPrefsFlow = remember(themeDataStore, layoutPreferenceDataStore) {
                combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    layoutPreferenceDataStore.hasChosenLayout,
                ) { theme, font, hasChosenLayout ->
                    MainUiPrefs(
                        theme = theme,
                        font = font,
                        hasChosenLayout = hasChosenLayout,
                    )
                }.combine(layoutPreferenceDataStore.trailerScreensaverEnabled) { prefs, trailerScreensaverEnabled ->
                    prefs.copy(trailerScreensaverEnabled = trailerScreensaverEnabled)
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(
                initial = cachedMainUiPrefs ?: MainUiPrefs(hasChosenLayout = null)
            )

            NexioTheme(appTheme = mainUiPrefs.theme, appFont = mainUiPrefs.font) {
                CompositionLocalProvider(
                    LocalBringIntoViewSpec provides NexioScrollDefaults.smoothScrollSpec
                ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = RectangleShape,
                    colors = SurfaceDefaults.colors(
                        containerColor = NexioColors.Background
                    )
                ) {
                    if (hasSeenAuthQrOnFirstLaunch == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NexioColors.Background)
                        )
                        return@Surface
                    }

                    if (
                        shouldShowAuthQrOnStartup(
                            hasSeenAuthQrOnFirstLaunch = hasSeenAuthQrOnFirstLaunch,
                            authState = authState,
                            onboardingCompletedThisSession = onboardingCompletedThisSession,
                            hadAuthenticatedSession = hadAuthenticatedSession
                        )
                    ) {
                        AuthQrSignInScreen(
                            onBackPress = {},
                            onContinue = {
                                lifecycleScope.launch {
                                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                                    onboardingCompletedThisSession = true
                                }
                                if (authManager.authState.value is AuthState.FullAccount) {
                                    startupSyncService.requestSyncNow()
                                }
                            }
                        )
                        return@Surface
                    }

                    val layoutChosen = mainUiPrefs.hasChosenLayout
                    if (layoutChosen == null) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(NexioColors.Background)
                        )
                        return@Surface
                    }
                    // Profile selection gating (D-02, UI-01, UI-02)
                    var hasPassedProfileSelectionGate by rememberSaveable {
                        mutableStateOf(processProfileSelectionGatePassed)
                    }
                    val profiles by profileManager.profiles.collectAsState()
                    val shouldShowProfileSelection = shouldShowStartupProfileSelection(
                        hasPassedProfileSelectionGate = hasPassedProfileSelectionGate,
                        profileCount = profiles.size
                    )
                    // Capture composition-local value at composable scope for use in LaunchedEffect
                    val contentFocusRequesterForGating = LocalContentFocusRequester.current
                    val profileSelectionScope = rememberCoroutineScope()

                    if (shouldShowProfileSelection) {
                        ProfileSelectionScreen(
                            onProfileSelected = { profileId ->
                                profileSelectionScope.launch {
                                    val beforeLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
                                    try {
                                        profileManager.setActiveProfile(profileId)
                                    } catch (e: com.nexio.tv.core.integration.ProfileBoundaryException) {
                                        if (e.violation == com.nexio.tv.core.integration.ProfileBoundaryViolation.PROFILE_SWITCH_BLOCKED_BY_ACTIVE_PLAYBACK) {
                                            android.widget.Toast.makeText(
                                                this@MainActivity,
                                                getString(R.string.profile_switch_blocked_by_playback),
                                                android.widget.Toast.LENGTH_SHORT
                                            ).show()
                                            return@launch
                                        } else {
                                            throw e
                                        }
                                    }
                                    val afterLocale = AppLocaleResolver.resolveEffectiveAppLanguageTag(this@MainActivity)
                                    processProfileSelectionGatePassed = true
                                    hasPassedProfileSelectionGate = true
                                    if (beforeLocale != afterLocale) {
                                        recreate()
                                    }
                                }
                            }
                        )
                        return@Surface
                    }

                    // Restore focus to content after profile selection exits (Pitfall 1)
                    LaunchedEffect(hasPassedProfileSelectionGate) {
                        if (hasPassedProfileSelectionGate) {
                            repeat(2) { withFrameNanos { } }
                            runCatching { contentFocusRequesterForGating.requestFocus() }
                        }
                    }

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()

                    val startDestination = if (layoutChosen) Screen.Home.route else Screen.LayoutSelection.route
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val pendingRecommendation by pendingRecommendationNavigation
                    val pendingFeed by pendingFeedNavigation
                    val lifecycleOwner = LocalLifecycleOwner.current
                    var appLifecycleState by remember(lifecycleOwner) {
                        mutableStateOf(lifecycleOwner.lifecycle.currentState)
                    }
                    DisposableEffect(lifecycleOwner) {
                        val observer = LifecycleEventObserver { _, _ ->
                            appLifecycleState = lifecycleOwner.lifecycle.currentState
                        }
                        lifecycleOwner.lifecycle.addObserver(observer)
                        appLifecycleState = lifecycleOwner.lifecycle.currentState
                        onDispose {
                            lifecycleOwner.lifecycle.removeObserver(observer)
                        }
                    }
                    val rootView = LocalView.current
                    val initialSplashDismissed = remember {
                        launchDisposition == StartupLaunchDisposition.WARM_PROCESS_SKIP_SPLASH
                    }
                    var startupSplashDismissed by rememberSaveable {
                        mutableStateOf(initialSplashDismissed)
                    }
                    var startupSplashReadyToPlay by remember { mutableStateOf(false) }
                    val showStartupSplash = !startupSplashDismissed
                    val idleScreensaverSlides by idleScreensaverRepository.slides.collectAsState()
                    val idleTrailerRepositoryCandidates by idleScreensaverRepository.trailerCandidates.collectAsState()
                    val idleScreensaverVisible by idleScreensaverController.isVisible.collectAsState()
                    val idleScreensaverSessionId by idleScreensaverController.sessionId.collectAsState()
                    val idleLastInteractionAtMs by idleScreensaverController.lastInteractionAtMs.collectAsState()
                    val idleScreensaverTimeoutMs by idleScreensaverController.currentTimeoutMs.collectAsState(
                        initial = IDLE_SCREENSAVER_DEFAULT_TIMEOUT_MS
                    )
                    val playbackIdleSnapshot by playbackIdleGateState.snapshot.collectAsState()
                    val idleTrailerCandidates = remember(
                        idleTrailerRepositoryCandidates,
                        idleScreensaverSlides
                    ) {
                        chooseIdleTrailerCandidates(
                            repositoryCandidates = idleTrailerRepositoryCandidates,
                            slides = idleScreensaverSlides
                        )
                    }
                    var inAppTrailerPlaybackActive by remember { mutableStateOf(false) }
                    var homeTrailerFullscreenActive by remember { mutableStateOf(false) }
                    var previousInAppTrailerPlaybackActive by remember { mutableStateOf(false) }
                    var idleTrailerSessionStart by remember { mutableStateOf<IdleTrailerScreensaverSessionStart?>(null) }

                    LaunchedEffect(pendingRecommendation) {
                        val navigation = pendingRecommendation ?: return@LaunchedEffect
                        navController.navigate(
                            Screen.Detail.createRoute(
                                itemId = navigation.itemId,
                                itemType = navigation.itemType,
                                addonBaseUrl = navigation.addonBaseUrl
                            )
                        )
                        pendingRecommendationNavigation.value = null
                    }

                    LaunchedEffect(hasSeenAuthQrOnFirstLaunch) {
                        cachedHasSeenAuthQrOnFirstLaunch = hasSeenAuthQrOnFirstLaunch
                    }

                    LaunchedEffect(mainUiPrefs) {
                        cachedMainUiPrefs = mainUiPrefs
                    }

                    LaunchedEffect(pendingFeed) {
                        val navigation = pendingFeed ?: return@LaunchedEffect
                        navController.navigate(Screen.AndroidTvFeed.createRoute(navigation.feedKey))
                        pendingFeedNavigation.value = null
                    }

                    val idleScreensaverEligible = remember(
                        currentRoute,
                        showStartupSplash,
                        playbackIdleSnapshot,
                        inAppTrailerPlaybackActive
                    ) {
                        isIdleScreensaverEligibleRoute(
                            currentRoute = currentRoute,
                            playbackIdleSnapshot = playbackIdleSnapshot,
                            inAppTrailerPlaybackActive = inAppTrailerPlaybackActive
                        ) && !showStartupSplash
                    }

                    LaunchedEffect(
                        currentRoute,
                        idleScreensaverEligible,
                        idleScreensaverVisible,
                        idleScreensaverSlides.size,
                        idleTrailerCandidates.size,
                        mainUiPrefs.trailerScreensaverEnabled,
                        inAppTrailerPlaybackActive,
                        idleLastInteractionAtMs
                    ) {
                        logIdleScreensaverDiagnostics(
                            buildIdleScreensaverDiagnosticsMessage(
                                event = "state_changed",
                                currentRoute = currentRoute,
                                idleScreensaverEligible = idleScreensaverEligible,
                                idleScreensaverVisible = idleScreensaverVisible,
                                slideCount = idleScreensaverSlides.size,
                                trailerCandidateCount = idleTrailerCandidates.size,
                                trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                idleLastInteractionAtMs = idleLastInteractionAtMs
                            )
                        )
                    }

                    LaunchedEffect(hasSeenAuthQrOnFirstLaunch, layoutChosen) {
                        if (
                            hasSeenAuthQrOnFirstLaunch != null &&
                            !processUiBootstrapped
                        ) {
                            processUiBootstrapped = true
                            logStartupPerf("process_ui_bootstrapped")
                        }
                    }

                    LaunchedEffect(currentRoute) {
                        if (
                            currentRoute != Screen.Home.route &&
                            currentRoute != Screen.Detail.route &&
                            inAppTrailerPlaybackActive
                        ) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "route_cleared_trailer_state",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs
                                )
                            )
                            inAppTrailerPlaybackActive = false
                        }
                        if (currentRoute != Screen.Home.route && homeTrailerFullscreenActive) {
                            homeTrailerFullscreenActive = false
                        }
                    }

                    LaunchedEffect(inAppTrailerPlaybackActive) {
                        if (
                            shouldRegisterIdleInteractionForTrailerPlaybackTransition(
                                previousActive = previousInAppTrailerPlaybackActive,
                                currentActive = inAppTrailerPlaybackActive
                            )
                        ) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "register_interaction_for_trailer_transition",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs
                                )
                            )
                            idleScreensaverController.registerInteraction()
                        }
                        previousInAppTrailerPlaybackActive = inAppTrailerPlaybackActive
                    }

                    LaunchedEffect(idleScreensaverEligible, idleScreensaverVisible) {
                        if (!idleScreensaverEligible && idleScreensaverVisible) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "dismiss_for_ineligible_state",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs
                                )
                            )
                            idleScreensaverController.dismiss()
                        }
                    }

                    LaunchedEffect(idleScreensaverVisible) {
                        if (!idleScreensaverVisible) {
                            idleTrailerSessionStart = null
                        }
                    }

                    DisposableEffect(idleScreensaverVisible) {
                        if (idleScreensaverVisible) {
                            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                        }
                        onDispose {
                            if (idleScreensaverVisible) {
                                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                            }
                        }
                    }

                    LaunchedEffect(
                        idleScreensaverEligible,
                        appLifecycleState,
                        idleScreensaverVisible,
                        idleScreensaverSlides,
                        idleTrailerCandidates,
                        mainUiPrefs.trailerScreensaverEnabled,
                        idleLastInteractionAtMs,
                        idleScreensaverTimeoutMs
                    ) {
                        if (!shouldScheduleIdleScreensaverStart(
                                lifecycleState = appLifecycleState,
                                idleScreensaverEligible = idleScreensaverEligible,
                                idleScreensaverVisible = idleScreensaverVisible,
                                slideCount = idleScreensaverSlides.size,
                                trailerCandidateCount = idleTrailerCandidates.size
                            )
                        ) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "start_skipped",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs,
                                    trailerSessionReady = idleTrailerSessionStart != null
                                )
                            )
                            return@LaunchedEffect
                        }
                        val elapsed = (SystemClock.elapsedRealtime() - idleLastInteractionAtMs).coerceAtLeast(0L)
                        val remainingDelayMs = (idleScreensaverTimeoutMs - elapsed).coerceAtLeast(0L)
                        logIdleScreensaverDiagnostics(
                            buildIdleScreensaverDiagnosticsMessage(
                                event = "start_scheduled",
                                currentRoute = currentRoute,
                                idleScreensaverEligible = idleScreensaverEligible,
                                idleScreensaverVisible = idleScreensaverVisible,
                                slideCount = idleScreensaverSlides.size,
                                trailerCandidateCount = idleTrailerCandidates.size,
                                trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                idleLastInteractionAtMs = idleLastInteractionAtMs,
                                elapsedMs = elapsed,
                                remainingDelayMs = remainingDelayMs,
                                trailerSessionReady = idleTrailerSessionStart != null
                            )
                        )
                        delay(remainingDelayMs)
                        if (!shouldScheduleIdleScreensaverStart(
                                lifecycleState = appLifecycleState,
                                idleScreensaverEligible = idleScreensaverEligible,
                                idleScreensaverVisible = idleScreensaverVisible,
                                slideCount = idleScreensaverSlides.size,
                                trailerCandidateCount = idleTrailerCandidates.size
                            )
                        ) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "start_aborted_after_delay",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs,
                                    trailerSessionReady = idleTrailerSessionStart != null
                                )
                            )
                            return@LaunchedEffect
                        }
                        idleTrailerSessionStart = if (mainUiPrefs.trailerScreensaverEnabled) {
                            com.nexio.tv.ui.screensaver.prepareIdleTrailerScreensaverSessionFromCandidates(
                                candidates = idleTrailerCandidates
                            ) { candidate, playbackRef ->
                                trailerService.resolveIdleTrailerScreensaverPlaybackSource(
                                    trailerResolver = trailerResolver,
                                    candidate = candidate,
                                    playbackRef = playbackRef
                                )
                            }
                        } else {
                            null
                        }
                        logIdleScreensaverDiagnostics(
                            buildIdleScreensaverDiagnosticsMessage(
                                event = "start_prepared",
                                currentRoute = currentRoute,
                                idleScreensaverEligible = idleScreensaverEligible,
                                idleScreensaverVisible = idleScreensaverVisible,
                                slideCount = idleScreensaverSlides.size,
                                trailerCandidateCount = idleTrailerCandidates.size,
                                trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                idleLastInteractionAtMs = idleLastInteractionAtMs,
                                trailerSessionReady = idleTrailerSessionStart != null
                            )
                        )
                        if (
                            idleScreensaverSlides.isNotEmpty() ||
                            idleTrailerSessionStart != null
                        ) {
                            logIdleScreensaverDiagnostics(
                                buildIdleScreensaverDiagnosticsMessage(
                                    event = "show_requested",
                                    currentRoute = currentRoute,
                                    idleScreensaverEligible = idleScreensaverEligible,
                                    idleScreensaverVisible = idleScreensaverVisible,
                                    slideCount = idleScreensaverSlides.size,
                                    trailerCandidateCount = idleTrailerCandidates.size,
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    inAppTrailerPlaybackActive = inAppTrailerPlaybackActive,
                                    idleLastInteractionAtMs = idleLastInteractionAtMs,
                                    trailerSessionReady = idleTrailerSessionStart != null
                                )
                            )
                            idleScreensaverController.show()
                        }
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
                    }

                    LaunchedEffect(showStartupSplash, lifecycleOwner, rootView) {
                        if (!showStartupSplash) return@LaunchedEffect
                        startupSplashReadyToPlay = false
                        while (!lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                            withFrameNanos { }
                        }
                        while (!rootView.isAttachedToWindow) {
                            withFrameNanos { }
                        }
                        repeat(2) { withFrameNanos { } }
                        startupSplashReadyToPlay = true
                    }

                    val rootRoutes = remember {
                        setOf(
                            Screen.Home.route,
                            Screen.Search.route,
                            Screen.Library.route,
                            Screen.Settings.route,
                            Screen.AddonManager.route
                        )
                    }

                    val strNavHome = stringResource(R.string.nav_home)
                    val strNavSearch = stringResource(R.string.nav_search)
                    val strNavLibrary = stringResource(R.string.nav_library)
                    val strNavAddons = stringResource(R.string.nav_addons)
                    val strNavSettings = stringResource(R.string.nav_settings)
                    val drawerItems = remember(
                        strNavHome,
                        strNavSearch,
                        strNavLibrary,
                        strNavAddons,
                        strNavSettings
                    ) {
                        listOf(
                            DrawerItem(
                                route = Screen.Home.route,
                                label = strNavHome,
                                icon = Icons.Default.Home
                            ),
                            DrawerItem(
                                route = Screen.Search.route,
                                label = strNavSearch,
                                iconRes = R.raw.sidebar_search
                            ),
                            DrawerItem(
                                route = Screen.Library.route,
                                label = strNavLibrary,
                                iconRes = R.raw.sidebar_library
                            ),
                            DrawerItem(
                                route = Screen.AddonManager.route,
                                label = strNavAddons,
                                iconRes = R.raw.sidebar_plugin
                            ),
                            DrawerItem(
                                route = Screen.Settings.route,
                                label = strNavSettings,
                                iconRes = R.raw.sidebar_settings
                            )
                        )
                    }
                    val selectedDrawerRoute = drawerItems.firstOrNull { item ->
                        currentRoute == item.route || currentRoute?.startsWith("${item.route}/") == true
                    }?.route

                    Box(modifier = Modifier.fillMaxSize()) {
                        LegacySidebarScaffold(
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            sidebarCollapsed = true,
                            hideBuiltInHeaders = false,
                            idleScreensaverVisible = idleScreensaverVisible,
                            startupSplashVisible = showStartupSplash,
                            idleScreensaverController = idleScreensaverController,
                            onHomeTrailerPlaybackActiveChanged = { active ->
                                inAppTrailerPlaybackActive = active
                            },
                            onHomeTrailerFullscreenActiveChanged = { active ->
                                homeTrailerFullscreenActive = active
                            },
                            onDetailTrailerPlaybackActiveChanged = { active ->
                                inAppTrailerPlaybackActive = active
                            },
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            },
                            profiles = profiles,
                            activeProfileId = profileManager.activeProfileId.collectAsState().value,
                            onSwitchProfile = { profileId ->
                                processProfileSelectionGatePassed = true
                                hasPassedProfileSelectionGate = true
                                switchProfileAndApplyLocale(profileId)
                            }
                        )

                        UpdatePromptDialog(
                            state = updateState,
                            onDismiss = { updateViewModel.dismissDialog() },
                            onDownload = { updateViewModel.downloadUpdate() },
                            onInstall = { updateViewModel.installUpdateOrRequestPermission() },
                            onIgnore = { updateViewModel.ignoreThisVersion() },
                            onOpenUnknownSources = { updateViewModel.openUnknownSourcesSettings() }
                        )

                        if (idleScreensaverVisible && (idleScreensaverSlides.isNotEmpty() || idleTrailerSessionStart != null)) {
                            when (
                                chooseIdleScreensaverPresentationMode(
                                    trailerScreensaverEnabled = mainUiPrefs.trailerScreensaverEnabled,
                                    trailerSessionStart = idleTrailerSessionStart
                                )
                            ) {
                                IdleScreensaverPresentationMode.TRAILER -> {
                                    val sessionStart = idleTrailerSessionStart
                                    if (sessionStart != null) {
                                        IdleTrailerScreensaverOverlay(
                                            sessionStart = sessionStart,
                                            sessionId = idleScreensaverSessionId,
                                            onDismiss = {
                                                idleTrailerSessionStart = null
                                                idleScreensaverController.dismiss()
                                            },
                                            onOpenSlide = { candidate ->
                                                idleTrailerSessionStart = null
                                                idleScreensaverController.dismiss()
                                                navController.navigate(
                                                    Screen.Detail.createRoute(
                                                        itemId = candidate.itemId,
                                                        itemType = candidate.itemType,
                                                        addonBaseUrl = candidate.addonBaseUrl
                                                    )
                                                )
                                            },
                                            resolvePlaybackSource = { candidate, playbackRef ->
                                                trailerService.resolveIdleTrailerScreensaverPlaybackSource(
                                                    trailerResolver = trailerResolver,
                                                    candidate = candidate,
                                                    playbackRef = playbackRef
                                                )
                                            }
                                        )
                                    }
                                }

                                IdleScreensaverPresentationMode.IMAGE -> {
                                    if (idleScreensaverSlides.isNotEmpty()) {
                                        IdleScreensaverOverlay(
                                            slides = idleScreensaverSlides,
                                            sessionId = idleScreensaverSessionId,
                                            onDismiss = { idleScreensaverController.dismiss() },
                                            onOpenSlide = { slide ->
                                                idleScreensaverController.dismiss()
                                                navController.navigate(
                                                    Screen.Detail.createRoute(
                                                        itemId = slide.itemId,
                                                        itemType = slide.itemType,
                                                        addonBaseUrl = slide.addonBaseUrl
                                                    )
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }

                        if (showStartupSplash) {
                            if (startupSplashReadyToPlay) {
                                StartupSplashOverlay(
                                    onFinished = {
                                        processUiBootstrapped = true
                                        startupSplashDismissed = true
                                    }
                                )
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .background(Color.Black)
                                )
                            }
                        }
                    }
                }
            }
            }
        }

        jankStats = JankStats.createAndTrack(window) { frameData ->
            val frameMs = frameData.frameDurationUiNanos / 1_000_000
            if (frameData.isJank) {
                Log.w(
                    "JankStats",
                    "JANK: ${frameMs}ms | states: ${frameData.states}"
                )
            }
            if (startupPerfTelemetryEnabled && startupPerfWindowOpen && (frameData.isJank || frameMs >= 24)) {
                Log.i("StartupPerf", "doFrame=${frameMs}ms jank=${frameData.isJank} states=${frameData.states}")
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = true
        FrameRateUtils.enforceUiPreferredRefreshRate(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleRecommendationIntent(intent)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            idleScreensaverController.registerInteraction()
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOICE_ASSIST,
                KeyEvent.KEYCODE_ASSIST,
                KeyEvent.KEYCODE_SEARCH -> {
                    if (voiceKeyHandler?.invoke() == true) return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    override fun onStart() {
        super.onStart()
        logStartupPerf("on_start")
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching { idleScreensaverRepository.warmFromCache() }
                .onFailure { error ->
                    Log.w("MainActivity", "Idle screensaver cache warmup failed", error)
                }
        }
        startupPerfWindowOpen = true
        startupWindowOpenedAtMs = SystemClock.elapsedRealtime()
        startupPerfWindowJob?.cancel()
        startupPerfWindowJob = lifecycleScope.launch {
            delay(STARTUP_PERF_WINDOW_MS)
            startupPerfWindowOpen = false
            logStartupPerf("startup_window_closed")
        }
        if (shouldRunDeferredStartupWorkThisStart) {
            scheduleDeferredStartupWork()
        } else {
            logStartupPerf("deferred_startup_skipped", "reason=warm_process_resume")
        }
    }

    override fun onStop() {
        super.onStop()
        startupPerfWindowOpen = false
        deferredStartupWorkJob?.cancel()
        deferredBrowsableRequestJob?.cancel()
        startupPerfWindowJob?.cancel()
    }

    private fun scheduleDeferredStartupWork() {
        val generation = ++startupDeferralGeneration
        val deferStartedAt = SystemClock.elapsedRealtime()
        deferredStartupWorkJob?.cancel()
        deferredStartupWorkJob = lifecycleScope.launch {
            val reachedStableFrames = withTimeoutOrNull(3_000) {
                awaitStableFrames(frameCount = 3)
                true
            } ?: false
            val elapsed = SystemClock.elapsedRealtime() - deferStartedAt
            if (elapsed < STARTUP_DEFERRED_WORK_MIN_DELAY_MS) {
                delay(STARTUP_DEFERRED_WORK_MIN_DELAY_MS - elapsed)
            }
            if (generation != startupDeferralGeneration) return@launch
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch

            logStartupPerf("deferred_startup_begin", "stable_frames=$reachedStableFrames")
            logStartupPerf("startup_sync_request_start")
            startupSyncService.requestSyncNow()
            logStartupPerf("startup_sync_request_end")
            logStartupPerf("channel_sync_request_start", "reason=app_start_deferred")
            androidTvChannelPublisher.requestSync("app_start_deferred")
            logStartupPerf("channel_sync_request_end", "reason=app_start_deferred")
            maybeLaunchPendingBrowsableChannelRequest()
            launch {
                logStartupPerf("tracking_refresh_start")
                runCatching { trackingProgressService.refreshNow() }
                    .onFailure { error ->
                        logStartupPerf("tracking_refresh_failed", "message=${error.message ?: "unknown"}")
                        Log.w("MainActivity", "Deferred tracking startup refresh failed", error)
                    }
                    .onSuccess {
                        logStartupPerf("tracking_refresh_end")
                    }
            }
            launch(Dispatchers.IO) {
                if (!idleScreensaverColdBootRefreshPending) return@launch
                idleScreensaverColdBootRefreshPending = false
                runCatching { idleScreensaverRepository.refreshOnColdBoot() }
                    .onFailure { error ->
                        Log.w("MainActivity", "Deferred idle screensaver refresh failed", error)
                    }
            }
            launch(Dispatchers.IO) {
                try {
                    deviceCapabilityReportUploader.submitOnceIfEnabled()
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Throwable) {
                    Log.w("MainActivity", "Deferred device capability report failed", error)
                }
            }
            processDeferredStartupWorkCompleted = true
            shouldRunDeferredStartupWorkThisStart = false
            logStartupPerf("deferred_startup_end")
        }
    }

    private fun scheduleDeferredBrowsableChannelRequest() {
        val deferStartedAt = SystemClock.elapsedRealtime()
        deferredBrowsableRequestJob?.cancel()
        deferredBrowsableRequestJob = lifecycleScope.launch {
            withTimeoutOrNull(3_000) { awaitStableFrames(frameCount = 3) }
            val elapsed = SystemClock.elapsedRealtime() - deferStartedAt
            if (elapsed < STARTUP_DEFERRED_WORK_MIN_DELAY_MS) {
                delay(STARTUP_DEFERRED_WORK_MIN_DELAY_MS - elapsed)
            }
            delayForStartupWindowClose()
            if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
            logStartupPerf("deferred_browsable_channel_request")
            maybeLaunchPendingBrowsableChannelRequest()
        }
    }

    private suspend fun delayForStartupWindowClose() {
        val openedAt = startupWindowOpenedAtMs
        if (openedAt <= 0L) {
            delay(STARTUP_PERF_WINDOW_MS)
            return
        }
        val elapsed = SystemClock.elapsedRealtime() - openedAt
        if (elapsed < STARTUP_PERF_WINDOW_MS) {
            delay(STARTUP_PERF_WINDOW_MS - elapsed)
        }
    }

    private suspend fun awaitStableFrames(frameCount: Int) {
        repeat(frameCount.coerceAtLeast(1)) {
            awaitNextFrame()
        }
    }

    private suspend fun awaitNextFrame() {
        suspendCancellableCoroutine<Unit> { continuation ->
            val choreographer = Choreographer.getInstance()
            val callback = Choreographer.FrameCallback {
                if (continuation.isActive) continuation.resume(Unit)
            }
            choreographer.postFrameCallback(callback)
            continuation.invokeOnCancellation {
                choreographer.removeFrameCallback(callback)
            }
        }
    }

    private fun logStartupPerf(event: String, details: String? = null) {
        if (!startupPerfTelemetryEnabled) return
        val nowMs = SystemClock.elapsedRealtime()
        val suffix = details?.let { " $it" }.orEmpty()
        Log.i("StartupPerf", "t=${nowMs}ms event=$event$suffix")
    }

    private fun handleRecommendationIntent(intent: Intent?) {
        val actualIntent = intent ?: return
        if (actualIntent.action == Intent.ACTION_VIEW) {
            AndroidTvNativeSearchIntent.parseDetailUri(actualIntent.data)?.let { target ->
                pendingRecommendationNavigation.value = RecommendationNavigation(
                    itemId = target.itemId,
                    itemType = target.itemType,
                    addonBaseUrl = target.addonBaseUrl
                )
                actualIntent.data = null
                return
            }
        }

        val itemId = actualIntent.getStringExtra(EXTRA_RECOMMENDATION_CONTENT_ID)?.trim().orEmpty()
        val itemType = actualIntent.getStringExtra(EXTRA_RECOMMENDATION_CONTENT_TYPE)?.trim().orEmpty()
        if (itemId.isNotEmpty() && itemType.isNotEmpty()) {
            val addonBaseUrl = actualIntent.getStringExtra(EXTRA_RECOMMENDATION_ADDON_BASE_URL)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
            pendingRecommendationNavigation.value = RecommendationNavigation(
                itemId = itemId,
                itemType = itemType,
                addonBaseUrl = addonBaseUrl
            )
            actualIntent.removeExtra(EXTRA_RECOMMENDATION_CONTENT_ID)
            actualIntent.removeExtra(EXTRA_RECOMMENDATION_CONTENT_TYPE)
            actualIntent.removeExtra(EXTRA_RECOMMENDATION_ADDON_BASE_URL)
            actualIntent.removeExtra(EXTRA_RECOMMENDATION_FEED_KEY)
            return
        }

        val feedKey = actualIntent.getStringExtra(EXTRA_RECOMMENDATION_FEED_KEY)
            ?.trim()
            .orEmpty()
        if (feedKey.isNotEmpty()) {
            pendingFeedNavigation.value = RecommendationFeedNavigation(feedKey = feedKey)
            actualIntent.removeExtra(EXTRA_RECOMMENDATION_FEED_KEY)
        }
    }

    private fun maybeLaunchPendingBrowsableChannelRequest() {
        if (channelBrowsableRequestInFlight) return
        lifecycleScope.launch {
            val prefs = androidTvRecommendationsDataStore.preferences.first()
            val pendingChannelId = prefs.pendingBrowsableChannelIds.firstOrNull() ?: return@launch
            val nowMs = System.currentTimeMillis()
            val cooldownUntilMs = prefs.browsableRequestCooldownUntilMsByChannelId[pendingChannelId] ?: 0L
            if (cooldownUntilMs > nowMs) {
                androidTvRecommendationsDataStore.markBrowsableChannelCooldown(
                    channelId = pendingChannelId,
                    cooldownDurationMs = cooldownUntilMs - nowMs
                )
                logStartupPerf(
                    "browsable_request_skipped_cooldown",
                    "channelId=$pendingChannelId cooldownRemainingMs=${cooldownUntilMs - nowMs}"
                )
                return@launch
            }

            val intent = Intent(TvContractCompat.ACTION_REQUEST_CHANNEL_BROWSABLE)
                .putExtra(TvContractCompat.EXTRA_CHANNEL_ID, pendingChannelId)

            channelBrowsableRequestInFlight = true
            pendingBrowsableChannelId = pendingChannelId
            logStartupPerf("browsable_request_launch", "channelId=$pendingChannelId")
            runCatching {
                channelBrowsableLauncher.launch(intent)
            }.onFailure { error ->
                lifecycleScope.launch {
                    androidTvRecommendationsDataStore.markBrowsableChannelCooldown(
                        channelId = pendingChannelId,
                        cooldownDurationMs = BROWSABLE_REQUEST_COOLDOWN_MS
                    )
                }
                logStartupPerf("browsable_request_launch_failed", "channelId=$pendingChannelId")
                Log.w("MainActivity", "Failed to launch Android TV browsable request channelId=$pendingChannelId", error)
                runCatching {
                    TvContractCompat.requestChannelBrowsable(this@MainActivity, pendingChannelId)
                }.onSuccess {
                    logStartupPerf("browsable_request_fallback_success", "channelId=$pendingChannelId")
                    androidTvChannelPublisher.requestSync("channel_browsable_fallback")
                }.onFailure { fallbackError ->
                    logStartupPerf("browsable_request_fallback_failed", "channelId=$pendingChannelId")
                    Log.w("MainActivity", "Failed to request browsable Android TV channelId=$pendingChannelId", fallbackError)
                }
                channelBrowsableRequestInFlight = false
                pendingBrowsableChannelId = null
            }
        }
    }
}

private data class RecommendationNavigation(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String?
)

private data class RecommendationFeedNavigation(
    val feedKey: String
)

internal fun shouldLogIdleScreensaverDiagnostics(isDebugBuild: Boolean): Boolean = isDebugBuild

internal fun buildIdleScreensaverDiagnosticsMessage(
    event: String,
    currentRoute: String?,
    idleScreensaverEligible: Boolean,
    idleScreensaverVisible: Boolean,
    slideCount: Int,
    trailerCandidateCount: Int,
    trailerScreensaverEnabled: Boolean,
    inAppTrailerPlaybackActive: Boolean,
    idleLastInteractionAtMs: Long,
    elapsedMs: Long? = null,
    remainingDelayMs: Long? = null,
    trailerSessionReady: Boolean? = null
): String {
    return buildString {
        append("event=").append(event)
        append(" route=").append(currentRoute ?: "null")
        append(" eligible=").append(idleScreensaverEligible)
        append(" visible=").append(idleScreensaverVisible)
        append(" slides=").append(slideCount)
        append(" trailerCandidates=").append(trailerCandidateCount)
        append(" trailerEnabled=").append(trailerScreensaverEnabled)
        append(" inAppTrailerActive=").append(inAppTrailerPlaybackActive)
        append(" lastInteractionMs=").append(idleLastInteractionAtMs)
        elapsedMs?.let { append(" elapsedMs=").append(it) }
        remainingDelayMs?.let { append(" remainingMs=").append(it) }
        trailerSessionReady?.let { append(" trailerSessionReady=").append(it) }
    }
}

internal fun logIdleScreensaverDiagnostics(
    message: String,
    isDebugBuild: Boolean = BuildConfig.DEBUG,
    logger: (String, String) -> Int = Log::d
) {
    if (!shouldLogIdleScreensaverDiagnostics(isDebugBuild)) return
    runCatching { logger(IDLE_SCREENSAVER_DEBUG_LOG_TAG, message) }
}

internal fun isIdleScreensaverEligibleRoute(
    currentRoute: String?,
    playbackIdleSnapshot: PlaybackIdleGateSnapshot,
    inAppTrailerPlaybackActive: Boolean
): Boolean {
    val route = currentRoute ?: return false
    if (
        route == Screen.AuthSignIn.route ||
        route == Screen.AuthQrSignIn.route ||
        route == Screen.LayoutSelection.route ||
        route == Screen.SyncCodeGenerate.route ||
        route == Screen.SyncCodeClaim.route
    ) {
        return false
    }
    if (route == Screen.Player.route) {
        return playbackIdleSnapshot.hasActiveSession && playbackIdleSnapshot.isPausedByUser
    }
    if (
        (route == Screen.Home.route || route == Screen.Detail.route) &&
        inAppTrailerPlaybackActive
    ) {
        return false
    }
    return true
}

internal fun shouldScheduleIdleScreensaverStart(
    lifecycleState: Lifecycle.State,
    idleScreensaverEligible: Boolean,
    idleScreensaverVisible: Boolean,
    slideCount: Int,
    trailerCandidateCount: Int
): Boolean {
    return lifecycleState == Lifecycle.State.RESUMED &&
        idleScreensaverEligible &&
        !idleScreensaverVisible &&
        (slideCount > 0 || trailerCandidateCount > 0)
}

internal fun shouldRegisterIdleInteractionForTrailerPlaybackTransition(
    previousActive: Boolean,
    currentActive: Boolean
): Boolean = previousActive != currentActive

internal fun chooseIdleScreensaverPresentationMode(
    trailerScreensaverEnabled: Boolean,
    trailerSessionStart: IdleTrailerScreensaverSessionStart?
): IdleScreensaverPresentationMode {
    return if (trailerScreensaverEnabled && trailerSessionStart != null) {
        IdleScreensaverPresentationMode.TRAILER
    } else {
        IdleScreensaverPresentationMode.IMAGE
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@Composable
private fun StartupSplashOverlay(
    onFinished: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val focusRequester = remember { FocusRequester() }
    var playbackEnded by remember { mutableStateOf(false) }
    var firstFrameRendered by remember { mutableStateOf(false) }
    var finishDispatched by remember { mutableStateOf(false) }
    val splashUri = remember(context) {
        Uri.parse("android.resource://${context.packageName}/${R.raw.splash_screen}")
    }
    val player = remember(context, splashUri) {
        ExoPlayer.Builder(context)
            .setVideoChangeFrameRateStrategy(C.VIDEO_CHANGE_FRAME_RATE_STRATEGY_OFF)
            .build()
            .apply {
                repeatMode = Player.REPEAT_MODE_OFF
                volume = 1f
                setAudioAttributes(
                    AudioAttributes.Builder()
                        .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                        .setUsage(C.USAGE_MEDIA)
                        .build(),
                    true
                )
                setMediaItem(MediaItem.fromUri(splashUri))
                prepare()
                playWhenReady = true
            }
    }

    DisposableEffect(lifecycleOwner, player) {
        val splashListener = object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    playbackEnded = true
                }
            }

            override fun onRenderedFirstFrame() {
                firstFrameRendered = true
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                if (finishDispatched) return
                finishDispatched = true
                onFinished()
            }
        }
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> {
                    if (!playbackEnded && !finishDispatched) {
                        player.playWhenReady = true
                    }
                }
                Lifecycle.Event.ON_PAUSE,
                Lifecycle.Event.ON_STOP -> player.pause()
                Lifecycle.Event.ON_DESTROY -> {
                    runCatching { player.stop() }
                    runCatching { player.clearMediaItems() }
                    runCatching { player.release() }
                }
                else -> Unit
            }
        }
        player.addListener(splashListener)
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            runCatching { player.removeListener(splashListener) }
            runCatching { lifecycleOwner.lifecycle.removeObserver(observer) }
            runCatching { player.stop() }
            runCatching { player.clearMediaItems() }
            runCatching { player.release() }
        }
    }

    LaunchedEffect(Unit) {
        runCatching { focusRequester.requestFocus() }
    }

    LaunchedEffect(firstFrameRendered) {
        if (firstFrameRendered || finishDispatched) return@LaunchedEffect
        delay(STARTUP_SPLASH_FIRST_FRAME_TIMEOUT_MS)
        if (!firstFrameRendered && !finishDispatched) {
            finishDispatched = true
            onFinished()
        }
    }

    LaunchedEffect(playbackEnded) {
        if (!playbackEnded || finishDispatched) return@LaunchedEffect
        finishDispatched = true
        delay(STARTUP_SPLASH_LAST_FRAME_HOLD_MS)
        onFinished()
    }

    LaunchedEffect(Unit) {
        delay(STARTUP_SPLASH_HARD_TIMEOUT_MS)
        if (!finishDispatched) {
            finishDispatched = true
            onFinished()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
            .onPreviewKeyEvent { true }
    ) {
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).apply {
                    this.player = player
                    useController = false
                    keepScreenOn = true
                    setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
                    setShutterBackgroundColor(android.graphics.Color.BLACK)
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (!firstFrameRendered) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LegacySidebarScaffold(
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    sidebarCollapsed: Boolean,
    hideBuiltInHeaders: Boolean,
    idleScreensaverVisible: Boolean,
    startupSplashVisible: Boolean,
    idleScreensaverController: IdleScreensaverController,
    onHomeTrailerPlaybackActiveChanged: (Boolean) -> Unit,
    onHomeTrailerFullscreenActiveChanged: (Boolean) -> Unit,
    onDetailTrailerPlaybackActiveChanged: (Boolean) -> Unit,
    onExitApp: () -> Unit,
    profiles: List<UserProfile> = emptyList(),
    activeProfileId: Int = 1,
    onSwitchProfile: (Int) -> Unit = {}
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }
    val profileSwitcherFocusRequester = remember { FocusRequester() }
    var homeTrailerFullscreenActive by remember { mutableStateOf(false) }
    val showSidebar = currentRoute in rootRoutes && !homeTrailerFullscreenActive

    LaunchedEffect(currentRoute) {
        drawerState.setValue(DrawerValue.Closed)
    }

    val closedDrawerWidth = if (sidebarCollapsed) 0.dp else 72.dp
    val openDrawerWidth = 216.dp

    val focusManager = LocalFocusManager.current
    val contentFocusRequester = remember { FocusRequester() }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Closed) {
        pendingSidebarFocusRequest = true
        drawerState.setValue(DrawerValue.Open)
    }

    BackHandler(enabled = currentRoute in rootRoutes && drawerState.currentValue == DrawerValue.Open) {
        onExitApp()
    }

    LaunchedEffect(drawerState.currentValue, pendingContentFocusTransfer) {
        if (!pendingContentFocusTransfer || drawerState.currentValue != DrawerValue.Closed) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { contentFocusRequester.requestFocus() }
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val requester = if (profiles.size > 1) {
            profileSwitcherFocusRequester
        } else {
            val targetRoute = selectedDrawerRoute ?: run {
                pendingSidebarFocusRequest = false
                return@LaunchedEffect
            }
            drawerItemFocusRequesters[targetRoute]
        } ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = { drawerValue ->
            if (showSidebar) {
                val drawerWidth = if (drawerValue == DrawerValue.Open) openDrawerWidth else closedDrawerWidth
                Column(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(drawerWidth)
                        .background(NexioColors.Background)
                        .padding(12.dp)
                        .selectableGroup()
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.key == Key.DirectionRight && keyEvent.type == KeyEventType.KeyDown) {
                                drawerState.setValue(DrawerValue.Closed)
                                pendingContentFocusTransfer = false
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    val isExpanded = drawerValue == DrawerValue.Open
                    if (isExpanded) {
                        Spacer(modifier = Modifier.height(30.dp))
                        Image(
                            painter = painterResource(id = R.drawable.app_logo_wordmark),
                            contentDescription = "NEXIO",
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(59.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        if (profiles.size > 1) {
                            LegacyProfileSwitcherSection(
                                profiles = profiles,
                                activeProfileId = activeProfileId,
                                focusRequester = profileSwitcherFocusRequester,
                                onSwitchProfile = onSwitchProfile
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    val itemWidth = if (isExpanded) 176.dp else 48.dp
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        drawerItems.forEach { item ->
                            LegacySidebarButton(
                                label = item.label,
                                iconRes = item.iconRes,
                                icon = item.icon,
                                selected = selectedDrawerRoute == item.route,
                                expanded = isExpanded,
                                onClick = {
                                    navigateToDrawerRoute(
                                        navController = navController,
                                        currentRoute = currentRoute,
                                        targetRoute = item.route
                                    )
                                    drawerState.setValue(DrawerValue.Closed)
                                    pendingContentFocusTransfer = true
                                },
                                modifier = Modifier.focusRequester(
                                    drawerItemFocusRequesters.getValue(item.route)
                                ).width(itemWidth)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                }
            }
        }
    ) {
        val contentStartPadding by animateDpAsState(
            targetValue = if (showSidebar) closedDrawerWidth else 0.dp,
            animationSpec = tween(durationMillis = 220, easing = FastOutSlowInEasing),
            label = "legacySidebarContentPadding"
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(start = contentStartPadding)
                .onKeyEvent { keyEvent ->
                    if (
                        showSidebar &&
                        drawerState.currentValue == DrawerValue.Closed &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        keyEvent.key == Key.DirectionLeft
                    ) {
                        pendingSidebarFocusRequest = true
                        drawerState.setValue(DrawerValue.Open)
                        true
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open),
                LocalContentFocusRequester provides contentFocusRequester
            ) {
                NexioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders,
                    idleScreensaverVisible = idleScreensaverVisible,
                    startupSplashVisible = startupSplashVisible,
                    onModernHomeTrailerPlaybackStarted = {
                        idleScreensaverController.registerInteraction()
                    },
                    onModernHomeTrailerPlaybackActiveChanged = { active ->
                        onHomeTrailerPlaybackActiveChanged(active)
                    },
                    onModernHomeTrailerFullscreenActiveChanged = { active ->
                        homeTrailerFullscreenActive = active
                        onHomeTrailerFullscreenActiveChanged(active)
                    },
                    onDetailTrailerPlaybackActiveChanged = { active ->
                        onDetailTrailerPlaybackActiveChanged(active)
                    }
                )
            }
        }
    }
}

@Composable
private fun LegacySidebarButton(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    selected: Boolean,
    expanded: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val itemShape = RoundedCornerShape(32.dp)
    val backgroundColor by animateColorAsState(
        targetValue = when {
            isFocused -> NexioColors.FocusBackground
            selected -> NexioColors.BackgroundCard
            else -> Color.Transparent
        },
        label = "legacySidebarItemBackground"
    )
    val contentColor by animateColorAsState(
        targetValue = when {
            isFocused -> NexioColors.TextPrimary
            selected -> NexioColors.TextPrimary
            else -> NexioColors.TextSecondary
        },
        label = "legacySidebarItemContent"
    )

    Box(
        modifier = modifier
            .height(52.dp)
            .focusProperties { canFocus = expanded }
            .clip(itemShape)
            .background(color = backgroundColor, shape = itemShape)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick),
    ) {
        DrawerItemIcon(
            iconRes = iconRes,
            icon = icon,
            tint = contentColor,
            modifier = if (expanded) {
                Modifier
                    .size(22.dp)
                    .align(Alignment.CenterStart)
                    .offset(x = 18.dp)
            } else {
                Modifier
                    .size(22.dp)
                    .align(Alignment.Center)
            }
        )
        if (expanded) {
            Text(
                text = label,
                color = contentColor,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = TextAlign.Start,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxWidth()
                    .padding(start = 54.dp, end = 14.dp)
            )
        }
    }
}

@Composable
private fun LegacyProfileSwitcherSection(
    profiles: List<UserProfile>,
    activeProfileId: Int,
    focusRequester: FocusRequester,
    onSwitchProfile: (Int) -> Unit
) {
    val activeProfile = profiles.find { it.id == activeProfileId } ?: profiles.firstOrNull() ?: return
    val otherProfiles = profiles.filter { it.id != activeProfileId }
    var expanded by remember { mutableStateOf(false) }
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) NexioColors.FocusRing else Color.Transparent,
        animationSpec = tween(180),
        label = "legacyProfileSwitcherBorder"
    )
    val arrowRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(200),
        label = "legacyProfileSwitcherArrow"
    )

    BackHandler(enabled = expanded) {
        expanded = false
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .clip(RoundedCornerShape(14.dp))
                .background(NexioColors.BackgroundCard)
                .border(2.dp, borderColor, RoundedCornerShape(14.dp))
                .focusRequester(focusRequester)
                .onFocusChanged { focused = it.isFocused }
                .focusable()
                .onPreviewKeyEvent { event ->
                    if (
                        event.type == KeyEventType.KeyDown &&
                        (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                    ) {
                        expanded = !expanded
                        true
                    } else {
                        false
                    }
                }
                .clickable { expanded = !expanded }
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ProfileAvatarCircle(
                name = activeProfile.name,
                colorHex = activeProfile.avatarColorHex,
                size = 40.dp,
                avatarImageUrl = activeProfile.avatarUrl
            )
            Text(
                text = activeProfile.name,
                color = NexioColors.TextPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Icon(
                imageVector = Icons.Default.KeyboardArrowDown,
                contentDescription = "Switch profile",
                tint = NexioColors.TextSecondary,
                modifier = Modifier.graphicsLayer { rotationZ = arrowRotation }
            )
        }

        if (expanded) {
            otherProfiles.forEach { profile ->
                LegacyProfileSwitcherRow(
                    profile = profile,
                    onSelect = {
                        expanded = false
                        onSwitchProfile(profile.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun LegacyProfileSwitcherRow(
    profile: UserProfile,
    onSelect: () -> Unit
) {
    var focused by remember { mutableStateOf(false) }
    val borderColor by animateColorAsState(
        targetValue = if (focused) NexioColors.FocusRing else Color.Transparent,
        animationSpec = tween(180),
        label = "legacyProfileSwitcherRowBorder"
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp)
            .clip(RoundedCornerShape(14.dp))
            .border(2.dp, borderColor, RoundedCornerShape(14.dp))
            .onFocusChanged { focused = it.isFocused }
            .focusable()
            .onPreviewKeyEvent { event ->
                if (
                    event.type == KeyEventType.KeyDown &&
                    (event.key == Key.DirectionCenter || event.key == Key.Enter || event.key == Key.NumPadEnter)
                ) {
                    onSelect()
                    true
                } else {
                    false
                }
            }
            .clickable { onSelect() }
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ProfileAvatarCircle(
            name = profile.name,
            colorHex = profile.avatarColorHex,
            size = 32.dp,
            avatarImageUrl = profile.avatarUrl
        )
        Text(
            text = profile.name,
            color = if (focused) NexioColors.TextPrimary else NexioColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

private fun navigateToDrawerRoute(
    navController: NavHostController,
    currentRoute: String?,
    targetRoute: String
) {
    if (currentRoute == targetRoute) {
        return
    }
    navController.navigate(targetRoute) {
        popUpTo(navController.graph.startDestinationId) {
            saveState = true
        }
        launchSingleTop = true
        restoreState = true
    }
}

private fun isBlockedContentKey(key: Key): Boolean {
    return key == Key.DirectionUp ||
        key == Key.DirectionDown ||
        key == Key.DirectionLeft ||
        key == Key.DirectionRight ||
        key == Key.DirectionCenter ||
        key == Key.Enter
}

@Composable
private fun DrawerItemIcon(
    iconRes: Int?,
    icon: ImageVector?,
    modifier: Modifier = Modifier,
    tint: Color = androidx.tv.material3.LocalContentColor.current
) {
    when {
        icon != null -> Icon(
            imageVector = icon,
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )

        iconRes != null -> Icon(
            painter = rememberRawSvgPainter(iconRes),
            contentDescription = null,
            tint = tint,
            modifier = modifier
        )
    }
}

@Composable
private fun rememberRawSvgPainter(rawIconRes: Int): Painter = rememberAsyncImagePainter(
    model = ImageRequest.Builder(androidx.compose.ui.platform.LocalContext.current)
        .data(rawIconRes)
        .decoderFactory(SvgDecoder.Factory())
        .build()
)

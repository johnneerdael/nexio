package com.nexio.tv

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.SystemClock
import android.os.Bundle
import android.util.Log
import android.app.Activity
import android.view.Choreographer
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
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateDp
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.updateTransition
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
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
import androidx.compose.material.icons.filled.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.TransformOrigin
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
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
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.core.recommendations.AndroidTvChannelPublisher
import com.nexio.tv.data.local.AppOnboardingDataStore
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.local.DebugSettingsDataStore
import com.nexio.tv.data.local.LayoutPreferenceDataStore
import com.nexio.tv.data.local.ThemeDataStore
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.domain.model.AppFont
import com.nexio.tv.domain.model.AppTheme
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.core.sync.StartupSyncService
import com.nexio.tv.ui.navigation.NexioNavHost
import com.nexio.tv.ui.navigation.Screen
import com.nexio.tv.ui.components.NexioScrollDefaults
import com.nexio.tv.ui.screens.account.AuthQrSignInScreen
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import com.nexio.tv.updater.UpdateViewModel
import com.nexio.tv.updater.ui.UpdatePromptDialog
import dagger.hilt.android.AndroidEntryPoint
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.haze
import javax.inject.Inject
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
import com.nexio.tv.R
import com.nexio.tv.DrawerItem
import com.nexio.tv.ModernSidebarBlurPanel
import androidx.lifecycle.Lifecycle
import kotlin.coroutines.resume

val LocalSidebarExpanded = compositionLocalOf { false }

private data class MainUiPrefs(
    val theme: AppTheme = AppTheme.WHITE,
    val font: AppFont = AppFont.INTER,
    val hasChosenLayout: Boolean? = null,
    val sidebarCollapsed: Boolean = false,
    val modernSidebarEnabled: Boolean = false,
    val modernSidebarBlurPref: Boolean = false
)

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    companion object {
        const val EXTRA_RECOMMENDATION_FEED_KEY = "recommendation_feed_key"
        const val EXTRA_RECOMMENDATION_CONTENT_ID = "recommendation_content_id"
        const val EXTRA_RECOMMENDATION_CONTENT_TYPE = "recommendation_content_type"
        const val EXTRA_RECOMMENDATION_ADDON_BASE_URL = "recommendation_addon_base_url"
        private const val STARTUP_PERF_WINDOW_MS = 12_000L
        private const val STARTUP_DEFERRED_WORK_MIN_DELAY_MS = 2_000L
        private const val BROWSABLE_REQUEST_COOLDOWN_MS = 24L * 60 * 60 * 1000
    }

    @Inject
    lateinit var themeDataStore: ThemeDataStore

    @Inject
    lateinit var layoutPreferenceDataStore: LayoutPreferenceDataStore

    @Inject
    lateinit var traktProgressService: TraktProgressService

    @Inject
    lateinit var startupSyncService: StartupSyncService

    @Inject
    lateinit var authManager: AuthManager

    @Inject
    lateinit var appOnboardingDataStore: AppOnboardingDataStore

    @Inject
    lateinit var androidTvRecommendationsDataStore: AndroidTvRecommendationsDataStore

    @Inject
    lateinit var androidTvChannelPublisher: AndroidTvChannelPublisher

    @Inject
    lateinit var debugSettingsDataStore: DebugSettingsDataStore

    private lateinit var jankStats: JankStats
    private val pendingRecommendationNavigation = mutableStateOf<RecommendationNavigation?>(null)
    private val pendingFeedNavigation = mutableStateOf<RecommendationFeedNavigation?>(null)
    private var pendingBrowsableChannelId: Long? = null
    private var channelBrowsableRequestInFlight: Boolean = false
    @Volatile
    private var startupPerfTelemetryEnabled: Boolean = false
    @Volatile
    private var diskFirstHomeStartupEnabled: Boolean = false
    @Volatile
    private var startupPerfWindowOpen: Boolean = false
    private var startupWindowOpenedAtMs: Long = 0L
    private var startupDeferralGeneration: Long = 0L
    private var deferredStartupWorkJob: Job? = null
    private var deferredBrowsableRequestJob: Job? = null
    private var startupPerfWindowJob: Job? = null
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

    @OptIn(ExperimentalFoundationApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        handleRecommendationIntent(intent)
        lifecycleScope.launch {
            debugSettingsDataStore.startupPerfTelemetryEnabled.collect { enabled ->
                startupPerfTelemetryEnabled = enabled
            }
        }
        lifecycleScope.launch {
            debugSettingsDataStore.diskFirstHomeStartupEnabled.collect { enabled ->
                diskFirstHomeStartupEnabled = enabled
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
                .collectAsState(initial = null)
            val authState by authManager.authState.collectAsState()

            LaunchedEffect(hasSeenAuthQrOnFirstLaunch, authState) {
                if (hasSeenAuthQrOnFirstLaunch == false && authState is AuthState.FullAccount) {
                    appOnboardingDataStore.setHasSeenAuthQrOnFirstLaunch(true)
                    onboardingCompletedThisSession = true
                }
            }

            val mainUiPrefsFlow = remember(themeDataStore, layoutPreferenceDataStore) {
                combine(
                    themeDataStore.selectedTheme,
                    themeDataStore.selectedFont,
                    layoutPreferenceDataStore.hasChosenLayout,
                    layoutPreferenceDataStore.sidebarCollapsedByDefault,
                    layoutPreferenceDataStore.modernSidebarEnabled,
                ) { theme, font, hasChosenLayout, sidebarCollapsed, modernSidebarEnabled ->
                    MainUiPrefs(
                        theme = theme,
                        font = font,
                        hasChosenLayout = hasChosenLayout,
                        sidebarCollapsed = sidebarCollapsed,
                        modernSidebarEnabled = modernSidebarEnabled,
                    )
                }.combine(layoutPreferenceDataStore.modernSidebarBlurEnabled) { prefs, modernSidebarBlurPref ->
                    prefs.copy(modernSidebarBlurPref = modernSidebarBlurPref)
                }
            }
            val mainUiPrefs by mainUiPrefsFlow.collectAsState(initial = MainUiPrefs(hasChosenLayout = null))

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
                        hasSeenAuthQrOnFirstLaunch == false &&
                        authState !is AuthState.FullAccount &&
                        !onboardingCompletedThisSession
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
                    val sidebarCollapsed = mainUiPrefs.sidebarCollapsed
                    val modernSidebarEnabled = mainUiPrefs.modernSidebarEnabled
                    val modernSidebarBlurEnabled =
                        mainUiPrefs.modernSidebarBlurPref && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S
                    val hideBuiltInHeadersForFloatingPill = modernSidebarEnabled && !sidebarCollapsed

                    val updateViewModel: UpdateViewModel = hiltViewModel(this@MainActivity)
                    val updateState by updateViewModel.uiState.collectAsState()

                    val startDestination = if (layoutChosen) Screen.Home.route else Screen.LayoutSelection.route
                    val navController = rememberNavController()
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route
                    val pendingRecommendation by pendingRecommendationNavigation
                    val pendingFeed by pendingFeedNavigation

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

                    LaunchedEffect(pendingFeed) {
                        val navigation = pendingFeed ?: return@LaunchedEffect
                        navController.navigate(Screen.AndroidTvFeed.createRoute(navigation.feedKey))
                        pendingFeedNavigation.value = null
                    }

                    val view = LocalView.current
                    LaunchedEffect(currentRoute) {
                        val holder = PerformanceMetricsState.getHolderForHierarchy(view)
                        if (currentRoute != null) {
                            holder.state?.putState("Screen", currentRoute)
                        }
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
                    val selectedDrawerItem = drawerItems.firstOrNull { it.route == selectedDrawerRoute } ?: drawerItems.first()

                    if (modernSidebarEnabled) {
                        ModernSidebarScaffold(
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            selectedDrawerItem = selectedDrawerItem,
                            sidebarCollapsed = sidebarCollapsed,
                            modernSidebarBlurEnabled = modernSidebarBlurEnabled,
                            hideBuiltInHeaders = hideBuiltInHeadersForFloatingPill,
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    } else {
                        LegacySidebarScaffold(
                            navController = navController,
                            startDestination = startDestination,
                            currentRoute = currentRoute,
                            rootRoutes = rootRoutes,
                            drawerItems = drawerItems,
                            selectedDrawerRoute = selectedDrawerRoute,
                            sidebarCollapsed = sidebarCollapsed,
                            hideBuiltInHeaders = false,
                            onExitApp = {
                                finishAffinity()
                                finishAndRemoveTask()
                            }
                        )
                    }

                    UpdatePromptDialog(
                        state = updateState,
                        onDismiss = { updateViewModel.dismissDialog() },
                        onDownload = { updateViewModel.downloadUpdate() },
                        onInstall = { updateViewModel.installUpdateOrRequestPermission() },
                        onIgnore = { updateViewModel.ignoreThisVersion() },
                        onOpenUnknownSources = { updateViewModel.openUnknownSourcesSettings() }
                    )
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

    override fun onPause() {
        super.onPause()
        if (::jankStats.isInitialized) jankStats.isTrackingEnabled = false
    }

    override fun onStart() {
        super.onStart()
        startupSyncService.setStartupGateOpen(false)
        logStartupPerf("on_start")
        startupPerfWindowOpen = true
        startupWindowOpenedAtMs = SystemClock.elapsedRealtime()
        startupPerfWindowJob?.cancel()
        startupPerfWindowJob = lifecycleScope.launch {
            delay(STARTUP_PERF_WINDOW_MS)
            startupPerfWindowOpen = false
            logStartupPerf("startup_window_closed")
        }
        scheduleDeferredStartupWork()
    }

    override fun onStop() {
        super.onStop()
        startupPerfWindowOpen = false
        deferredStartupWorkJob?.cancel()
        deferredBrowsableRequestJob?.cancel()
        startupPerfWindowJob?.cancel()
        startupSyncService.setStartupGateOpen(false)
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
            startupSyncService.setStartupGateOpen(true)
            logStartupPerf("startup_sync_request_start")
            startupSyncService.requestSyncNow()
            logStartupPerf("startup_sync_request_end")
            logStartupPerf("channel_sync_request_start", "reason=app_start_deferred")
            androidTvChannelPublisher.requestSync("app_start_deferred")
            logStartupPerf("channel_sync_request_end", "reason=app_start_deferred")
            maybeLaunchPendingBrowsableChannelRequest()
            launch {
                if (diskFirstHomeStartupEnabled) {
                    logStartupPerf("trakt_refresh_deferred", "reason=disk_first_startup_gate")
                    return@launch
                }
                logStartupPerf("trakt_refresh_start")
                runCatching { traktProgressService.refreshNow() }
                    .onFailure { error ->
                        logStartupPerf("trakt_refresh_failed", "message=${error.message ?: "unknown"}")
                        Log.w("MainActivity", "Deferred Trakt startup refresh failed", error)
                    }
                    .onSuccess {
                        logStartupPerf("trakt_refresh_end")
                    }
            }
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
    onExitApp: () -> Unit
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }
    val showSidebar = currentRoute in rootRoutes

    LaunchedEffect(currentRoute) {
        drawerState.setValue(DrawerValue.Closed)
    }

    val closedDrawerWidth = if (sidebarCollapsed) 0.dp else 72.dp
    val openDrawerWidth = 216.dp

    val focusManager = LocalFocusManager.current
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
        focusManager.moveFocus(FocusDirection.Right)
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(drawerState.currentValue, selectedDrawerRoute, showSidebar, pendingSidebarFocusRequest) {
        if (!showSidebar || !pendingSidebarFocusRequest || drawerState.currentValue != DrawerValue.Open) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
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
                                .height(42.dp)
                        )
                        Spacer(modifier = Modifier.height(16.dp))
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
        val contentStartPadding = if (showSidebar) closedDrawerWidth else 0.dp
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
                        if (focusManager.moveFocus(FocusDirection.Left)) {
                            true
                        } else {
                            pendingSidebarFocusRequest = true
                            drawerState.setValue(DrawerValue.Open)
                            true
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides (drawerState.currentValue == DrawerValue.Open)
            ) {
                NexioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
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
private fun ModernSidebarScaffold(
    navController: NavHostController,
    startDestination: String,
    currentRoute: String?,
    rootRoutes: Set<String>,
    drawerItems: List<DrawerItem>,
    selectedDrawerRoute: String?,
    selectedDrawerItem: DrawerItem,
    sidebarCollapsed: Boolean,
    modernSidebarBlurEnabled: Boolean,
    hideBuiltInHeaders: Boolean,
    onExitApp: () -> Unit
) {
    val showSidebar = currentRoute in rootRoutes
    val collapsedSidebarWidth = if (sidebarCollapsed) 0.dp else 184.dp
    val openSidebarWidth = 262.dp

    val focusManager = LocalFocusManager.current
    val drawerItemFocusRequesters = remember(drawerItems) {
        drawerItems.associate { item -> item.route to FocusRequester() }
    }

    var isSidebarExpanded by remember { mutableStateOf(false) }
    var sidebarCollapsePending by remember { mutableStateOf(false) }
    var pendingContentFocusTransfer by remember { mutableStateOf(false) }
    var pendingSidebarFocusRequest by remember { mutableStateOf(false) }
    var focusedDrawerIndex by remember { mutableStateOf(-1) }
    var isFloatingPillIconOnly by remember { mutableStateOf(false) }
    val keepFloatingPillExpanded = selectedDrawerRoute == Screen.Settings.route
    val keepSidebarFocusDuringCollapse =
        isSidebarExpanded || sidebarCollapsePending || pendingContentFocusTransfer

    LaunchedEffect(showSidebar) {
        if (!showSidebar) {
            isSidebarExpanded = false
            sidebarCollapsePending = false
            pendingContentFocusTransfer = false
            pendingSidebarFocusRequest = false
            isFloatingPillIconOnly = false
        }
    }

    LaunchedEffect(keepFloatingPillExpanded, showSidebar) {
        if (!showSidebar || keepFloatingPillExpanded) {
            isFloatingPillIconOnly = false
        }
    }

    BackHandler(enabled = currentRoute in rootRoutes && !isSidebarExpanded && !sidebarCollapsePending) {
        isSidebarExpanded = true
        sidebarCollapsePending = false
        pendingSidebarFocusRequest = true
    }

    BackHandler(enabled = currentRoute in rootRoutes && isSidebarExpanded && !sidebarCollapsePending) {
        onExitApp()
    }

    LaunchedEffect(sidebarCollapsePending, isSidebarExpanded, showSidebar) {
        if (!showSidebar || !sidebarCollapsePending) {
            return@LaunchedEffect
        }
        if (!isSidebarExpanded) {
            sidebarCollapsePending = false
            return@LaunchedEffect
        }
        delay(95L)
        isSidebarExpanded = false
        sidebarCollapsePending = false
    }

    val sidebarVisible = showSidebar && (isSidebarExpanded || !sidebarCollapsed)
    val sidebarHazeState = remember { HazeState() }
    val targetSidebarWidth = when {
        !sidebarVisible -> 0.dp
        isSidebarExpanded -> openSidebarWidth
        else -> collapsedSidebarWidth
    }
    val sidebarWidth by animateDpAsState(
        targetValue = targetSidebarWidth,
        animationSpec = if (isSidebarExpanded) {
            keyframes {
                durationMillis = 365
                (openSidebarWidth + 12.dp) at 175
            }
        } else {
            tween(durationMillis = 385, easing = LinearOutSlowInEasing)
        },
        label = "sidebarWidth"
    )
    val sidebarSlideX by animateDpAsState(
        targetValue = if (sidebarVisible) 0.dp else (-24).dp,
        animationSpec = tween(durationMillis = 205, easing = FastOutSlowInEasing),
        label = "sidebarSlideX"
    )
    val sidebarSurfaceAlpha by animateFloatAsState(
        targetValue = if (sidebarVisible) 1f else 0f,
        animationSpec = tween(durationMillis = 135, easing = FastOutSlowInEasing),
        label = "sidebarSurfaceAlpha"
    )
    val shouldApplySidebarHaze = showSidebar && modernSidebarBlurEnabled && (
        isSidebarExpanded || sidebarCollapsePending
        )
    val sidebarTransition = updateTransition(
        targetState = isSidebarExpanded,
        label = "sidebarTransition"
    )
    val sidebarLabelAlpha by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 125, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 145, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarLabelAlpha"
    ) { expanded ->
        if (expanded) 1f else 0f
    }
    val sidebarExpandProgress by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 385, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarExpandProgress"
    ) { expanded ->
        if (expanded) 1f else 0f
    }

    // derivedStateOf prevents per-frame recomposition — only triggers when the boolean crosses the threshold
    val sidebarBlocksContentKeys by remember { derivedStateOf { sidebarExpandProgress > 0.2f } }
    val sidebarShowExpandedPanel by remember { derivedStateOf { sidebarExpandProgress > 0.01f } }
    val sidebarShowCollapsedPill by remember { derivedStateOf { sidebarExpandProgress < 0.98f } }

    val sidebarIconScale by sidebarTransition.animateFloat(
        transitionSpec = { tween(durationMillis = 145, easing = FastOutSlowInEasing) },
        label = "sidebarIconScale"
    ) { expanded ->
        if (expanded) 1f else 0.92f
    }
    val sidebarBloomScale by sidebarTransition.animateFloat(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarBloomScale"
    ) { expanded ->
        if (expanded) 1f else 0.9f
    }
    val sidebarDeflateOffsetX by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetX"
    ) { expanded ->
        if (expanded) 0.dp else (-10).dp
    }
    val sidebarDeflateOffsetY by sidebarTransition.animateDp(
        transitionSpec = {
            if (targetState) {
                tween(durationMillis = 345, easing = FastOutSlowInEasing)
            } else {
                tween(durationMillis = 395, easing = LinearOutSlowInEasing)
            }
        },
        label = "sidebarDeflateOffsetY"
    ) { expanded ->
        if (expanded) 0.dp else (-8).dp
    }

    LaunchedEffect(isSidebarExpanded, sidebarCollapsePending, pendingContentFocusTransfer, showSidebar) {
        if (!showSidebar || !pendingContentFocusTransfer || isSidebarExpanded || sidebarCollapsePending) {
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        focusManager.moveFocus(FocusDirection.Right)
        pendingContentFocusTransfer = false
    }

    LaunchedEffect(isSidebarExpanded, pendingSidebarFocusRequest, showSidebar, selectedDrawerRoute) {
        if (!showSidebar || !pendingSidebarFocusRequest || !isSidebarExpanded) {
            return@LaunchedEffect
        }
        val targetRoute = selectedDrawerRoute ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        val requester = drawerItemFocusRequesters[targetRoute] ?: run {
            pendingSidebarFocusRequest = false
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        runCatching { requester.requestFocus() }
        pendingSidebarFocusRequest = false
    }

    Box(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (shouldApplySidebarHaze) {
                        Modifier.haze(sidebarHazeState)
                    } else {
                        Modifier
                    }
                )
                .onPreviewKeyEvent { keyEvent ->
                    if (
                        isSidebarExpanded &&
                        !sidebarCollapsePending &&
                        sidebarBlocksContentKeys &&
                        keyEvent.type == KeyEventType.KeyDown &&
                        isBlockedContentKey(keyEvent.key)
                    ) {
                        true
                    } else {
                        false
                    }
                }
                .onKeyEvent { keyEvent ->
                    if (showSidebar && !isSidebarExpanded && keyEvent.type == KeyEventType.KeyDown) {
                        if (!keepFloatingPillExpanded) {
                            when (keyEvent.key) {
                                Key.DirectionDown -> isFloatingPillIconOnly = true
                                Key.DirectionUp -> isFloatingPillIconOnly = false
                                else -> Unit
                            }
                        }
                        if (keyEvent.key == Key.DirectionLeft) {
                            if (focusManager.moveFocus(FocusDirection.Left)) {
                                true
                            } else {
                                isSidebarExpanded = true
                                sidebarCollapsePending = false
                                pendingSidebarFocusRequest = true
                                true
                            }
                        } else {
                            false
                        }
                    } else {
                        false
                    }
                }
        ) {
            CompositionLocalProvider(
                LocalSidebarExpanded provides isSidebarExpanded
            ) {
                NexioNavHost(
                    navController = navController,
                    startDestination = startDestination,
                    hideBuiltInHeaders = hideBuiltInHeaders
                )
            }
        }

        if (showSidebar && (sidebarVisible || sidebarWidth > 0.dp)) {
            val panelShape = RoundedCornerShape(30.dp)
            val showExpandedPanel = isSidebarExpanded || sidebarShowExpandedPanel

            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .width(sidebarWidth)
                    .padding(start = 14.dp, top = 16.dp, bottom = 12.dp, end = 8.dp)
                    .offset {
                        IntOffset(
                            (sidebarSlideX + sidebarDeflateOffsetX).roundToPx(),
                            sidebarDeflateOffsetY.roundToPx()
                        )
                    }
                    .graphicsLayer {
                        alpha = sidebarSurfaceAlpha
                        scaleX = sidebarBloomScale
                        scaleY = sidebarBloomScale
                        transformOrigin = TransformOrigin(0f, 0f)
                    }
                    .selectableGroup()
                    .onPreviewKeyEvent { keyEvent ->
                        if (!isSidebarExpanded || keyEvent.type != KeyEventType.KeyDown) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.key) {
                            Key.DirectionUp -> {
                                focusedDrawerIndex == 0
                            }

                            Key.DirectionDown -> {
                                focusedDrawerIndex > drawerItems.lastIndex
                            }

                            Key.DirectionRight -> {
                                pendingContentFocusTransfer = false
                                sidebarCollapsePending = true
                                true
                            }

                            else -> false
                        }
                    }
            ) {
                if (showExpandedPanel) {
                    ModernSidebarBlurPanel(
                        drawerItems = drawerItems,
                        selectedDrawerRoute = selectedDrawerRoute,
                        keepSidebarFocusDuringCollapse = keepSidebarFocusDuringCollapse,
                        sidebarLabelAlpha = sidebarLabelAlpha,
                        sidebarIconScale = sidebarIconScale,
                        sidebarExpandProgress = sidebarExpandProgress,
                        isSidebarExpanded = isSidebarExpanded,
                        sidebarCollapsePending = sidebarCollapsePending,
                        blurEnabled = modernSidebarBlurEnabled,
                        sidebarHazeState = sidebarHazeState,
                        panelShape = panelShape,
                        drawerItemFocusRequesters = drawerItemFocusRequesters,
                        onDrawerItemFocused = { focusedDrawerIndex = it },
                        onDrawerItemClick = { targetRoute ->
                            navigateToDrawerRoute(
                                navController = navController,
                                currentRoute = currentRoute,
                                targetRoute = targetRoute
                            )
                            pendingSidebarFocusRequest = false
                            isSidebarExpanded = false
                            sidebarCollapsePending = false
                            pendingContentFocusTransfer = true
                        }
                    )
                }
            }

            if (
                !sidebarCollapsed &&
                sidebarShowCollapsedPill &&
                selectedDrawerRoute != Screen.Search.route
            ) {
                CollapsedSidebarPill(
                    label = selectedDrawerItem.label,
                    iconRes = selectedDrawerItem.iconRes,
                    icon = selectedDrawerItem.icon,
                    iconOnly = isFloatingPillIconOnly && !keepFloatingPillExpanded,
                    blurEnabled = modernSidebarBlurEnabled,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .offset {
                            IntOffset(
                                (40.dp + sidebarSlideX + sidebarDeflateOffsetX).roundToPx(),
                                (16.dp + sidebarDeflateOffsetY).roundToPx()
                            )
                        }
                        .graphicsLayer {
                            val progress = sidebarExpandProgress
                            alpha = 1f - progress
                            val s = 0.9f + (0.1f * (1f - progress))
                            scaleX = s
                            scaleY = s
                            transformOrigin = TransformOrigin(0f, 0f)
                        },
                    onExpand = {
                        isSidebarExpanded = true
                        sidebarCollapsePending = false
                        pendingSidebarFocusRequest = true
                    }
                )
            }
        }
    }
}

@Composable
private fun CollapsedSidebarPill(
    label: String,
    iconRes: Int?,
    icon: ImageVector?,
    iconOnly: Boolean,
    blurEnabled: Boolean,
    modifier: Modifier = Modifier,
    onExpand: () -> Unit
) {
    val pillShape = RoundedCornerShape(999.dp)
    val bgElevated = NexioColors.BackgroundElevated
    val bgCard = NexioColors.BackgroundCard
    val borderBase = NexioColors.Border
    val pillBackgroundBrush = remember(blurEnabled, bgElevated, bgCard) {
        if (blurEnabled) {
            Brush.verticalGradient(listOf(Color(0xD1424851), Color(0xC73B4149)))
        } else {
            Brush.verticalGradient(listOf(bgElevated, bgCard))
        }
    }
    val pillBorderColor = remember(blurEnabled, borderBase) {
        if (blurEnabled) Color.White.copy(alpha = 0.14f) else borderBase.copy(alpha = 0.9f)
    }

    Row(
        modifier = modifier
            .focusProperties { canFocus = false }
            .animateContentSize()
            .clickable(onClick = onExpand)
            .padding(horizontal = 1.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(0.25.dp)
    ) {
        if (!iconOnly) {
            Image(
                painter = painterResource(id = R.drawable.ic_chevron_compact_left),
                contentDescription = stringResource(R.string.cd_expand_sidebar),
                modifier = Modifier
                    .width(8.5.dp)
                    .height(16.dp)
                    .offset(y = (-0.5).dp)
            )
        }

        Box(
            modifier = Modifier
                .height(44.dp)
                .graphicsLayer {
                    shape = pillShape
                    clip = true
                    compositingStrategy = CompositingStrategy.Offscreen
                }
                .clip(pillShape)
                .background(brush = pillBackgroundBrush, shape = pillShape)
                .border(width = 1.dp, color = pillBorderColor, shape = pillShape)
        ) {
            Row(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .padding(start = 5.dp, end = if (iconOnly) 5.dp else 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(if (iconOnly) 0.dp else 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(34.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF4F555E)),
                    contentAlignment = Alignment.Center
                ) {
                    DrawerItemIcon(
                        iconRes = iconRes,
                        icon = icon,
                        tint = Color.White,
                        modifier = Modifier
                            .size(22.dp)
                            .offset(y = (-0.5).dp)
                    )
                }

                if (!iconOnly) {
                    Text(
                        text = label,
                        color = Color.White,
                        style = androidx.tv.material3.MaterialTheme.typography.titleLarge.copy(
                            lineHeight = 30.sp
                        ),
                        modifier = Modifier.offset(y = (-0.5).dp),
                        maxLines = 1
                    )
                }
            }
        }
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

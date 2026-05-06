package com.nexio.tv.ui.screens.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.domain.model.HomeLayout
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.data.repository.TraktRecommendationRef
import com.nexio.tv.ui.components.ErrorState
import com.nexio.tv.ui.components.LoadingIndicator
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.components.PosterCardDefaults
import com.nexio.tv.ui.components.PosterCardStyle
import com.nexio.tv.ui.components.TrailerPlayer
import androidx.compose.ui.res.stringResource
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexio.tv.R
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

private data class HomePosterOptionsTarget(
    val item: MetaPreview,
    val addonBaseUrl: String,
    val statusKey: String,
    val recommendationRef: TraktRecommendationRef?
)

private data class HomePosterTrailerPendingResolution(
    val item: MetaPreview
)

private const val HOME_STARTUP_CONTENT_TIMEOUT_MS = 5_000L

internal fun shouldShowHomeManualStreamSelection(
    deterministicAutoplayEnabled: Boolean,
    homeLayout: HomeLayout,
    apiType: String
): Boolean {
    return deterministicAutoplayEnabled &&
        homeLayout == HomeLayout.MODERN &&
        apiType.equals("movie", ignoreCase = true)
}

internal fun shouldShowContinueWatchingManualStreamSelection(
    deterministicAutoplayEnabled: Boolean,
    item: ContinueWatchingItem
): Boolean {
    if (!deterministicAutoplayEnabled) return false
    val contentType = when (item) {
        is ContinueWatchingItem.InProgress -> item.progress.contentType
        is ContinueWatchingItem.NextUp -> item.info.contentType
    }
    return contentType.equals("movie", ignoreCase = true) ||
        contentType.equals("series", ignoreCase = true)
}

internal fun hasRenderableHomeContent(uiState: HomeUiState): Boolean {
    val hasCatalogContent = uiState.catalogRows.any { row ->
        row.items.isNotEmpty() ||
            (uiState.homeLayout == HomeLayout.MODERN && row.isLoading)
    }
    return hasCatalogContent ||
        uiState.continueWatchingItems.isNotEmpty() ||
        uiState.heroItems.isNotEmpty()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel = hiltViewModel(),
    idleScreensaverVisible: Boolean = false,
    startupSplashVisible: Boolean = false,
    onModernHomeTrailerPlaybackStarted: () -> Unit = {},
    onModernHomeTrailerPlaybackActiveChanged: (Boolean) -> Unit = {},
    onModernHomeTrailerFullscreenActiveChanged: (Boolean) -> Unit = {},
    onNavigateToDetail: (String, String, String) -> Unit,
    onPlayWithManualStreamSelection: (MetaPreview) -> Unit = {},
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit = { item ->
        onNavigateToDetail(
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentId
                is ContinueWatchingItem.NextUp -> item.info.contentId
            },
            when (item) {
                is ContinueWatchingItem.InProgress -> item.progress.contentType
                is ContinueWatchingItem.NextUp -> item.info.contentType
            },
            ""
        )
    },
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = onContinueWatchingClick,
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit = {},
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit = { _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val hasRenderableContent = hasRenderableHomeContent(uiState)
    var showHomeContentWithAnimation by rememberSaveable { mutableStateOf(false) }
    var startupContentGateTimedOut by rememberSaveable { mutableStateOf(false) }
    var posterOptionsTarget by remember { mutableStateOf<HomePosterOptionsTarget?>(null) }
    var posterTrailerPlayback by remember { mutableStateOf<HomePosterTrailerPlayback?>(null) }
    var pendingPosterTrailerResolution by remember { mutableStateOf<HomePosterTrailerPendingResolution?>(null) }
    val shouldArmStartupTimeout = uiState.isLoading && !hasRenderableContent && uiState.error == null
    val latestMovieWatchedStatus by rememberUpdatedState(uiState.movieWatchedStatus)
    val latestTraktRecommendationRefs by rememberUpdatedState(uiState.traktRecommendationRefs)
    val isCatalogItemWatched: (MetaPreview) -> Boolean = remember(Unit) {
        { item -> latestMovieWatchedStatus[homeItemStatusKey(item.id, item.apiType)] == true }
    }
    val onCatalogItemLongPress: (MetaPreview, String) -> Unit = remember(Unit) {
        { item, addonBaseUrl ->
            val statusKey = homeItemStatusKey(item.id, item.apiType)
            posterOptionsTarget = HomePosterOptionsTarget(
                item = item,
                addonBaseUrl = addonBaseUrl,
                statusKey = statusKey,
                recommendationRef = latestTraktRecommendationRefs[statusKey]
            )
        }
    }

    LaunchedEffect(shouldArmStartupTimeout) {
        if (!shouldArmStartupTimeout) {
            startupContentGateTimedOut = false
            return@LaunchedEffect
        }
        startupContentGateTimedOut = false
        delay(HOME_STARTUP_CONTENT_TIMEOUT_MS)
        startupContentGateTimedOut = true
    }

    LaunchedEffect(startupContentGateTimedOut, hasRenderableContent) {
        if (startupContentGateTimedOut && !hasRenderableContent) {
            viewModel.logStartupPerf(
                event = "home_startup_gate_timeout",
                details = "timeoutMs=$HOME_STARTUP_CONTENT_TIMEOUT_MS"
            )
        }
    }

    LaunchedEffect(activity) {
        if (activity != null) {
            FrameRateUtils.setBlockDisplayModeChangesOutsideMainPlayer(true)
            FrameRateUtils.setMainPlayerDisplayModeSessionActive(false)
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    DisposableEffect(uiState.homeLayout, onModernHomeTrailerPlaybackActiveChanged) {
        if (uiState.homeLayout != HomeLayout.MODERN) {
            onModernHomeTrailerPlaybackActiveChanged(false)
        }
        onDispose {
            onModernHomeTrailerPlaybackActiveChanged(false)
        }
    }
    DisposableEffect(uiState.homeLayout, onModernHomeTrailerFullscreenActiveChanged) {
        if (uiState.homeLayout != HomeLayout.MODERN) {
            onModernHomeTrailerFullscreenActiveChanged(false)
        }
        onDispose {
            onModernHomeTrailerFullscreenActiveChanged(false)
        }
    }

    val posterCardStyle = remember(
        uiState.posterCardWidthDp,
        uiState.posterCardCornerRadiusDp
    ) {
        val computedHeightDp = (uiState.posterCardWidthDp * 1.5f).roundToInt()
        PosterCardStyle(
            width = uiState.posterCardWidthDp.dp,
            height = computedHeightDp.dp,
            cornerRadius = uiState.posterCardCornerRadiusDp.dp,
            focusedBorderWidth = PosterCardDefaults.Style.focusedBorderWidth,
            focusedScale = PosterCardDefaults.Style.focusedScale
        )
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
    ) {
        when {
            uiState.isLoading && !hasRenderableContent && !startupContentGateTimedOut -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }

            startupContentGateTimedOut && !hasRenderableContent && uiState.error == null -> {
                ErrorState(
                    message = stringResource(R.string.home_startup_loading_timeout),
                    onRetry = {
                        viewModel.logStartupPerf("home_startup_gate_retry")
                        startupContentGateTimedOut = false
                        viewModel.onEvent(HomeEvent.OnRetry)
                    }
                )
            }

            uiState.error == "No addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioColors.TextSecondary
                    )
                }
            }

            uiState.error == "No catalog addons installed" && uiState.catalogRows.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.home_no_catalog_addons),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioColors.TextSecondary
                    )
                }
            }

            uiState.error != null && uiState.catalogRows.isEmpty() -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(HomeEvent.OnRetry) }
                )
            }

            else -> {
                val shouldWaitForContinueWatching =
                    uiState.homeLayout == HomeLayout.MODERN &&
                        !uiState.initialContinueWatchingResolved &&
                        uiState.error == null &&
                        uiState.installedAddonsCount > 0
                val shouldShowLoadingGate = (!hasRenderableContent || shouldWaitForContinueWatching) &&
                    uiState.error == null &&
                    !startupContentGateTimedOut
                LaunchedEffect(shouldShowLoadingGate) {
                    if (shouldShowLoadingGate) {
                        showHomeContentWithAnimation = false
                    } else {
                        // Flip on the next frame so AnimatedVisibility can run enter transition.
                        kotlinx.coroutines.yield()
                        showHomeContentWithAnimation = true
                    }
                }
                if (shouldShowLoadingGate) {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        LoadingIndicator()
                    }
                } else {
                    AnimatedVisibility(
                        visible = showHomeContentWithAnimation,
                        enter = fadeIn(animationSpec = tween(320)) +
                            slideInVertically(
                                initialOffsetY = { it / 24 },
                                animationSpec = tween(320)
                            )
                    ) {
                        when (uiState.homeLayout) {
                            HomeLayout.CLASSIC -> ClassicHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.GRID -> GridHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                posterCardStyle = posterCardStyle,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
                                onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )

                            HomeLayout.MODERN -> ModernHomeRoute(
                                viewModel = viewModel,
                                uiState = uiState,
                                idleScreensaverVisible = idleScreensaverVisible,
                                startupSplashVisible = startupSplashVisible,
                                externalTrailerTakeoverActive = posterTrailerPlayback != null || pendingPosterTrailerResolution != null,
                                onModernHomeTrailerPlaybackStarted = onModernHomeTrailerPlaybackStarted,
                                onModernHomeTrailerPlaybackActiveChanged = onModernHomeTrailerPlaybackActiveChanged,
                                onNavigateToDetail = onNavigateToDetail,
                                onContinueWatchingClick = onContinueWatchingClick,
                                onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
                                onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
                                isCatalogItemWatched = isCatalogItemWatched,
                                onCatalogItemLongPress = onCatalogItemLongPress
                            )
                        }
                    }
                }
            }
        }
    }

    val selectedPoster = posterOptionsTarget
    if (selectedPoster != null) {
        val item = selectedPoster.item
        val statusKey = homeItemStatusKey(item.id, item.apiType)
        val isMovie = item.apiType.equals("movie", ignoreCase = true)
        val hasTrailerAction = hasHomeTrailerAction(
            itemId = item.id,
            apiType = item.apiType,
            metadataAvailableKeys = viewModel.trailerMetadataAvailableKeys,
            previewUrls = viewModel.trailerPreviewUrls,
            previewExternalUrls = viewModel.trailerPreviewExternalUrls
        )
        val trailerPlayback = playableHomeTrailerFor(
            itemId = item.id,
            title = item.name,
            item = item,
            previewUrls = viewModel.trailerPreviewUrls,
            previewAudioUrls = viewModel.trailerPreviewAudioUrls,
            previewUserAgents = viewModel.trailerPreviewUserAgents
        )
        HomePosterOptionsDialog(
            title = item.name,
            isInLibrary = uiState.posterLibraryMembership[statusKey] == true,
            isLibraryPending = statusKey in uiState.posterLibraryPending,
            showManageLists = uiState.librarySourceMode == LibrarySourceMode.TRAKT || uiState.librarySourceMode == LibrarySourceMode.SIMKL,
            isMovie = isMovie,
            showManualStreamSelection = shouldShowHomeManualStreamSelection(
                deterministicAutoplayEnabled = uiState.deterministicAutoplayEnabled,
                homeLayout = uiState.homeLayout,
                apiType = item.apiType
            ),
            isWatched = uiState.movieWatchedStatus[statusKey] == true,
            isWatchedPending = statusKey in uiState.movieWatchedPending,
            showPlayTrailer = hasTrailerAction,
            onDismiss = { posterOptionsTarget = null },
            onPlayWithManualStreamSelection = {
                onPlayWithManualStreamSelection(item)
                posterOptionsTarget = null
            },
            onDetails = {
                onNavigateToDetail(item.id, item.apiType, selectedPoster.addonBaseUrl)
                posterOptionsTarget = null
            },
            onPlayTrailer = {
                if (trailerPlayback != null) {
                    posterTrailerPlayback = trailerPlayback
                } else {
                    pendingPosterTrailerResolution = HomePosterTrailerPendingResolution(item)
                    if (item.id in viewModel.trailerPreviewNegativeCacheIds) {
                        viewModel.retryTrailerPreview(
                            itemId = item.id,
                            title = item.name,
                            releaseInfo = item.releaseInfo,
                            apiType = item.apiType,
                            fallbackYtId = viewModel.trailerSelectedFallbackYtIds[
                                homeTrailerAvailabilityKey(item.id, item.apiType)
                            ]
                        )
                    } else {
                        viewModel.requestTrailerPreview(item)
                    }
                }
                posterOptionsTarget = null
            },
            onToggleLibrary = {
                if (uiState.librarySourceMode == LibrarySourceMode.TRAKT || uiState.librarySourceMode == LibrarySourceMode.SIMKL) {
                    viewModel.openPosterListPicker(item, selectedPoster.addonBaseUrl)
                } else {
                    viewModel.togglePosterLibrary(item, selectedPoster.addonBaseUrl)
                }
                posterOptionsTarget = null
            },
            onToggleWatched = {
                viewModel.togglePosterMovieWatched(item)
                posterOptionsTarget = null
            },
            showHideRecommendation = selectedPoster.recommendationRef != null,
            onHideRecommendation = {
                selectedPoster.recommendationRef?.let { ref ->
                    viewModel.dismissTraktRecommendation(ref)
                }
                posterOptionsTarget = null
            }
        )
    }

    val activePosterTrailerPlayback = posterTrailerPlayback
    var modernPosterTrailerTextTimedOut by remember { mutableStateOf(false) }
    LaunchedEffect(
        pendingPosterTrailerResolution?.item?.id,
        viewModel.trailerPreviewUrls,
        viewModel.trailerPreviewAudioUrls,
        viewModel.trailerPreviewUserAgents,
        viewModel.trailerPreviewExternalUrls,
        viewModel.trailerPreviewLoadingItemIds,
        viewModel.trailerPreviewNegativeCacheIds
    ) {
        val pendingRequest = pendingPosterTrailerResolution ?: return@LaunchedEffect
        val playback = playableHomeTrailerFor(
            itemId = pendingRequest.item.id,
            title = pendingRequest.item.name,
            item = pendingRequest.item,
            previewUrls = viewModel.trailerPreviewUrls,
            previewAudioUrls = viewModel.trailerPreviewAudioUrls,
            previewUserAgents = viewModel.trailerPreviewUserAgents
        )
        if (playback != null) {
            posterTrailerPlayback = playback
            pendingPosterTrailerResolution = null
            return@LaunchedEffect
        }
        val externalUrl = viewModel.trailerPreviewExternalUrls[pendingRequest.item.id]
        if (!externalUrl.isNullOrBlank()) {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(externalUrl)).apply {
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
            pendingPosterTrailerResolution = null
            return@LaunchedEffect
        }
        if (
            pendingRequest.item.id in viewModel.trailerPreviewNegativeCacheIds &&
            pendingRequest.item.id !in viewModel.trailerPreviewLoadingItemIds
        ) {
            pendingPosterTrailerResolution = null
        }
    }
    LaunchedEffect(activePosterTrailerPlayback != null, pendingPosterTrailerResolution != null) {
        if (activePosterTrailerPlayback != null) {
            onModernHomeTrailerPlaybackStarted()
        }
        onModernHomeTrailerPlaybackActiveChanged(
            shouldTreatFocusedPosterTrailerPlaybackAsActiveTime(
                hasActivePlayback = activePosterTrailerPlayback != null,
                trailerMuted = uiState.focusedPosterBackdropTrailerMuted
            )
        )
    }
    LaunchedEffect(uiState.homeLayout, activePosterTrailerPlayback != null, pendingPosterTrailerResolution != null) {
        onModernHomeTrailerFullscreenActiveChanged(
            uiState.homeLayout == HomeLayout.MODERN &&
                (activePosterTrailerPlayback != null || pendingPosterTrailerResolution != null)
        )
    }
    LaunchedEffect(activePosterTrailerPlayback?.itemId, uiState.homeLayout) {
        modernPosterTrailerTextTimedOut = false
        val playback = activePosterTrailerPlayback ?: return@LaunchedEffect
        if (uiState.homeLayout == HomeLayout.MODERN && playback.heroPreview != null) {
            delay(10_000L)
            if (activePosterTrailerPlayback.itemId == playback.itemId) {
                modernPosterTrailerTextTimedOut = true
            }
        }
    }
    if (activePosterTrailerPlayback != null) {
        BackHandler {
            posterTrailerPlayback = null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            TrailerPlayer(
                trailerUrl = activePosterTrailerPlayback.videoUrl,
                trailerAudioUrl = activePosterTrailerPlayback.audioUrl,
                trailerUserAgent = activePosterTrailerPlayback.userAgent,
                isPlaying = true,
                cropToFill = true,
                onEnded = { posterTrailerPlayback = null },
                modifier = Modifier.fillMaxSize()
            )
            if (uiState.homeLayout != HomeLayout.MODERN && activePosterTrailerPlayback.heroPreview != null) {
                ModernHeroGradientLayer(
                    bgColor = NexioColors.Background,
                    modifier = Modifier.fillMaxSize()
                )
                if (!modernPosterTrailerTextTimedOut) {
                    HeroTitleBlock(
                        preview = activePosterTrailerPlayback.heroPreview,
                        portraitMode = !uiState.modernLandscapePostersEnabled,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 52.dp, end = 48.dp, bottom = 64.dp)
                            .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
                    )
                }
            }
        }
    }
    val pendingPosterTrailer = pendingPosterTrailerResolution
    if (pendingPosterTrailer != null && activePosterTrailerPlayback == null) {
        BackHandler {
            pendingPosterTrailerResolution = null
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(pendingPosterTrailer.item.displayBackground ?: pendingPosterTrailer.item.displayPoster)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f)),
                contentAlignment = Alignment.Center
            ) {
                LoadingIndicator()
            }
        }
    }

    if (uiState.showPosterListPicker) {
        HomeLibraryListPickerDialog(
            title = uiState.posterListPickerTitle ?: stringResource(R.string.detail_lists_fallback),
            tabs = uiState.libraryListTabs,
            membership = uiState.posterListPickerMembership,
            isPending = uiState.posterListPickerPending,
            error = uiState.posterListPickerError,
            onToggle = { key -> viewModel.togglePosterListPickerMembership(key) },
            onSave = { viewModel.savePosterListPickerMembership() },
            onDismiss = { viewModel.dismissPosterListPicker() }
        )
    }
}

@Composable
private fun ClassicHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val requestTrailerPreview = remember(viewModel) {
        { item: MetaPreview -> viewModel.requestTrailerPreview(item) }
    }
    ClassicHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        focusState = focusState,
        trailerPreviewUrls = viewModel.trailerPreviewUrls,
        trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
        trailerPreviewUserAgents = viewModel.trailerPreviewUserAgents,
        trailerPreviewExternalUrls = viewModel.trailerPreviewExternalUrls,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        onMarkContinueWatchingWatched = { item ->
            viewModel.markContinueWatchingAsWatched(item)
        },
        onCheckInContinueWatching = { item ->
            viewModel.checkInContinueWatching(item)
        },
        cwWatchlistMembership = uiState.posterLibraryMembership,
        onToggleContinueWatchingLibrary = { item ->
            viewModel.toggleContinueWatchingLibrary(item)
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onRequestTrailerPreview = requestTrailerPreview,
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveFocusState = { vi, vo, ri, ii, m ->
            viewModel.saveFocusState(vi, vo, ri, ii, m)
        }
    )
}

@Composable
private fun GridHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    posterCardStyle: PosterCardStyle,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit,
    onNavigateToCatalogSeeAll: (String, String, String) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val gridFocusState by viewModel.gridFocusState.collectAsStateWithLifecycle()
    GridHomeContent(
        uiState = uiState,
        posterCardStyle = posterCardStyle,
        gridFocusState = gridFocusState,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
        onNavigateToCatalogSeeAll = onNavigateToCatalogSeeAll,
        onRemoveContinueWatching = { contentId, season, episode, isNextUp ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        },
        onMarkContinueWatchingWatched = { item ->
            viewModel.markContinueWatchingAsWatched(item)
        },
        onCheckInContinueWatching = { item ->
            viewModel.checkInContinueWatching(item)
        },
        cwWatchlistMembership = uiState.posterLibraryMembership,
        onToggleContinueWatchingLibrary = { item ->
            viewModel.toggleContinueWatchingLibrary(item)
        },
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = { item ->
            viewModel.onItemFocus(item)
        },
        onSaveGridFocusState = { vi, vo ->
            viewModel.saveGridFocusState(vi, vo)
        }
    )
}

@Composable
private fun ModernHomeRoute(
    viewModel: HomeViewModel,
    uiState: HomeUiState,
    idleScreensaverVisible: Boolean,
    startupSplashVisible: Boolean,
    externalTrailerTakeoverActive: Boolean,
    onModernHomeTrailerPlaybackStarted: () -> Unit,
    onModernHomeTrailerPlaybackActiveChanged: (Boolean) -> Unit,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit,
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit,
    isCatalogItemWatched: (MetaPreview) -> Boolean,
    onCatalogItemLongPress: (MetaPreview, String) -> Unit
) {
    val focusState by viewModel.focusState.collectAsStateWithLifecycle()
    val enrichingItemIdState: State<String?> = viewModel.enrichingItemId.collectAsStateWithLifecycle()
    val modernContentState = remember(
        uiState.catalogRows,
        uiState.continueWatchingItems,
        uiState.modernHomePresentation,
        uiState.deterministicAutoplayEnabled,
        uiState.modernLandscapePostersEnabled,
        uiState.catalogTypeSuffixEnabled,
        uiState.focusedPosterBackdropExpandEnabled,
        uiState.focusedPosterBackdropExpandDelaySeconds,
        uiState.focusedPosterBackdropTrailerEnabled,
        uiState.focusedPosterBackdropTrailerMuted,
        uiState.focusedPosterBackdropTrailerPlaybackTarget,
        uiState.homeTrailerAutoplayEnabled,
        uiState.homeTrailerAutoplayDelaySeconds,
        uiState.posterCardWidthDp,
        uiState.posterCardHeightDp,
        uiState.posterCardCornerRadiusDp,
        uiState.posterLabelsEnabled,
        viewModel.trailerPreviewUrls,
        viewModel.trailerPreviewAudioUrls,
        viewModel.trailerPreviewUserAgents,
        viewModel.trailerPreviewExternalUrls,
        viewModel.trailerPreviewLoadingItemIds,
        viewModel.trailerPreviewNegativeCacheIds,
        viewModel.trailerMetadataAvailableKeys
    ) {
        ModernHomeContentState(
            catalogRows = uiState.catalogRows,
            continueWatchingItems = uiState.continueWatchingItems,
            modernHomePresentation = uiState.modernHomePresentation,
            deterministicAutoplayEnabled = uiState.deterministicAutoplayEnabled,
            modernLandscapePostersEnabled = uiState.modernLandscapePostersEnabled,
            catalogTypeSuffixEnabled = uiState.catalogTypeSuffixEnabled,
            focusedPosterBackdropExpandEnabled = uiState.focusedPosterBackdropExpandEnabled,
            focusedPosterBackdropExpandDelaySeconds = uiState.focusedPosterBackdropExpandDelaySeconds,
            focusedPosterBackdropTrailerEnabled = uiState.focusedPosterBackdropTrailerEnabled,
            focusedPosterBackdropTrailerMuted = uiState.focusedPosterBackdropTrailerMuted,
            focusedPosterBackdropTrailerPlaybackTarget = uiState.focusedPosterBackdropTrailerPlaybackTarget,
            homeTrailerAutoplayEnabled = uiState.homeTrailerAutoplayEnabled,
            homeTrailerAutoplayDelaySeconds = uiState.homeTrailerAutoplayDelaySeconds,
            posterCardWidthDp = uiState.posterCardWidthDp,
            posterCardHeightDp = uiState.posterCardHeightDp,
            posterCardCornerRadiusDp = uiState.posterCardCornerRadiusDp,
            posterLabelsEnabled = uiState.posterLabelsEnabled,
            trailerPreviewUrls = viewModel.trailerPreviewUrls,
            trailerPreviewAudioUrls = viewModel.trailerPreviewAudioUrls,
            trailerPreviewUserAgents = viewModel.trailerPreviewUserAgents,
            trailerPreviewExternalUrls = viewModel.trailerPreviewExternalUrls,
            trailerPreviewLoadingIds = viewModel.trailerPreviewLoadingItemIds,
            trailerPreviewNegativeCacheIds = viewModel.trailerPreviewNegativeCacheIds,
            trailerMetadataAvailableKeys = viewModel.trailerMetadataAvailableKeys
        )
    }
    val preloadAdjacentItem = remember(viewModel) {
        { item: MetaPreview -> viewModel.preloadAdjacentItem(item) }
    }
    val loadMoreCatalog = remember(viewModel) {
        { catalogId: String, addonId: String, type: String ->
            viewModel.onEvent(HomeEvent.OnLoadMoreCatalog(catalogId, addonId, type))
        }
    }
    val requestTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String, fallbackYtId: String? ->
            viewModel.requestTrailerPreview(itemId, title, releaseInfo, apiType, fallbackYtId)
        }
    }
    val retryTrailerPreview = remember(viewModel) {
        { itemId: String, title: String, releaseInfo: String?, apiType: String, fallbackYtId: String? ->
            viewModel.retryTrailerPreview(itemId, title, releaseInfo, apiType, fallbackYtId)
        }
    }
    val removeContinueWatching = remember(viewModel) {
        { contentId: String, season: Int?, episode: Int?, isNextUp: Boolean ->
            viewModel.onEvent(HomeEvent.OnRemoveContinueWatching(contentId, season, episode, isNextUp))
        }
    }
    val markContinueWatchingWatched = remember(viewModel) {
        { item: ContinueWatchingItem ->
            viewModel.markContinueWatchingAsWatched(item)
        }
    }
    val checkInContinueWatching = remember(viewModel) {
        { item: ContinueWatchingItem ->
            viewModel.checkInContinueWatching(item)
        }
    }
    val toggleContinueWatchingLibrary = remember(viewModel) {
        { item: ContinueWatchingItem -> viewModel.toggleContinueWatchingLibrary(item) }
    }
    val saveModernFocusState = remember(viewModel) {
        { vi: Int, vo: Int, ri: Int, ii: Int, m: Map<String, Int> ->
            viewModel.saveFocusState(vi, vo, ri, ii, m)
        }
    }
    ModernHomeContent(
        contentState = modernContentState,
        idleScreensaverVisible = idleScreensaverVisible,
        startupSplashVisible = startupSplashVisible,
        focusState = focusState,
        enrichingItemIdState = enrichingItemIdState,
        onNavigateToDetail = onNavigateToDetail,
        onContinueWatchingClick = onContinueWatchingClick,
        onContinueWatchingStartFromBeginning = onContinueWatchingStartFromBeginning,
        onContinueWatchingManualStreamSelection = onContinueWatchingManualStreamSelection,
        onLoadMoreCatalog = loadMoreCatalog,
        onRemoveContinueWatching = removeContinueWatching,
        onMarkContinueWatchingWatched = markContinueWatchingWatched,
        onCheckInContinueWatching = checkInContinueWatching,
        cwWatchlistMembership = uiState.posterLibraryMembership,
        onToggleContinueWatchingLibrary = toggleContinueWatchingLibrary,
        isCatalogItemWatched = isCatalogItemWatched,
        onCatalogItemLongPress = onCatalogItemLongPress,
        onItemFocus = remember(viewModel) { { item -> viewModel.onItemFocus(item) } },
        onPreloadAdjacentItem = preloadAdjacentItem,
        onRequestTrailerPreview = requestTrailerPreview,
        onRetryTrailerPreview = retryTrailerPreview,
        externalTrailerTakeoverActive = externalTrailerTakeoverActive,
        onModernHomeTrailerPlaybackStarted = onModernHomeTrailerPlaybackStarted,
        onModernHomeTrailerPlaybackActiveChanged = onModernHomeTrailerPlaybackActiveChanged,
        onSaveFocusState = saveModernFocusState
    )
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomePosterOptionsDialog(
    title: String,
    isInLibrary: Boolean,
    isLibraryPending: Boolean,
    showManageLists: Boolean,
    isMovie: Boolean,
    showManualStreamSelection: Boolean,
    isWatched: Boolean,
    isWatchedPending: Boolean,
    showPlayTrailer: Boolean,
    onDismiss: () -> Unit,
    onPlayWithManualStreamSelection: () -> Unit,
    onDetails: () -> Unit,
    onPlayTrailer: () -> Unit,
    onToggleLibrary: () -> Unit,
    onToggleWatched: () -> Unit,
    showHideRecommendation: Boolean = false,
    onHideRecommendation: () -> Unit = {}
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.home_poster_dialog_subtitle)
    ) {
        if (showManualStreamSelection) {
            Button(
                onClick = onPlayWithManualStreamSelection,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocusRequester),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.play_with_manual_stream_selection))
            }
        }

        Button(
            onClick = onDetails,
            modifier = if (showManualStreamSelection) {
                Modifier.fillMaxWidth()
            } else {
                Modifier
                    .fillMaxWidth()
                    .focusRequester(primaryFocusRequester)
            },
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(stringResource(R.string.cw_action_go_to_details))
        }

        if (showPlayTrailer) {
            Button(
                onClick = onPlayTrailer,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.hero_play_trailer))
            }
        }

        Button(
            onClick = onToggleLibrary,
            enabled = !isLibraryPending,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                contentColor = NexioColors.TextPrimary
            )
        ) {
            Text(
                if (showManageLists) {
                    stringResource(R.string.library_manage_lists)
                } else {
                    if (isInLibrary) {
                        stringResource(R.string.hero_remove_from_library)
                    } else {
                        stringResource(R.string.hero_add_to_library)
                    }
                }
            )
        }

        if (isMovie) {
            Button(
                onClick = onToggleWatched,
                enabled = !isWatchedPending,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(
                    if (isWatched) {
                        stringResource(R.string.hero_mark_unwatched)
                    } else {
                        stringResource(R.string.hero_mark_watched)
                    }
                )
            }
        }

        if (showHideRecommendation) {
            Button(
                onClick = onHideRecommendation,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.trakt_hide_recommendation))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun HomeLibraryListPickerDialog(
    title: String,
    tabs: List<LibraryListTab>,
    membership: Map<String, Boolean>,
    isPending: Boolean,
    error: String?,
    onToggle: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit
) {
    val primaryFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        primaryFocusRequester.requestFocus()
    }

    NexioDialog(
        onDismiss = onDismiss,
        title = title,
        subtitle = stringResource(R.string.detail_lists_subtitle),
        width = 500.dp
    ) {
        if (!error.isNullOrBlank()) {
            Text(
                text = error,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFFFB6B6)
            )
        }

        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 300.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(tabs, key = { it.key }) { tab ->
                val selected = membership[tab.key] == true
                val titleText = if (selected) "\u2713 ${tab.title}" else tab.title
                Button(
                    onClick = { onToggle(tab.key) },
                    enabled = !isPending,
                    modifier = if (tab.key == tabs.firstOrNull()?.key) {
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(primaryFocusRequester)
                    } else {
                        Modifier.fillMaxWidth()
                    },
                    colors = ButtonDefaults.colors(
                        containerColor = if (selected) NexioColors.FocusBackground else NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary
                    )
                ) {
                    Text(
                        text = titleText,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }

        HorizontalDivider(color = NexioColors.Border, thickness = 1.dp)

        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Button(
                onClick = onSave,
                enabled = !isPending,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(if (isPending) stringResource(R.string.action_saving) else stringResource(R.string.action_save))
            }
        }
    }
}

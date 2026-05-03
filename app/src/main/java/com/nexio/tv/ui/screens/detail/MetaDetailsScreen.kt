package com.nexio.tv.ui.screens.detail

import android.content.Intent
import android.net.Uri
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.Stable
import androidx.compose.runtime.key
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListPrefetchStrategy
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.zIndex
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import com.nexio.tv.core.metadata.episodeRuntimeOrSeriesAverageMinutes
import com.nexio.tv.core.anime.AnimeStremioId
import com.nexio.tv.core.ui.findLifecycleOwner
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.LibraryListTab
import com.nexio.tv.domain.model.LibrarySourceMode
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.domain.model.MetaCompany
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.MetaReview
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.OrganizationDiscoverType
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.WatchProgress
import com.nexio.tv.core.player.FrameRateUtils
import com.nexio.tv.ui.components.ErrorState
import com.nexio.tv.ui.components.MetaDetailsSkeleton
import com.nexio.tv.ui.components.NexioDialog
import com.nexio.tv.ui.components.TrailerPlayer
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import androidx.compose.ui.window.Dialog
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.graphics.Brush

internal fun resolveEpisodePlaybackRuntimeMinutes(
    episodeRuntimeMinutes: Int?,
    seriesRuntime: String?
): Int? {
    return episodeRuntimeOrSeriesAverageMinutes(
        episodeRuntimeMinutes = episodeRuntimeMinutes,
        seriesRuntime = seriesRuntime
    )
}

private enum class RestoreTarget {
    HERO,
    EPISODE,
    SEASON_ENTRY,
    CAST_MEMBER,
    RELATED,
    COLLECTION
}

private enum class PeopleSectionTab {
    CAST,
    RATINGS,
    RELATED,
    REVIEWS,
    COLLECTION
}

private data class PeopleTabItem(
    val tab: PeopleSectionTab,
    val label: String,
    val focusRequester: FocusRequester
)

private sealed class DetailTrailerTakeoverRequest {
    data object TitleTrailer : DetailTrailerTakeoverRequest()
    data class SeasonTrailer(val season: Int) : DetailTrailerTakeoverRequest()
    data class SeasonRecap(val season: Int) : DetailTrailerTakeoverRequest()
}

private const val FIRST_DETAIL_LOWER_CONTENT_ITEM_INDEX = 1

private fun resolveDetailReturnEpisodeFocusTarget(
    meta: Meta,
    request: DetailReturnEpisodeFocusRequest?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>
): Video? {
    val requestedSeason = request?.season ?: return null
    val requestedEpisode = request.episode ?: return null

    val orderedEpisodes = meta.videos
        .filter { it.season != null && it.episode != null }
        .sortedWith(compareBy({ it.season }, { it.episode }))
    if (orderedEpisodes.isEmpty()) return null

    val matchedIndex = orderedEpisodes.indexOfFirst {
        it.season == requestedSeason && it.episode == requestedEpisode
    }
    if (matchedIndex < 0) return null

    val isCompleted = episodeProgressMap[requestedSeason to requestedEpisode]?.isCompleted() == true ||
        watchedEpisodes.contains(requestedSeason to requestedEpisode)

    return if (isCompleted) {
        orderedEpisodes.getOrNull(matchedIndex + 1) ?: orderedEpisodes[matchedIndex]
    } else {
        orderedEpisodes[matchedIndex]
    }
}

private const val USER_INTERACTION_DISPATCH_DEBOUNCE_MS = 120L

internal fun shouldShowDetailTrailerTakeover(
    isTrailerPlaying: Boolean,
    isTrailerLoading: Boolean,
    isTrailerTakeoverPending: Boolean = false
): Boolean = isTrailerPlaying || isTrailerLoading || isTrailerTakeoverPending

internal fun shouldRenderDetailTrailerPlayer(
    isTrailerPlaying: Boolean,
    trailerUrl: String?
): Boolean = isTrailerPlaying && !trailerUrl.isNullOrBlank()

internal fun shouldDispatchDetailTrailerTakeoverRequest(
    hasPendingRequest: Boolean,
    isTrailerTakeoverPending: Boolean,
    hasClaimedTakeover: Boolean
): Boolean = hasPendingRequest && isTrailerTakeoverPending && hasClaimedTakeover

internal fun shouldShowStandaloneDetailTrailerTakeoverOverlay(
    isTrailerTakeoverPending: Boolean,
    detailBackdropAlreadyVisible: Boolean
): Boolean = isTrailerTakeoverPending && !detailBackdropAlreadyVisible

internal fun shouldShowDetailTrailerButton(
    titleHasPlayableTrailerMedia: Boolean,
    trailerUrl: String?,
    trailerExternalUrl: String?
): Boolean {
    return titleHasPlayableTrailerMedia ||
        !trailerUrl.isNullOrBlank() ||
        !trailerExternalUrl.isNullOrBlank()
}

internal fun shouldShowDetailScrollableContent(
    isTrailerPlaying: Boolean,
    isTrailerLoading: Boolean,
    isTrailerTakeoverPending: Boolean
): Boolean {
    return true
}

@Stable
private class TrailerSeekOverlayState {
    var positionMs by mutableLongStateOf(0L)
    var durationMs by mutableLongStateOf(0L)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun MetaDetailsScreen(
    viewModel: MetaDetailsViewModel = hiltViewModel(),
    returnFocusSeason: Int? = null,
    returnFocusEpisode: Int? = null,
    onReturnFocusConsumed: () -> Unit = {},
    onTrailerPlaybackActiveChanged: (Boolean) -> Unit = {},
    onBackPress: () -> Unit,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean, provider: String) -> Unit = { _, _, _, _ -> },
    onNavigateToOrganizationDetail: (
        entityId: Int,
        entityName: String,
        kindName: String,
        discoverTypeName: String
    ) -> Unit = { _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onPlayClick: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        genres: String?,
        year: String?,
        runtime: Int?,
        originalLanguage: String?,
        deterministicAutoplay: Boolean
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _, _, _, _ -> },
    onPlayEpisodeWithManualStreamSelection: (
        videoId: String,
        contentType: String,
        contentId: String,
        title: String,
        poster: String?,
        backdrop: String?,
        logo: String?,
        season: Int?,
        episode: Int?,
        episodeName: String?,
        runtime: Int?,
        originalLanguage: String?
    ) -> Unit = { _, _, _, _, _, _, _, _, _, _, _, _ -> }
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val activity = context as? android.app.Activity
    val navLifecycleOwner = LocalLifecycleOwner.current
    val trailerLifecycleOwner = remember(context, navLifecycleOwner) {
        context.findLifecycleOwner() ?: navLifecycleOwner
    }
    var immediateTrailerTakeoverPending by rememberSaveable { mutableStateOf(false) }
    var pendingTrailerTakeoverRequest by remember { mutableStateOf<DetailTrailerTakeoverRequest?>(null) }
    var immediateTrailerTakeoverClaimed by remember { mutableStateOf(false) }
    var officialAppChooserTargets by remember { mutableStateOf<List<OfficialStreamingAppTarget>>(emptyList()) }
    var pendingOfficialAppTitle by remember { mutableStateOf<String?>(null) }
    var showNoOfficialAppDialog by remember { mutableStateOf(false) }

    LaunchedEffect(activity) {
        if (activity != null) {
            FrameRateUtils.setBlockDisplayModeChangesOutsideMainPlayer(true)
            FrameRateUtils.setMainPlayerDisplayModeSessionActive(false)
        }
    }

    BackHandler {
        if (uiState.isTrailerPlaying) {
            detailFocusDebug("backHandler trailerPlaying=true")
            viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded)
        } else if (immediateTrailerTakeoverPending) {
            immediateTrailerTakeoverPending = false
            immediateTrailerTakeoverClaimed = false
            pendingTrailerTakeoverRequest = null
        } else {
            onBackPress()
        }
    }

    val currentIsTrailerPlaying by rememberUpdatedState(uiState.isTrailerPlaying)
    val currentShowTrailerControls by rememberUpdatedState(uiState.showTrailerControls)
    val currentImmediateTrailerTakeoverPending by rememberUpdatedState(immediateTrailerTakeoverPending)
    var trailerSeekOverlayVisible by remember { mutableStateOf(false) }
    val trailerSeekOverlayState = remember { TrailerSeekOverlayState() }
    var trailerSeekToken by remember { mutableIntStateOf(0) }
    var trailerSeekDeltaMs by remember { mutableLongStateOf(0L) }
    var lastUserInteractionDispatchMs by remember { mutableLongStateOf(0L) }
    val detailTrailerPlaybackActive = remember(
        uiState.isTrailerPlaying,
        uiState.isTrailerLoading,
        immediateTrailerTakeoverPending
    ) {
        shouldShowDetailTrailerTakeover(
            isTrailerPlaying = uiState.isTrailerPlaying,
            isTrailerLoading = uiState.isTrailerLoading,
            isTrailerTakeoverPending = immediateTrailerTakeoverPending
        )
    }
    LaunchedEffect(
        uiState.isTrailerLoading,
        uiState.isTrailerPlaying,
        detailTrailerPlaybackActive,
        uiState.trailerUrl,
        uiState.trailerExternalUrl
    ) {
        detailFocusDebug(
            "detailTrailerState loading=${uiState.isTrailerLoading} playing=${uiState.isTrailerPlaying} takeover=$detailTrailerPlaybackActive trailerUrl=${!uiState.trailerUrl.isNullOrBlank()} trailerExternalUrl=${!uiState.trailerExternalUrl.isNullOrBlank()}"
        )
    }
    val onTrailerProgressChanged = remember(trailerSeekOverlayState) {
        { position: Long, duration: Long ->
            trailerSeekOverlayState.positionMs = position
            trailerSeekOverlayState.durationMs = duration
        }
    }

    LaunchedEffect(uiState.isTrailerPlaying, uiState.showTrailerControls) {
        onTrailerPlaybackActiveChanged(
            shouldTreatDetailTrailerPlaybackAsActiveTime(
                isTrailerPlaying = uiState.isTrailerPlaying,
                showTrailerControls = uiState.showTrailerControls
            )
        )
    }

    LaunchedEffect(uiState.isTrailerLoading, uiState.isTrailerPlaying) {
        if (uiState.isTrailerLoading || uiState.isTrailerPlaying) {
            immediateTrailerTakeoverPending = false
            immediateTrailerTakeoverClaimed = false
            pendingTrailerTakeoverRequest = null
        }
    }

    LaunchedEffect(pendingTrailerTakeoverRequest, immediateTrailerTakeoverPending) {
        if (pendingTrailerTakeoverRequest == null || !immediateTrailerTakeoverPending) {
            immediateTrailerTakeoverClaimed = false
            return@LaunchedEffect
        }
        detailFocusDebug("immediateTrailerTakeover claimStart request=$pendingTrailerTakeoverRequest")
        immediateTrailerTakeoverClaimed = false
        withFrameNanos { }
        withFrameNanos { }
        immediateTrailerTakeoverClaimed = true
        detailFocusDebug("immediateTrailerTakeover claimed request=$pendingTrailerTakeoverRequest")
    }

    LaunchedEffect(
        pendingTrailerTakeoverRequest,
        immediateTrailerTakeoverPending,
        immediateTrailerTakeoverClaimed
    ) {
        val request = pendingTrailerTakeoverRequest ?: return@LaunchedEffect
        if (
            !shouldDispatchDetailTrailerTakeoverRequest(
                hasPendingRequest = true,
                isTrailerTakeoverPending = immediateTrailerTakeoverPending,
                hasClaimedTakeover = immediateTrailerTakeoverClaimed
            )
        ) {
            return@LaunchedEffect
        }
        detailFocusDebug("immediateTrailerTakeover dispatch request=$request")
        when (request) {
            DetailTrailerTakeoverRequest.TitleTrailer -> {
                viewModel.onEvent(MetaDetailsEvent.OnTrailerButtonClick)
            }
            is DetailTrailerTakeoverRequest.SeasonTrailer -> {
                viewModel.onEvent(MetaDetailsEvent.OnSeasonShortPress(request.season))
            }
            is DetailTrailerTakeoverRequest.SeasonRecap -> {
                viewModel.onEvent(MetaDetailsEvent.OnPlaySeasonRecap(request.season))
            }
        }
        immediateTrailerTakeoverClaimed = false
        pendingTrailerTakeoverRequest = null
    }

    androidx.compose.runtime.DisposableEffect(Unit) {
        onDispose {
            onTrailerPlaybackActiveChanged(false)
        }
    }

    LaunchedEffect(uiState.userMessage) {
        if (uiState.userMessage != null) {
            delay(2500)
            viewModel.onEvent(MetaDetailsEvent.OnClearMessage)
        }
    }

    LaunchedEffect(uiState.pendingExternalTrailerUrl) {
        val externalTrailerUrl = uiState.pendingExternalTrailerUrl ?: return@LaunchedEffect
        runCatching {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(externalTrailerUrl)).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        }
        viewModel.onEvent(MetaDetailsEvent.OnExternalTrailerConsumed)
    }

    fun handleUniversalStreamerPlayRequest(contentTitle: String) {
        val installedPackages = context.packageManager
            .getInstalledApplications(0)
            .mapTo(linkedSetOf()) { it.packageName }
        val searchablePackages = resolveSearchableStreamingPackages(context, installedPackages)
        val targets = resolveInstalledOfficialStreamingApps(installedPackages, searchablePackages)
        when {
            targets.isEmpty() -> {
                showNoOfficialAppDialog = true
            }
            targets.size == 1 -> {
                buildOfficialStreamingAppIntent(context, targets.first(), contentTitle)?.let { intent ->
                    context.startActivity(intent)
                }
            }
            else -> {
                pendingOfficialAppTitle = contentTitle
                officialAppChooserTargets = targets
            }
        }
    }

    androidx.compose.runtime.DisposableEffect(trailerLifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_PAUSE) {
                viewModel.onEvent(MetaDetailsEvent.OnLifecyclePause)
            }
        }
        trailerLifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            trailerLifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
            .onPreviewKeyEvent { keyEvent ->
                if (currentIsTrailerPlaying) {
                    if (currentShowTrailerControls) {
                        if (keyEvent.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) {
                            return@onPreviewKeyEvent false
                        }
                        when (keyEvent.nativeKeyEvent.keyCode) {
                            KeyEvent.KEYCODE_DPAD_CENTER,
                            KeyEvent.KEYCODE_ENTER,
                            KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_UP -> {
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_DOWN -> {
                                trailerSeekOverlayVisible = false
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_LEFT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> -12_000L
                                    repeatCount >= 6 -> -8_000L
                                    repeatCount >= 2 -> -5_000L
                                    else -> -3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                val repeatCount = keyEvent.nativeKeyEvent.repeatCount
                                val delta = when {
                                    repeatCount >= 12 -> 12_000L
                                    repeatCount >= 6 -> 8_000L
                                    repeatCount >= 2 -> 5_000L
                                    else -> 3_000L
                                }
                                trailerSeekDeltaMs = delta
                                trailerSeekToken += 1
                                trailerSeekOverlayVisible = true
                                true
                            }
                            else -> false
                        }
                    }
                    // During auto trailer preview, consume all keys except back/ESC so content doesn't scroll.
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (currentImmediateTrailerTakeoverPending) {
                    val keyCode = keyEvent.nativeKeyEvent.keyCode
                    return@onPreviewKeyEvent keyCode != KeyEvent.KEYCODE_BACK &&
                            keyCode != KeyEvent.KEYCODE_ESCAPE
                }
                if (keyEvent.nativeKeyEvent.action == KeyEvent.ACTION_DOWN) {
                    val nativeEvent = keyEvent.nativeKeyEvent
                    val shouldDispatch =
                        nativeEvent.repeatCount == 0 &&
                            (nativeEvent.eventTime - lastUserInteractionDispatchMs) >=
                            USER_INTERACTION_DISPATCH_DEBOUNCE_MS
                    if (shouldDispatch) {
                        lastUserInteractionDispatchMs = nativeEvent.eventTime
                        viewModel.onEvent(MetaDetailsEvent.OnUserInteraction)
                    }
                }
                false
            }
    ) {
        when {
            uiState.isLoading -> {
                MetaDetailsSkeleton()
            }
            uiState.error != null -> {
                ErrorState(
                    message = uiState.error ?: stringResource(R.string.error_generic),
                    onRetry = { viewModel.onEvent(MetaDetailsEvent.OnRetry) }
                )
            }
            uiState.meta != null -> {
                val meta = uiState.meta!!
                var consumedInitialReturnFocus by rememberSaveable(meta.id) { mutableStateOf(false) }
                val detailReturnFocusResolution = remember(
                    meta.id,
                    returnFocusSeason,
                    returnFocusEpisode,
                    consumedInitialReturnFocus
                ) {
                    resolveDetailReturnFocusRequest(
                        returnFocusSeason = returnFocusSeason,
                        returnFocusEpisode = returnFocusEpisode,
                        alreadyConsumed = consumedInitialReturnFocus
                    )
                }
                val genresString = remember(meta.genres) {
                    meta.genres.takeIf { it.isNotEmpty() }?.joinToString(" • ")
                }
                val yearString = remember(meta.releaseInfo) {
                    meta.releaseInfo?.split("-")?.firstOrNull() ?: meta.releaseInfo
                }

                LaunchedEffect(meta.id, detailReturnFocusResolution.shouldConsumeSavedState) {
                    if (detailReturnFocusResolution.shouldConsumeSavedState) {
                        detailFocusDebug(
                            "consumeReturnFocusSavedState meta=${meta.id} season=$returnFocusSeason episode=$returnFocusEpisode"
                        )
                        consumedInitialReturnFocus = true
                        onReturnFocusConsumed()
                    }
                }

                key(meta.id) {
                    fun requestImmediateTrailerTakeover(request: DetailTrailerTakeoverRequest) {
                        detailFocusDebug("requestImmediateTrailerTakeover meta=${meta.id} request=$request")
                        pendingTrailerTakeoverRequest = request
                        immediateTrailerTakeoverPending = true
                    }

                    MetaDetailsContent(
                        meta = meta,
                        detailReturnEpisodeFocusRequest = detailReturnFocusResolution.request,
                        seasons = uiState.seasons,
                        selectedSeason = uiState.selectedSeason,
                        manualSeasonOverrideActive = uiState.manualSeasonOverrideActive,
                        episodesForSeason = uiState.episodesForSeason,
                        isInLibrary = uiState.isInLibrary,
                        librarySourceMode = uiState.librarySourceMode,
                        nextToWatch = uiState.nextToWatch,
                        episodeProgressMap = uiState.episodeProgressMap,
                        watchedEpisodes = uiState.watchedEpisodes,
                        episodeWatchedPendingKeys = uiState.episodeWatchedPendingKeys,
                        blurUnwatchedEpisodes = uiState.blurUnwatchedEpisodes,
                        isAnimeDetail = uiState.isAnimeDetail,
                        relatedItems = uiState.relatedItems,
                        reviews = uiState.reviews,
                        isReviewsLoading = uiState.isReviewsLoading,
                        reviewsError = uiState.reviewsError,
                        collection = uiState.collection,
                        collectionName = uiState.collectionName,
                        episodeRatings = uiState.episodeRatings,
                        isEpisodeRatingsLoading = uiState.isEpisodeRatingsLoading,
                        episodeRatingsError = uiState.episodeRatingsError,
                        mdbListRatings = uiState.mdbListRatings,
                        showMdbListImdb = uiState.showMdbListImdb,
                        deterministicAutoplayEnabled = uiState.deterministicAutoplayEnabled,
                        onSeasonSelected = { viewModel.onEvent(MetaDetailsEvent.OnSeasonSelected(it)) },
                        onSeasonShortPress = {
                            requestImmediateTrailerTakeover(DetailTrailerTakeoverRequest.SeasonTrailer(it))
                        },
                        onSeasonOptionsOpened = { viewModel.onEvent(MetaDetailsEvent.OnSeasonOptionsOpened(it)) },
                        onProgrammaticSeasonSelected = { viewModel.setSelectedSeasonProgrammatically(it) },
                        onEpisodeClick = { video ->
                            if (uiState.universalStreamerModeEnabled) {
                                handleUniversalStreamerPlayRequest(meta.name)
                            } else {
                                val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
                                    episodeRuntimeMinutes = video.runtime,
                                    seriesRuntime = meta.runtime
                                )
                                onPlayClick(
                                    video.id,
                                    meta.apiType,
                                    meta.id,
                                    meta.name,
                                    video.thumbnail ?: meta.poster,
                                    meta.background,
                                    meta.logo,
                                    video.season,
                                    video.episode,
                                    video.title,
                                    null,
                                    null,
                                    playbackRuntime,
                                    meta.language,
                                    uiState.deterministicAutoplayEnabled
                                )
                            }
                        },
                        onPlayClick = { videoId ->
                            if (uiState.universalStreamerModeEnabled) {
                                handleUniversalStreamerPlayRequest(meta.name)
                            } else {
                                onPlayClick(
                                    videoId,
                                    meta.apiType,
                                    meta.id,
                                    meta.name,
                                    meta.poster,
                                    meta.background,
                                    meta.logo,
                                    null,
                                    null,
                                    null,
                                    genresString,
                                    yearString,
                                    null,
                                    meta.language,
                                    uiState.deterministicAutoplayEnabled
                                )
                            }
                        },
                        onPlayEpisodeWithManualStreamSelection = { video ->
                            if (uiState.universalStreamerModeEnabled) {
                                handleUniversalStreamerPlayRequest(meta.name)
                            } else {
                                val playbackRuntime = resolveEpisodePlaybackRuntimeMinutes(
                                    episodeRuntimeMinutes = video.runtime,
                                    seriesRuntime = meta.runtime
                                )
                                onPlayEpisodeWithManualStreamSelection(
                                    video.id,
                                    meta.apiType,
                                    meta.id,
                                    meta.name,
                                    video.thumbnail ?: meta.poster,
                                    meta.background,
                                    meta.logo,
                                    video.season,
                                    video.episode,
                                    video.title,
                                    playbackRuntime,
                                    meta.language
                                )
                            }
                        },
                        onPlayButtonFocused = { viewModel.onEvent(MetaDetailsEvent.OnPlayButtonFocused) },
                        onToggleLibrary = { viewModel.onEvent(MetaDetailsEvent.OnToggleLibrary) },
                        onLibraryLongPress = { viewModel.onEvent(MetaDetailsEvent.OnLibraryLongPress) },
                        onToggleEpisodeWatched = { video ->
                            viewModel.onEvent(MetaDetailsEvent.OnToggleEpisodeWatched(video))
                        },
                        onClearEpisodeProgress = { video ->
                            viewModel.onEvent(MetaDetailsEvent.OnClearEpisodeProgress(video))
                        },
                        onCheckInEpisode = { video ->
                            viewModel.onEvent(MetaDetailsEvent.OnCheckInEpisode(video))
                        },
                        onMarkSeasonWatched = { season ->
                            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonWatched(season))
                        },
                        onMarkSeasonUnwatched = { season ->
                            viewModel.onEvent(MetaDetailsEvent.OnMarkSeasonUnwatched(season))
                        },
                        onPlaySeasonRecap = { season ->
                            requestImmediateTrailerTakeover(DetailTrailerTakeoverRequest.SeasonRecap(season))
                        },
                        onMarkPreviousEpisodesWatched = { video ->
                            viewModel.onEvent(MetaDetailsEvent.OnMarkPreviousEpisodesWatched(video))
                        },
                        isSeasonFullyWatched = { season ->
                            viewModel.isSeasonFullyWatched(season)
                        },
                        seasonMediaAvailabilityBySeason = uiState.seasonMediaAvailabilityBySeason,
                        titleHasPlayableTrailerMedia = uiState.titleHasPlayableTrailerMedia,
                        trailerUrl = uiState.trailerUrl,
                        trailerAudioUrl = uiState.trailerAudioUrl,
                        trailerExternalUrl = uiState.trailerExternalUrl,
                        isTrailerPlaying = uiState.isTrailerPlaying,
                        isTrailerLoading = uiState.isTrailerLoading,
                        showTrailerControls = uiState.showTrailerControls,
                        hideLogoDuringTrailer = uiState.hideLogoDuringTrailer,
                        trailerButtonEnabled = uiState.trailerButtonEnabled,
                        trailerSeekToken = trailerSeekToken,
                        trailerSeekDeltaMs = trailerSeekDeltaMs,
                        onTrailerControlKey = { keyCode, action, repeatCount ->
                            if (!uiState.showTrailerControls || !uiState.isTrailerPlaying) {
                                false
                            } else if (action != KeyEvent.ACTION_DOWN) {
                                false
                            } else {
                                val seekStepMs = when {
                                    repeatCount >= 12 -> 12_000L
                                    repeatCount >= 6 -> 8_000L
                                    repeatCount >= 2 -> 5_000L
                                    else -> 3_000L
                                }
                                when (keyCode) {
                                    KeyEvent.KEYCODE_DPAD_CENTER,
                                    KeyEvent.KEYCODE_ENTER,
                                    KeyEvent.KEYCODE_NUMPAD_ENTER,
                                    KeyEvent.KEYCODE_DPAD_UP -> {
                                        trailerSeekOverlayVisible = true
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_DOWN -> {
                                        trailerSeekOverlayVisible = false
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_LEFT -> {
                                        trailerSeekDeltaMs = -seekStepMs
                                        trailerSeekToken += 1
                                        trailerSeekOverlayVisible = true
                                        true
                                    }
                                    KeyEvent.KEYCODE_DPAD_RIGHT -> {
                                        trailerSeekDeltaMs = seekStepMs
                                        trailerSeekToken += 1
                                        trailerSeekOverlayVisible = true
                                        true
                                    }
                                    else -> false
                                }
                            }
                        },
                        onTrailerProgressChanged = onTrailerProgressChanged,
                        onTrailerEnded = { viewModel.onEvent(MetaDetailsEvent.OnTrailerEnded) },
                        onTrailerButtonClick = {
                            requestImmediateTrailerTakeover(DetailTrailerTakeoverRequest.TitleTrailer)
                        },
                        immediateTrailerTakeoverPending = immediateTrailerTakeoverPending,
                        onNavigateToCastDetail = onNavigateToCastDetail,
                        onNavigateToOrganizationDetail = onNavigateToOrganizationDetail,
                        onNavigateToDetail = onNavigateToDetail,
                        onReviewFocused = { index ->
                            viewModel.onEvent(MetaDetailsEvent.OnReviewItemFocused(index))
                        }
                    )
                }
            }
        }

        if (uiState.showListPicker) {
            LibraryListPickerDialog(
                title = uiState.meta?.name ?: stringResource(R.string.detail_lists_fallback),
                tabs = uiState.libraryListTabs,
                membership = uiState.pickerMembership,
                isPending = uiState.pickerPending,
                error = uiState.pickerError,
                onToggle = { key ->
                    viewModel.onEvent(MetaDetailsEvent.OnPickerMembershipToggled(key))
                },
                onSave = { viewModel.onEvent(MetaDetailsEvent.OnPickerSave) },
                onDismiss = { viewModel.onEvent(MetaDetailsEvent.OnPickerDismiss) }
            )
        }

        if (officialAppChooserTargets.isNotEmpty()) {
            OfficialAppChooserDialog(
                title = pendingOfficialAppTitle ?: uiState.meta?.name ?: uiState.meta?.id.orEmpty(),
                targets = officialAppChooserTargets,
                onOpen = { target ->
                    val contentTitle = pendingOfficialAppTitle ?: uiState.meta?.name.orEmpty()
                    buildOfficialStreamingAppIntent(context, target, contentTitle)?.let { intent ->
                        context.startActivity(intent)
                    }
                    officialAppChooserTargets = emptyList()
                    pendingOfficialAppTitle = null
                },
                onDismiss = {
                    officialAppChooserTargets = emptyList()
                    pendingOfficialAppTitle = null
                }
            )
        }

        if (showNoOfficialAppDialog) {
            NoOfficialAppDialog(
                onDismiss = { showNoOfficialAppDialog = false }
            )
        }

        val message = uiState.userMessage
        if (!message.isNullOrBlank()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 24.dp)
                    .background(
                        color = if (uiState.userMessageIsError) {
                            Color(0xFF5A1C1C)
                        } else {
                            NexioColors.BackgroundElevated
                        },
                        shape = RoundedCornerShape(10.dp)
                    )
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextPrimary
                )
            }
        }

        TrailerSeekOverlayHost(
            visible = uiState.isTrailerPlaying && uiState.showTrailerControls && trailerSeekOverlayVisible,
            overlayState = trailerSeekOverlayState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )

        val immediateTakeoverMeta = uiState.meta
        if (
            immediateTakeoverMeta != null &&
            shouldShowStandaloneDetailTrailerTakeoverOverlay(
                isTrailerTakeoverPending = immediateTrailerTakeoverPending,
                detailBackdropAlreadyVisible = true
            )
        ) {
            ImmediateDetailTrailerTakeoverOverlay(
                backdropUrl = immediateTakeoverMeta.background ?: immediateTakeoverMeta.poster
            )
        }
    }

    LaunchedEffect(trailerSeekOverlayVisible, uiState.isTrailerPlaying, uiState.showTrailerControls, trailerSeekToken) {
        if (trailerSeekOverlayVisible && uiState.isTrailerPlaying && uiState.showTrailerControls) {
            delay(3000)
            trailerSeekOverlayVisible = false
        }
    }

    LaunchedEffect(uiState.isTrailerPlaying, uiState.showTrailerControls) {
        if (!uiState.isTrailerPlaying || !uiState.showTrailerControls) {
            trailerSeekOverlayVisible = false
        }
    }
}

@Composable
private fun ImmediateDetailTrailerTakeoverOverlay(
    backdropUrl: String?
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(100f)
            .background(NexioColors.Background)
    ) {
        if (!backdropUrl.isNullOrBlank()) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(backdropUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            NexioColors.Background.copy(alpha = 0.16f),
                            NexioColors.Background.copy(alpha = 0.42f),
                            NexioColors.Background.copy(alpha = 0.86f)
                        )
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.horizontalGradient(
                        colors = listOf(
                            NexioColors.Background.copy(alpha = 0.72f),
                            NexioColors.Background.copy(alpha = 0.36f),
                            Color.Transparent
                        )
                    )
                )
        )
    }
}

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class, ExperimentalFoundationApi::class)
@Composable
private fun MetaDetailsContent(
    meta: Meta,
    detailReturnEpisodeFocusRequest: DetailReturnEpisodeFocusRequest? = null,
    seasons: List<Int>,
    selectedSeason: Int,
    manualSeasonOverrideActive: Boolean,
    episodesForSeason: List<Video>,
    isInLibrary: Boolean,
    librarySourceMode: LibrarySourceMode,
    nextToWatch: NextToWatch?,
    episodeProgressMap: Map<Pair<Int, Int>, WatchProgress>,
    watchedEpisodes: Set<Pair<Int, Int>>,
    episodeWatchedPendingKeys: Set<String>,
    blurUnwatchedEpisodes: Boolean,
    isAnimeDetail: Boolean,
    relatedItems: List<MetaPreview>,
    reviews: List<MetaReview>,
    isReviewsLoading: Boolean,
    reviewsError: String?,
    collection: List<MetaPreview>,
    collectionName: String?,
    episodeRatings: Map<Pair<Int, Int>, EpisodeRating>,
    isEpisodeRatingsLoading: Boolean,
    episodeRatingsError: String?,
    mdbListRatings: MDBListRatings?,
    showMdbListImdb: Boolean,
    deterministicAutoplayEnabled: Boolean,
    onSeasonSelected: (Int) -> Unit,
    onSeasonShortPress: (Int) -> Unit,
    onSeasonOptionsOpened: (Int) -> Unit,
    onProgrammaticSeasonSelected: (Int) -> Unit,
    onEpisodeClick: (Video) -> Unit,
    onPlayClick: (String) -> Unit,
    onPlayEpisodeWithManualStreamSelection: (Video) -> Unit,
    onPlayButtonFocused: () -> Unit,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    onToggleEpisodeWatched: (Video) -> Unit,
    onClearEpisodeProgress: (Video) -> Unit,
    onCheckInEpisode: (Video) -> Unit,
    onMarkSeasonWatched: (Int) -> Unit,
    onMarkSeasonUnwatched: (Int) -> Unit,
    onPlaySeasonRecap: (Int) -> Unit,
    onMarkPreviousEpisodesWatched: (Video) -> Unit,
    isSeasonFullyWatched: (Int) -> Boolean,
    seasonMediaAvailabilityBySeason: Map<Int, SeasonMediaActionAvailability>,
    titleHasPlayableTrailerMedia: Boolean,
    trailerUrl: String?,
    trailerAudioUrl: String?,
    trailerExternalUrl: String?,
    isTrailerPlaying: Boolean,
    isTrailerLoading: Boolean,
    showTrailerControls: Boolean,
    hideLogoDuringTrailer: Boolean,
    trailerButtonEnabled: Boolean,
    trailerSeekToken: Int,
    trailerSeekDeltaMs: Long,
    onTrailerControlKey: (keyCode: Int, action: Int, repeatCount: Int) -> Boolean,
    onTrailerProgressChanged: (Long, Long) -> Unit,
    onTrailerEnded: () -> Unit,
    onTrailerButtonClick: () -> Unit,
    immediateTrailerTakeoverPending: Boolean,
    onNavigateToCastDetail: (personId: Int, personName: String, preferCrew: Boolean, provider: String) -> Unit = { _, _, _, _ -> },
    onNavigateToOrganizationDetail: (Int, String, String, String) -> Unit = { _, _, _, _ -> },
    onNavigateToDetail: (itemId: String, itemType: String, addonBaseUrl: String?) -> Unit = { _, _, _ -> },
    onReviewFocused: (Int) -> Unit = {}
) {
    val showTrailerTakeover = remember(isTrailerPlaying, isTrailerLoading, immediateTrailerTakeoverPending) {
        shouldShowDetailTrailerTakeover(
            isTrailerPlaying = isTrailerPlaying,
            isTrailerLoading = isTrailerLoading,
            isTrailerTakeoverPending = immediateTrailerTakeoverPending
        )
    }
    val isSeries = remember(meta) {
        isSeriesDetailMeta(meta)
    }
    val nextEpisode = remember(episodesForSeason) { episodesForSeason.firstOrNull() }
    val heroVideo = remember(meta.videos, nextToWatch, nextEpisode, isSeries) {
        if (!isSeries) return@remember null
        val byId = nextToWatch?.nextVideoId?.let { id ->
            meta.videos.firstOrNull { it.id == id }
        }
        val bySeasonEpisode = if (byId == null && nextToWatch?.nextSeason != null && nextToWatch.nextEpisode != null) {
            meta.videos.firstOrNull { it.season == nextToWatch.nextSeason && it.episode == nextToWatch.nextEpisode }
        } else {
            null
        }
        byId ?: bySeasonEpisode ?: nextEpisode
    }
    val nestedPrefetchStrategy = remember { LazyListPrefetchStrategy(nestedPrefetchItemCount = 2) }
    val listState = rememberResettableLazyListState(
        resetKey = meta.id,
        prefetchStrategy = nestedPrefetchStrategy
    )
    val selectedSeasonFocusRequester = remember { FocusRequester() }
    val heroPlayFocusRequester = remember { FocusRequester() }
    val castTabFocusRequester = remember { FocusRequester() }
    val moreLikeTabFocusRequester = remember { FocusRequester() }
    val reviewsTabFocusRequester = remember { FocusRequester() }
    val collectionTabFocusRequester = remember { FocusRequester() }
    val ratingsTabFocusRequester = remember { FocusRequester() }
    val ratingsContentFocusRequester = remember { FocusRequester() }
    var pendingRestoreType by rememberSaveable(meta.id) { mutableStateOf<RestoreTarget?>(null) }
    var pendingRestoreEpisodeId by rememberSaveable(meta.id) { mutableStateOf<String?>(null) }
    var pendingRestoreCastPersonId by rememberSaveable(meta.id) { mutableStateOf<Int?>(null) }
    var pendingRestoreMoreLikeItemId by rememberSaveable(meta.id) { mutableStateOf<String?>(null) }
    var restoreFocusToken by rememberSaveable(meta.id) { mutableIntStateOf(0) }
    var initialHeroFocusRequested by rememberSaveable(meta.id) { mutableStateOf(false) }
    // Guard state for post-landing focus-thief protection on the Play button.
    var playButtonIsFocused by remember { mutableStateOf(false) }
    var playButtonEverFocused by rememberSaveable(meta.id) { mutableStateOf(false) }
    var userHasNavigatedFromPlay by rememberSaveable(meta.id) { mutableStateOf(false) }
    var initialDetailReturnFocusHandled by remember(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode
    ) {
        mutableStateOf(false)
    }
    val screenContext = LocalContext.current
    val navLifecycleOwner = LocalLifecycleOwner.current
    val lifecycleOwner = remember(screenContext, navLifecycleOwner) {
        screenContext.findLifecycleOwner() ?: navLifecycleOwner
    }
    val coroutineScope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current

    fun logFocusState(message: String) {
        detailFocusDebug(
            "meta=${meta.id} $message pendingRestoreType=$pendingRestoreType pendingEpisodeId=$pendingRestoreEpisodeId scrollIndex=${listState.firstVisibleItemIndex} scrollOffset=${listState.firstVisibleItemScrollOffset} initialHeroFocusRequested=$initialHeroFocusRequested trailerPlaying=$isTrailerPlaying trailerLoading=$isTrailerLoading"
        )
    }

    fun clearPendingRestore() {
        logFocusState("clearPendingRestore")
        pendingRestoreType = null
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
    }

    fun markHeroRestore() {
        pendingRestoreType = RestoreTarget.HERO
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        logFocusState("markHeroRestore")
    }

    fun markEpisodeRestore(episodeId: String) {
        pendingRestoreType = RestoreTarget.EPISODE
        pendingRestoreEpisodeId = episodeId
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        logFocusState("markEpisodeRestore episodeId=$episodeId")
    }

    fun markSeasonEntryRestore(episodeId: String) {
        pendingRestoreType = RestoreTarget.SEASON_ENTRY
        pendingRestoreEpisodeId = episodeId
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        logFocusState("markSeasonEntryRestore episodeId=$episodeId")
    }

    fun requestSeasonEntryRestore(episodeId: String) {
        markSeasonEntryRestore(episodeId)
        coroutineScope.launch {
            logFocusState("requestSeasonEntryRestore episodeId=$episodeId")
            if (seasons.isNotEmpty()) {
                // Keep the episodes row composed before the target item restores focus.
                listState.scrollToItem(2)
                delay(32)
            }
            restoreFocusToken += 1
        }
    }

    fun markCastMemberRestore(personId: Int) {
        pendingRestoreType = RestoreTarget.CAST_MEMBER
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = personId
        pendingRestoreMoreLikeItemId = null
        logFocusState("markCastMemberRestore personId=$personId")
    }

    fun markMoreLikeThisRestore(itemId: String) {
        pendingRestoreType = RestoreTarget.RELATED
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = itemId
        logFocusState("markMoreLikeThisRestore itemId=$itemId")
    }

    var pendingRestoreCollectionItemId by rememberSaveable(meta.id) { mutableStateOf<String?>(null) }
    fun markCollectionRestore(itemId: String) {
        pendingRestoreType = RestoreTarget.COLLECTION
        pendingRestoreEpisodeId = null
        pendingRestoreCastPersonId = null
        pendingRestoreMoreLikeItemId = null
        pendingRestoreCollectionItemId = itemId
        logFocusState("markCollectionRestore itemId=$itemId")
    }

    androidx.compose.runtime.DisposableEffect(
        lifecycleOwner,
        pendingRestoreType,
        pendingRestoreEpisodeId,
        pendingRestoreCastPersonId,
        pendingRestoreMoreLikeItemId,
        pendingRestoreCollectionItemId
    ) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreType != null) {
                logFocusState("lifecycleResume restoreFocusToken=${restoreFocusToken + 1}")
                restoreFocusToken += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    LaunchedEffect(
        meta.id,
        detailReturnEpisodeFocusRequest?.season,
        detailReturnEpisodeFocusRequest?.episode,
        nextToWatch,
        episodeProgressMap,
        watchedEpisodes
    ) {
        if (initialDetailReturnFocusHandled) return@LaunchedEffect
        if (!isSeries) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        val request = detailReturnEpisodeFocusRequest
        if (request?.season == null || request.episode == null) {
            initialDetailReturnFocusHandled = true
            return@LaunchedEffect
        }
        if (nextToWatch == null) return@LaunchedEffect

        val targetEpisode = resolveDetailReturnEpisodeFocusTarget(
            meta = meta,
            request = request,
            episodeProgressMap = episodeProgressMap,
            watchedEpisodes = watchedEpisodes
        )
        initialDetailReturnFocusHandled = true
        targetEpisode ?: return@LaunchedEffect

        val targetSeason = targetEpisode.season
        if (targetSeason != null && selectedSeason != targetSeason) {
            onProgrammaticSeasonSelected(targetSeason)
        }
        // Prevent the default hero autofocus from stealing focus after the episode restore completes.
        initialHeroFocusRequested = true
        markEpisodeRestore(targetEpisode.id)
        if (seasons.isNotEmpty()) {
            // Ensure the episodes row is composed before requesting focus on a card.
            listState.scrollToItem(2)
            delay(32)
        }
        restoreFocusToken += 1
        logFocusState("detailReturnEpisode restoreFocusToken=$restoreFocusToken targetEpisode=${targetEpisode.id}")
    }

    LaunchedEffect(meta.id, listState) {
        snapshotFlow { listState.firstVisibleItemIndex to listState.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) ->
                detailFocusDebug(
                    "meta=${meta.id} listScroll index=$index offset=$offset pendingRestoreType=$pendingRestoreType initialHeroFocusRequested=$initialHeroFocusRequested trailerPlaying=$isTrailerPlaying trailerLoading=$isTrailerLoading"
                )
            }
    }

    // Track if scrolled past hero (first item)
    val isScrolledPastHero by remember {
        derivedStateOf {
            listState.firstVisibleItemIndex > 0 ||
            (listState.firstVisibleItemIndex == 0 && listState.firstVisibleItemScrollOffset > 200)
        }
    }

    // Pre-compute cast members to avoid recomputation in lazy scope
    val castMembersToShow = remember(meta.castMembers, meta.cast) {
        val safeCastMembers = (meta.castMembers as List<*>).mapNotNull { raw ->
            when (raw) {
                is MetaCastMember -> raw
                is Map<*, *> -> {
                    val name = (raw["name"] as? String)?.trim().orEmpty()
                    if (name.isBlank()) null else MetaCastMember(
                        name = name,
                        character = (raw["character"] as? String)?.takeIf { it.isNotBlank() },
                        photo = (raw["photo"] as? String)?.takeIf { it.isNotBlank() },
                        tmdbId = when (val tmdbId = raw["tmdbId"]) {
                            is Number -> tmdbId.toInt()
                            is String -> tmdbId.toIntOrNull()
                            else -> null
                        },
                        provider = (raw["provider"] as? String)?.takeIf { it.isNotBlank() },
                        providerId = (raw["providerId"] as? String)?.takeIf { it.isNotBlank() }
                    )
                }
                else -> null
            }
        }
        if (safeCastMembers.isNotEmpty()) {
            safeCastMembers
        } else {
            (meta.cast as List<*>).mapNotNull { raw ->
                val name = (raw as? String)?.trim().orEmpty()
                if (name.isBlank()) null else MetaCastMember(name = name)
            }
        }
    }

    fun isLeadCreditRole(role: String?): Boolean {
        val r = role?.trim().orEmpty()
        return r.equals("Creator", ignoreCase = true) ||
            r.equals("Director", ignoreCase = true) ||
            r.equals("Writer", ignoreCase = true)
    }

    fun MetaCastMember.leadCreditKey(): String {
        return tmdbId?.let { "tmdb:$it" }
            ?: "${name.trim().lowercase()}|${character?.trim()?.lowercase().orEmpty()}"
    }

    val directorWriterMembers = remember(castMembersToShow, isAnimeDetail) {
        if (isAnimeDetail) return@remember emptyList()
        val creators = castMembersToShow.filter { it.character.equals("Creator", ignoreCase = true) }
        val directors = castMembersToShow.filter { it.character.equals("Director", ignoreCase = true) }
        val writers = castMembersToShow.filter { it.character.equals("Writer", ignoreCase = true) }
        when {
            creators.isNotEmpty() -> creators
            directors.isNotEmpty() -> directors
            else -> writers
        }
    }

    val normalCastMembers = remember(castMembersToShow, directorWriterMembers) {
        val leadingKeys = directorWriterMembers.map { it.leadCreditKey() }.toSet()
        castMembersToShow.filterNot {
            it.leadCreditKey() in leadingKeys && isLeadCreditRole(it.character)
        }
    }
    val isTvShow = remember(meta) {
        isSeriesDetailMeta(meta)
    }
    val hasCastSection = directorWriterMembers.isNotEmpty() || normalCastMembers.isNotEmpty()
    val hasRelatedSection = relatedItems.isNotEmpty()
    val hasReviewsSection = isReviewsLoading || reviews.isNotEmpty() || !reviewsError.isNullOrBlank()
    val hasRatingsSection = isTvShow && !isAnimeDetail
    val strTabCast = if (isAnimeDetail) "Characters" else stringResource(R.string.detail_tab_cast)
    val strTabRatings = stringResource(R.string.detail_tab_ratings)
    val strTabRelated = if (isAnimeDetail) "Related" else stringResource(R.string.detail_tab_more_like_this)
    val strTabReviews = stringResource(R.string.detail_tab_reviews)
    val strTabCollection = stringResource(R.string.tmdb_collections_title)
    val peopleTabItems = remember(
        hasCastSection,
        hasRelatedSection,
        hasReviewsSection,
        hasRatingsSection,
        collection,
        castTabFocusRequester,
        ratingsTabFocusRequester,
        moreLikeTabFocusRequester,
        reviewsTabFocusRequester,
        collectionTabFocusRequester,
        collectionName
    ) {
        buildList {
            if (hasCastSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.CAST,
                        label = strTabCast,
                        focusRequester = castTabFocusRequester
                    )
                )
            }
            if (hasRatingsSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.RATINGS,
                        label = strTabRatings,
                        focusRequester = ratingsTabFocusRequester
                    )
                )
            }
            if (hasRelatedSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.RELATED,
                        label = strTabRelated,
                        focusRequester = moreLikeTabFocusRequester
                    )
                )
            }
            if (hasReviewsSection) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.REVIEWS,
                        label = strTabReviews,
                        focusRequester = reviewsTabFocusRequester
                    )
                )
            }
            if (collection.isNotEmpty()) {
                add(
                    PeopleTabItem(
                        tab = PeopleSectionTab.COLLECTION,
                        label = collectionName ?: strTabCollection,
                        focusRequester = collectionTabFocusRequester
                    )
                )
            }
        }
    }
    val availablePeopleTabs = remember(peopleTabItems) { peopleTabItems.map { it.tab } }
    val hasPeopleSection = availablePeopleTabs.isNotEmpty()
    val hasPeopleTabs = availablePeopleTabs.size > 1
    val initialPeopleTab = when {
        availablePeopleTabs.contains(PeopleSectionTab.CAST) -> PeopleSectionTab.CAST
        availablePeopleTabs.isNotEmpty() -> availablePeopleTabs.first()
        else -> PeopleSectionTab.RATINGS
    }
    var hasVisitedPeopleTabs by rememberSaveable(meta.id) { mutableStateOf(false) }
    var hasVisitedSeasonTabs by rememberSaveable(meta.id) { mutableStateOf(false) }
    var hasVisitedEpisodes by rememberSaveable(meta.id) { mutableStateOf(false) }
    var hasUserMovedDownFromHero by rememberSaveable(meta.id) { mutableStateOf(false) }
    var activePeopleTab by rememberSaveable(meta.id) { mutableStateOf(initialPeopleTab) }
    var seasonOptionsDialogSeason by remember { mutableStateOf<Int?>(null) }
    val lastFocusedEpisodeIdBySeason = remember(meta.id) { mutableStateMapOf<Int, String>() }
    val nextToWatchScrolledSeasons = remember(meta.id) { mutableStateMapOf<Int, Boolean>() }
    val episodeFocusRequestersBySeason = remember(meta.id) { mutableMapOf<Int, MutableMap<String, FocusRequester>>() }
    val seasonEpisodeFocusRequesters = remember(selectedSeason, episodesForSeason) {
        val byEpisodeId = episodeFocusRequestersBySeason.getOrPut(selectedSeason) { mutableMapOf() }
        episodesForSeason.forEach { episode ->
            if (!byEpisodeId.containsKey(episode.id)) {
                byEpisodeId[episode.id] = FocusRequester()
            }
        }
        byEpisodeId.keys.retainAll(episodesForSeason.map { it.id }.toSet())
        byEpisodeId
    }
    val seasonNextToWatchCandidate = remember(nextToWatch) {
        SeriesNextToWatchCandidate(
            watchProgress = nextToWatch?.watchProgress,
            isResume = nextToWatch?.isResume == true,
            nextVideoId = nextToWatch?.nextVideoId,
            nextSeason = nextToWatch?.nextSeason,
            nextEpisode = nextToWatch?.nextEpisode
        )
    }
    val seasonEntryEpisodeId = remember(
        meta,
        selectedSeason,
        manualSeasonOverrideActive,
        nextToWatch,
        lastFocusedEpisodeIdBySeason[selectedSeason],
        nextToWatchScrolledSeasons[selectedSeason]
    ) {
        resolveSeasonEntryEpisodeId(
            meta = meta,
            selectedSeason = selectedSeason,
            nextToWatch = seasonNextToWatchCandidate,
            lastFocusedEpisodeIdBySeason = lastFocusedEpisodeIdBySeason,
            manualSeasonOverride = manualSeasonOverrideActive,
            preferStoredEpisodeFocus = nextToWatchScrolledSeasons[selectedSeason] == true
        )
    }
    val seasonDownFocusRequester = remember(selectedSeason, episodesForSeason, seasonEpisodeFocusRequesters, seasonEntryEpisodeId) {
        seasonEntryEpisodeId?.let { seasonEpisodeFocusRequesters[it] }
            ?: episodesForSeason.firstOrNull()?.id?.let { seasonEpisodeFocusRequesters[it] }
    }

    val activePeopleTabFocusRequester = peopleTabItems
        .firstOrNull { it.tab == activePeopleTab }
        ?.focusRequester
        ?: if (activePeopleTab == PeopleSectionTab.RATINGS && !hasPeopleTabs) {
            ratingsContentFocusRequester
        } else {
            castTabFocusRequester
        }
    val episodesDownFocusRequester = when {
        hasPeopleTabs -> activePeopleTabFocusRequester
        activePeopleTab == PeopleSectionTab.RATINGS -> ratingsContentFocusRequester
        else -> null
    }
    val peopleSectionUpFocusRequester = when (
        resolvePeopleSectionUpTarget(
            isSeries = isSeries,
            hasEpisodeEntry = seasonDownFocusRequester != null
        )
    ) {
        SectionUpTarget.EPISODE_ENTRY -> seasonDownFocusRequester ?: heroPlayFocusRequester
        SectionUpTarget.HERO_PLAY -> heroPlayFocusRequester
    }
    val hasActiveLowerContentRestoreTarget = pendingRestoreType == RestoreTarget.SEASON_ENTRY ||
        pendingRestoreType == RestoreTarget.EPISODE ||
        pendingRestoreType == RestoreTarget.CAST_MEMBER ||
        pendingRestoreType == RestoreTarget.RELATED ||
        pendingRestoreType == RestoreTarget.COLLECTION
    val lowerContentFocusEnabled = shouldEnableLowerContentFocus(
        hasUserMovedDownFromHero = hasUserMovedDownFromHero,
        hasVisitedLowerContent = hasVisitedDetailLowerContent(
            hasVisitedSeasonTabs = hasVisitedSeasonTabs,
            hasVisitedEpisodes = hasVisitedEpisodes,
            hasVisitedPeopleTabs = hasVisitedPeopleTabs
        ),
        hasActiveRestoreTarget = hasActiveLowerContentRestoreTarget
    )

    LaunchedEffect(availablePeopleTabs) {
        if (availablePeopleTabs.isNotEmpty() && activePeopleTab !in availablePeopleTabs) {
            activePeopleTab = availablePeopleTabs.first()
        }
    }

    // Backdrop alpha for crossfade
    var trailerFirstFrameRendered by remember(trailerUrl) { mutableStateOf(false) }
    val backdropAlpha by animateFloatAsState(
        targetValue = if (showTrailerTakeover && trailerFirstFrameRendered) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "backdropFade"
    )

    val backgroundColor = NexioColors.Background

    val dimColor = remember(backgroundColor) { backgroundColor.copy(alpha = 0.08f) }

    // Stable hero play callback
    val heroPlayClick = remember(heroVideo, meta.id, onEpisodeClick, onPlayClick) {
        {
            markHeroRestore()
            if (heroVideo != null) {
                onEpisodeClick(heroVideo)
            } else {
                onPlayClick(meta.id)
            }
        }
    }
    val heroDownFocusRequester = when (
        resolveHeroDownTarget(
            isSeries = isSeries,
            hasSeasonEntry = seasonDownFocusRequester != null || seasons.isNotEmpty(),
            hasPeopleSection = hasPeopleSection
        )
    ) {
        HeroDownTarget.SEASONS -> selectedSeasonFocusRequester
        HeroDownTarget.PEOPLE -> activePeopleTabFocusRequester
        HeroDownTarget.NONE -> null
    }
    val heroTrailerClick = remember(
        meta.id,
        titleHasPlayableTrailerMedia,
        trailerUrl,
        trailerExternalUrl,
        onTrailerButtonClick
    ) {
        {
            detailFocusDebug(
                "heroTrailerClick meta=${meta.id} titleAvailable=$titleHasPlayableTrailerMedia trailerUrl=${!trailerUrl.isNullOrBlank()} trailerExternalUrl=${!trailerExternalUrl.isNullOrBlank()}"
            )
            onTrailerButtonClick()
        }
    }
    val heroMoveDownRequest: (() -> Unit)? = remember(heroDownFocusRequester, coroutineScope, listState) {
        val target = heroDownFocusRequester
        if (target == null) {
            null
        } else {
            {
                hasUserMovedDownFromHero = true
                userHasNavigatedFromPlay = true
                coroutineScope.launch {
                    logFocusState("heroMoveDownRequested")
                    moveFocusToLazyItem(
                        listState = listState,
                        targetItemIndex = FIRST_DETAIL_LOWER_CONTENT_ITEM_INDEX,
                        focusRequester = target,
                        reason = "hero_move_down"
                    )
                }
            }
        }
    }

    val episodeClick = remember(onEpisodeClick) {
        { video: Video ->
            markEpisodeRestore(video.id)
            onEpisodeClick(video)
        }
    }

    LaunchedEffect(
        pendingRestoreType,
        pendingRestoreEpisodeId,
        initialHeroFocusRequested,
        isTrailerPlaying,
        isTrailerLoading,
        meta.id
    ) {
        if (
            shouldAutoResetFocusToHero(
                initialHeroFocusRequested = initialHeroFocusRequested,
                hasPendingRestoreTarget = pendingRestoreType != null,
                hasPendingRestoreEpisodeId = pendingRestoreEpisodeId != null,
                isTrailerPlaying = isTrailerPlaying,
                isTrailerLoading = isTrailerLoading
            )
        ) {
            try {
                delay(120)
                if (!initialHeroFocusRequested) {
                    logFocusState("autoResetFocusToHero fallback")
                    heroPlayFocusRequester.requestFocusUntilFocused(
                        isFocused = { playButtonIsFocused },
                        initialFrames = 0,
                        reason = "initial_detail_entry_fallback"
                    )
                }
            } finally {
                logFocusState("autoResetFocusToHero finished")
            }
        }
    }

    // Post-landing guard: if Play had focus and loses it without an explicit user navigation,
    // re-steal focus back. Runs only while the guard policy allows and for a bounded window.
    LaunchedEffect(
        meta.id,
        playButtonEverFocused,
        userHasNavigatedFromPlay,
        pendingRestoreType,
        isTrailerPlaying,
        isTrailerLoading
    ) {
        if (!playButtonEverFocused) return@LaunchedEffect
        // Bounded window (~1.5 s) after initial landing so we don't fight users forever.
        val guardFrames = 90
        repeat(guardFrames) {
            if (
                !shouldReclaimPlayFocus(
                    playEverFocused = playButtonEverFocused,
                    playIsFocused = playButtonIsFocused,
                    userHasNavigatedFromPlay = userHasNavigatedFromPlay,
                    hasPendingRestoreTarget = pendingRestoreType != null,
                    isTrailerPlaying = isTrailerPlaying,
                    isTrailerLoading = isTrailerLoading
                )
            ) {
                if (userHasNavigatedFromPlay) return@LaunchedEffect
                // Skip this frame; conditions may change (e.g., trailer resolving).
                delay(16)
                return@repeat
            }
            logFocusState("reclaimPlayFocus guardActive")
            runCatching { heroPlayFocusRequester.requestFocus() }
            delay(32)
        }
    }

    // Pre-compute screen dimensions to avoid BoxWithConstraints subcomposition overhead
    val configuration = LocalConfiguration.current
    val localContext = LocalContext.current
    val localDensity = LocalDensity.current
    val screenWidthDp = remember(configuration) { configuration.screenWidthDp.dp }
    val screenHeightDp = remember(configuration) { configuration.screenHeightDp.dp }
    val backdropWidthPx = remember(screenWidthDp, localDensity) {
        with(localDensity) { screenWidthDp.roundToPx() }
    }
    val backdropHeightPx = remember(screenHeightDp, localDensity) {
        with(localDensity) { screenHeightDp.roundToPx() }
    }
    val backdropRequest = remember(
        localContext,
        meta.background,
        meta.poster,
        backdropWidthPx,
        backdropHeightPx
    ) {
        ImageRequest.Builder(localContext)
            .data(meta.background ?: meta.poster)
            .crossfade(true)
            .size(width = backdropWidthPx, height = backdropHeightPx)
            .build()
    }
    val leftGradientBitmap = remember(backgroundColor, backdropWidthPx) {
        val w = backdropWidthPx.coerceAtLeast(1)
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(w, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val shader = android.graphics.LinearGradient(
            0f, 0f, w * 0.78f, 0f,
            intArrayOf(
                backgroundColor.copy(alpha = 1f).toArgb(),
                backgroundColor.copy(alpha = 0.95f).toArgb(),
                backgroundColor.copy(alpha = 0.84f).toArgb(),
                backgroundColor.copy(alpha = 0.70f).toArgb(),
                backgroundColor.copy(alpha = 0.52f).toArgb(),
                backgroundColor.copy(alpha = 0.34f).toArgb(),
                backgroundColor.copy(alpha = 0.18f).toArgb(),
                backgroundColor.copy(alpha = 0.07f).toArgb(),
                transparent
            ),
            floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, w.toFloat(), 2f, android.graphics.Paint().apply {
            this.shader = shader
            isDither = true
        })
        bmp.asImageBitmap()
    }
    val bottomGradientBitmap = remember(backgroundColor, backdropHeightPx) {
        val h = backdropHeightPx.coerceAtLeast(1)
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(2, h, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val startY = h * 0.38f
        val shader = android.graphics.LinearGradient(
            0f, startY, 0f, h.toFloat(),
            intArrayOf(
                transparent,
                backgroundColor.copy(alpha = 0.05f).toArgb(),
                backgroundColor.copy(alpha = 0.18f).toArgb(),
                backgroundColor.copy(alpha = 0.38f).toArgb(),
                backgroundColor.copy(alpha = 0.60f).toArgb(),
                backgroundColor.copy(alpha = 0.78f).toArgb(),
                backgroundColor.copy(alpha = 0.91f).toArgb(),
                backgroundColor.copy(alpha = 0.97f).toArgb(),
                backgroundColor.copy(alpha = 1f).toArgb()
            ),
            floatArrayOf(0f, 0.10f, 0.22f, 0.36f, 0.52f, 0.66f, 0.78f, 0.90f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 2f, h.toFloat(), android.graphics.Paint().apply {
            this.shader = shader
            isDither = true
        })
        bmp.asImageBitmap()
    }

    // Animated gradient alpha (moved outside subcomposition scope)
    val gradientAlpha by animateFloatAsState(
        targetValue = if (isTrailerPlaying) 0f else 1f,
        animationSpec = tween(durationMillis = 800),
        label = "gradientFade"
    )

    // Always-composed bottom gradient alpha (avoids add/remove during scroll)
    val bottomGradientAlpha by animateFloatAsState(
        targetValue = if (isScrolledPastHero) 1f else 0f,
        animationSpec = tween(durationMillis = 300),
        label = "bottomGradientFade"
    )

    val heroSection: @Composable () -> Unit = {
        Box {
            HeroContentSection(
                meta = meta,
                nextEpisode = nextEpisode,
                nextToWatch = nextToWatch,
                onPlayClick = heroPlayClick,
                isInLibrary = isInLibrary,
                onToggleLibrary = onToggleLibrary,
                onLibraryLongPress = {
                    if (librarySourceMode == LibrarySourceMode.TRAKT || librarySourceMode == LibrarySourceMode.SIMKL) {
                        onLibraryLongPress()
                    }
                },
                mdbListRatings = mdbListRatings,
                hideMetaInfoImdb = showMdbListImdb,
                trailerAvailable = shouldShowDetailTrailerButton(
                    titleHasPlayableTrailerMedia = titleHasPlayableTrailerMedia,
                    trailerUrl = trailerUrl,
                    trailerExternalUrl = trailerExternalUrl
                ),
                onTrailerClick = heroTrailerClick,
                hideLogoDuringTrailer = hideLogoDuringTrailer,
                isTrailerPlaying = isTrailerPlaying,
                playButtonFocusRequester = heroPlayFocusRequester,
                downFocusRequester = heroDownFocusRequester,
                requestInitialFocus = shouldAutoResetFocusToHero(
                    initialHeroFocusRequested = initialHeroFocusRequested,
                    hasPendingRestoreTarget = pendingRestoreType != null,
                    hasPendingRestoreEpisodeId = pendingRestoreEpisodeId != null,
                    isTrailerPlaying = isTrailerPlaying,
                    isTrailerLoading = isTrailerLoading
                ),
                onMoveDownRequested = heroMoveDownRequest,
                onHeroActionFocused = { action ->
                    logFocusState("heroActionFocused action=$action")
                    initialHeroFocusRequested = true
                    if (action != HeroAction.PLAY) {
                        // Moving to LIBRARY/TRAILER is a real user navigation — disarm the guard.
                        userHasNavigatedFromPlay = true
                    }
                    clearPendingRestore()
                },
                restorePlayFocusToken = if (pendingRestoreType == RestoreTarget.HERO) restoreFocusToken else 0,
                onPlayFocusRestored = {
                    logFocusState("playFocusRestored restoreFocusToken=$restoreFocusToken")
                    onPlayButtonFocused()
                    initialHeroFocusRequested = true
                    clearPendingRestore()
                    coroutineScope.launch {
                        listState.restoreHeroScrollAfterFocus(reason = "play_focus_restored")
                    }
                },
                onPlayFocusChanged = { focused ->
                    playButtonIsFocused = focused
                    if (focused) {
                        playButtonEverFocused = true
                    }
                }
            )
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Sticky background — backdrop only. Trailer takeover is rendered above the detail content.
        Box(modifier = Modifier.fillMaxSize()) {
            // Backdrop image (fades out when trailer plays)
            AsyncImage(
                model = backdropRequest,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = backdropAlpha },
                contentScale = ContentScale.Crop
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        onDrawBehind {
                            drawRect(color = dimColor, size = size)
                            if (gradientAlpha > 0f) {
                                drawImage(
                                    leftGradientBitmap,
                                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                                    alpha = gradientAlpha
                                )
                            }
                            if (bottomGradientAlpha > 0f) {
                                drawImage(
                                    bottomGradientBitmap,
                                    dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()),
                                    alpha = bottomGradientAlpha
                                )
                            }
                        }
                    }
            )
        }

        // Single scrollable column with hero + content. Keep it mounted during trailer takeover.
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            state = listState
        ) {
                // Hero as first item in the lazy column
                item(key = "hero", contentType = "hero") {
                    heroSection()
                }

                // Season tabs and episodes for series
                if (isSeries && seasons.isNotEmpty()) {
                    item(key = "season_tabs", contentType = "season_tabs") {
                        SeasonTabs(
                            seasons = seasons,
                            selectedSeason = selectedSeason,
                            onSeasonSelected = onSeasonSelected,
                            onSeasonShortPress = onSeasonShortPress,
                            onSeasonLongPress = {
                                onSeasonSelected(it)
                                onSeasonOptionsOpened(it)
                                seasonOptionsDialogSeason = it
                            },
                            selectedTabFocusRequester = selectedSeasonFocusRequester,
                            upFocusRequester = heroPlayFocusRequester,
                            enableFocus = lowerContentFocusEnabled,
                            enableFocusRestorer = shouldEnableSectionFocusRestorer(
                                hasVisitedSection = hasVisitedSeasonTabs,
                                hasActiveRestoreTarget = pendingRestoreType == RestoreTarget.SEASON_ENTRY ||
                                    pendingRestoreType == RestoreTarget.EPISODE
                            ),
                            downFocusRequester = seasonDownFocusRequester,
                            onSelectedSeasonDown = seasonEntryEpisodeId?.let { episodeId ->
                                { requestSeasonEntryRestore(episodeId) }
                            },
                            onSeasonTabFocused = {
                                hasVisitedSeasonTabs = true
                            }
                        )
                    }
                    item(key = "episodes_$selectedSeason", contentType = "episodes") {
                        val isEpisodeRestoreActive = pendingRestoreType == RestoreTarget.EPISODE ||
                            pendingRestoreType == RestoreTarget.SEASON_ENTRY
                        EpisodesRow(
                            episodes = episodesForSeason,
                            episodeProgressMap = episodeProgressMap,
                            episodeRatings = episodeRatings,
                            watchedEpisodes = watchedEpisodes,
                            episodeWatchedPendingKeys = episodeWatchedPendingKeys,
                            blurUnwatchedEpisodes = blurUnwatchedEpisodes,
                            showManualStreamSelection = shouldShowEpisodeManualStreamSelection(
                                deterministicAutoplayEnabled = deterministicAutoplayEnabled
                            ),
                            onEpisodeClick = episodeClick,
                            onPlayWithManualStreamSelection = onPlayEpisodeWithManualStreamSelection,
                            onToggleEpisodeWatched = onToggleEpisodeWatched,
                            onClearEpisodeProgress = onClearEpisodeProgress,
                            onCheckInEpisode = onCheckInEpisode,
                            onMarkSeasonWatched = onMarkSeasonWatched,
                            onMarkSeasonUnwatched = onMarkSeasonUnwatched,
                            isSeasonFullyWatched = isSeasonFullyWatched(selectedSeason),
                            selectedSeason = selectedSeason,
                            onMarkPreviousEpisodesWatched = onMarkPreviousEpisodesWatched,
                            upFocusRequester = selectedSeasonFocusRequester,
                            downFocusRequester = episodesDownFocusRequester,
                            episodeFocusRequesters = seasonEpisodeFocusRequesters,
                            restoreEpisodeId = if (isEpisodeRestoreActive) pendingRestoreEpisodeId else null,
                            restoreFocusToken = if (isEpisodeRestoreActive) restoreFocusToken else 0,
                            onRestoreFocusHandled = {
                                clearPendingRestore()
                            },
                            onEpisodeFocused = { episodeId ->
                                hasVisitedEpisodes = true
                                lastFocusedEpisodeIdBySeason[selectedSeason] = episodeId
                            },
                            scrollToEpisodeId = if (nextToWatchScrolledSeasons[selectedSeason] != true) {
                                seasonEntryEpisodeId
                            } else null,
                            onScrollToEpisodeHandled = {
                                nextToWatchScrolledSeasons[selectedSeason] = true
                            }
                        )
                    }
                }

            // Cast / More like this section
            if (hasPeopleSection) {
                if (hasPeopleTabs) {
                    item(key = "cast_more_like_tabs", contentType = "horizontal_row") {
                        PeopleSectionTabs(
                            activeTab = activePeopleTab,
                            tabs = peopleTabItems,
                            enableFocus = lowerContentFocusEnabled,
                            enableFocusRestorer = shouldEnableSectionFocusRestorer(
                                hasVisitedSection = hasVisitedPeopleTabs,
                                hasActiveRestoreTarget = false
                            ),
                            upFocusRequester = peopleSectionUpFocusRequester,
                            ratingsDownFocusRequester = ratingsContentFocusRequester,
                            onTabFocused = { tab ->
                                hasVisitedPeopleTabs = true
                                activePeopleTab = tab
                            }
                        )
                    }
                }

                item(key = "cast_or_more_like", contentType = "horizontal_row") {
                    val visiblePeopleSection = if (hasPeopleTabs) {
                        activePeopleTab
                    } else {
                        availablePeopleTabs.first()
                    }
                    val hasItemsBelow = meta.networks.isNotEmpty() || meta.productionCompanies.isNotEmpty()
                    var castSectionHeightPx by remember { mutableIntStateOf(0) }
                    val castSectionHeight = with(LocalDensity.current) { castSectionHeightPx.toDp() }

                    Crossfade(
                        targetState = visiblePeopleSection,
                        animationSpec = tween(durationMillis = 160),
                        label = "peopleSectionSwitch"
                    ) { section ->
                        when (section) {
                            PeopleSectionTab.CAST -> {
                                CastSection(
                                    cast = normalCastMembers,
                                    title = if (hasPeopleTabs) "" else strTabCast,
                                    leadingCast = directorWriterMembers,
                                    enableFocus = lowerContentFocusEnabled,
                                    upFocusRequester = if (hasPeopleTabs) castTabFocusRequester else peopleSectionUpFocusRequester,
                                    restorePersonId = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) pendingRestoreCastPersonId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.CAST_MEMBER) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onCastMemberClick = { member ->
                                        member.tmdbId?.let { id ->
                                            markCastMemberRestore(id)
                                            val preferCrew = member.character.equals("Creator", ignoreCase = true) ||
                                                member.character.equals("Director", ignoreCase = true) ||
                                                member.character.equals("Writer", ignoreCase = true)
                                            onNavigateToCastDetail(id, member.name, preferCrew, "tmdb")
                                        } ?: member.tvdbPeopleId?.let { id ->
                                            markCastMemberRestore(id)
                                            val preferCrew = member.character.equals("Creator", ignoreCase = true) ||
                                                member.character.equals("Director", ignoreCase = true) ||
                                                member.character.equals("Writer", ignoreCase = true)
                                            onNavigateToCastDetail(id, member.name, preferCrew, "tvdb")
                                        }
                                    },
                                    modifier = Modifier.onSizeChanged { castSectionHeightPx = it.height }
                                )
                            }

                            PeopleSectionTab.RELATED -> {
                                MoreLikeThisSection(
                                    items = relatedItems,
                                    upFocusRequester = if (hasPeopleTabs) moreLikeTabFocusRequester else peopleSectionUpFocusRequester,
                                    restoreItemId = if (pendingRestoreType == RestoreTarget.RELATED) pendingRestoreMoreLikeItemId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.RELATED) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onItemClick = { item ->
                                        markMoreLikeThisRestore(item.id)
                                        onNavigateToDetail(item.id, item.apiType, null)
                                    }
                                )
                            }

                            PeopleSectionTab.REVIEWS -> {
                                ReviewsSection(
                                    reviews = reviews,
                                    isLoading = isReviewsLoading,
                                    error = reviewsError,
                                    title = if (hasPeopleTabs) "" else strTabReviews,
                                    upFocusRequester = if (hasPeopleTabs) {
                                        reviewsTabFocusRequester
                                    } else {
                                        peopleSectionUpFocusRequester
                                    },
                                    onReviewFocused = onReviewFocused
                                )
                            }
                            
                            PeopleSectionTab.COLLECTION -> {
                                CollectionSection(
                                    items = collection,
                                    upFocusRequester = if (hasPeopleTabs) collectionTabFocusRequester else peopleSectionUpFocusRequester,
                                    restoreItemId = if (pendingRestoreType == RestoreTarget.COLLECTION) pendingRestoreCollectionItemId else null,
                                    restoreFocusToken = if (pendingRestoreType == RestoreTarget.COLLECTION) restoreFocusToken else 0,
                                    onRestoreFocusHandled = {
                                        clearPendingRestore()
                                    },
                                    onItemClick = { item ->
                                        markCollectionRestore(item.id)
                                        onNavigateToDetail(item.id, item.apiType, null)
                                    }
                                )
                            }

                            PeopleSectionTab.RATINGS -> {
                                EpisodeRatingsSection(
                                    episodes = meta.videos,
                                    ratings = resolveEpisodeRatingValues(episodeRatings),
                                    isLoading = isEpisodeRatingsLoading,
                                    error = episodeRatingsError,
                                    title = if (hasPeopleTabs) "" else strTabRatings,
                                    upFocusRequester = if (hasPeopleTabs) {
                                        ratingsTabFocusRequester
                                    } else {
                                        peopleSectionUpFocusRequester
                                    },
                                    firstItemFocusRequester = ratingsContentFocusRequester,
                                    modifier = Modifier.heightIn(min = if (!hasItemsBelow) castSectionHeight else 0.dp)
                                )
                            }
                        }
                    }
                }
            }

            if (isTvShow) {
                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks,
                            onCompanyClick = { company ->
                                navigateToOrganizationDetail(
                                    company = company,
                                    discoverType = OrganizationDiscoverType.TV_NETWORK,
                                    onNavigateToOrganizationDetail = onNavigateToOrganizationDetail
                                )
                            }
                        )
                    }
                }

                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies,
                            onCompanyClick = { company ->
                                navigateToOrganizationDetail(
                                    company = company,
                                    discoverType = OrganizationDiscoverType.TV_COMPANY,
                                    onNavigateToOrganizationDetail = onNavigateToOrganizationDetail
                                )
                            }
                        )
                    }
                }
            } else {
                if (meta.productionCompanies.isNotEmpty()) {
                    item(key = "production", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_production),
                            companies = meta.productionCompanies,
                            onCompanyClick = { company ->
                                navigateToOrganizationDetail(
                                    company = company,
                                    discoverType = OrganizationDiscoverType.MOVIE_COMPANY,
                                    onNavigateToOrganizationDetail = onNavigateToOrganizationDetail
                                )
                            }
                        )
                    }
                }

                if (meta.networks.isNotEmpty()) {
                    item(key = "networks", contentType = "horizontal_row") {
                        CompanyLogosSection(
                            title = stringResource(R.string.detail_section_network),
                            companies = meta.networks,
                            onCompanyClick = { company ->
                                navigateToOrganizationDetail(
                                    company = company,
                                    discoverType = OrganizationDiscoverType.TV_NETWORK,
                                    onNavigateToOrganizationDetail = onNavigateToOrganizationDetail
                                )
                            }
                        )
                    }
                }
            }
        }

        if (showTrailerTakeover) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .zIndex(50f)
            ) {
                if (shouldRenderDetailTrailerPlayer(isTrailerPlaying, trailerUrl)) {
                    TrailerPlayer(
                        trailerUrl = trailerUrl,
                        trailerAudioUrl = trailerAudioUrl,
                        isPlaying = true,
                        onEnded = {
                            trailerFirstFrameRendered = false
                            onTrailerEnded()
                        },
                        onFirstFrameRendered = {
                            trailerFirstFrameRendered = true
                        },
                        seekRequestToken = trailerSeekToken,
                        seekDeltaMs = trailerSeekDeltaMs,
                        onProgressChanged = onTrailerProgressChanged,
                        onRemoteKey = onTrailerControlKey,
                        cropToFill = true,
                        modifier = Modifier.fillMaxSize()
                    )
                } else if (immediateTrailerTakeoverPending || isTrailerLoading) {
                    ImmediateDetailTrailerTakeoverOverlay(
                        backdropUrl = meta.background ?: meta.poster
                    )
                }
            }
        }

        seasonOptionsDialogSeason?.let { season ->
            SeasonOptionsDialog(
                season = season,
                isFullyWatched = isSeasonFullyWatched(season),
                onPlayRecap = if (seasonMediaAvailabilityBySeason[season]?.hasRecap == true) {
                    {
                        onPlaySeasonRecap(season)
                        seasonOptionsDialogSeason = null
                    }
                } else {
                    null
                },
                onDismiss = { seasonOptionsDialogSeason = null },
                onMarkSeasonWatched = {
                    onMarkSeasonWatched(season)
                    seasonOptionsDialogSeason = null
                },
                onMarkSeasonUnwatched = {
                    onMarkSeasonUnwatched(season)
                    seasonOptionsDialogSeason = null
                }
            )
        }
    }
}

private fun navigateToOrganizationDetail(
    company: MetaCompany,
    discoverType: OrganizationDiscoverType,
    onNavigateToOrganizationDetail: (Int, String, String, String) -> Unit
) {
    val entityId = company.tmdbId ?: return
    onNavigateToOrganizationDetail(
        entityId,
        company.name,
        company.kind.name,
        discoverType.name
    )
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PeopleSectionTabs(
    activeTab: PeopleSectionTab,
    tabs: List<PeopleTabItem>,
    enableFocus: Boolean = true,
    enableFocusRestorer: Boolean = true,
    upFocusRequester: FocusRequester? = null,
    ratingsDownFocusRequester: FocusRequester? = null,
    onTabFocused: (PeopleSectionTab) -> Unit
) {
    if (tabs.isEmpty()) return

    val defaultRequester = tabs.first().focusRequester
    val restorerRequester = tabs.firstOrNull { it.tab == activeTab }?.focusRequester ?: defaultRequester

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 20.dp, start = 48.dp, end = 48.dp)
            .then(
                if (enableFocusRestorer) {
                    Modifier.focusRestorer(restorerRequester)
                } else {
                    Modifier
                }
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEachIndexed { index, item ->
            if (index > 0) {
                Text(
                    text = "|",
                    style = MaterialTheme.typography.titleLarge,
                    color = NexioColors.TextPrimary.copy(alpha = 0.45f),
                    modifier = Modifier.padding(horizontal = 10.dp)
                )
            }

            PeopleSectionTabButton(
                label = item.label,
                selected = activeTab == item.tab,
                focusRequester = item.focusRequester,
                enableFocus = enableFocus,
                upFocusRequester = upFocusRequester,
                downFocusRequester = if (item.tab == PeopleSectionTab.RATINGS) ratingsDownFocusRequester else null,
                onFocused = { onTabFocused(item.tab) }
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun PeopleSectionTabButton(
    label: String,
    selected: Boolean,
    focusRequester: FocusRequester,
    enableFocus: Boolean = true,
    upFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    onFocused: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onFocused,
        modifier = Modifier
            .focusRequester(focusRequester)
            .focusProperties {
                canFocus = enableFocus
                if (upFocusRequester != null) {
                    up = upFocusRequester
                }
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            }
            .onFocusChanged { state ->
                val focusedNow = state.isFocused
                isFocused = focusedNow
                if (focusedNow) {
                    detailFocusDebug("peopleTabFocused label=$label")
                    onFocused()
                }
            },
        colors = CardDefaults.colors(
            containerColor = Color.Transparent,
            focusedContainerColor = Color.Transparent
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(0.dp, Color.Transparent),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        scale = CardDefaults.scale(focusedScale = 1.03f)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleLarge,
            color = when {
                isFocused -> NexioColors.TextPrimary
                selected -> NexioColors.TextPrimary.copy(alpha = 0.92f)
                else -> NexioColors.TextPrimary.copy(alpha = 0.55f)
            },
            modifier = Modifier.padding(horizontal = 2.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun TrailerSeekOverlayHost(
    visible: Boolean,
    overlayState: TrailerSeekOverlayState,
    modifier: Modifier = Modifier
) {
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        enter = androidx.compose.animation.fadeIn(animationSpec = tween(150)),
        exit = androidx.compose.animation.fadeOut(animationSpec = tween(150)),
        modifier = modifier
    ) {
        TrailerSeekOverlay(
            currentPosition = overlayState.positionMs,
            duration = overlayState.durationMs
        )
    }
}

@Composable
private fun TrailerSeekOverlay(
    currentPosition: Long,
    duration: Long
) {
    val progress = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    val animatedProgress by animateFloatAsState(
        targetValue = progress,
        animationSpec = tween(100),
        label = "trailerSeekProgress"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color.White.copy(alpha = 0.3f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(NexioColors.Secondary)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "${formatPlaybackTime(currentPosition)} / ${formatPlaybackTime(duration)}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.9f)
            )
        }
    }
}

private fun formatPlaybackTime(milliseconds: Long): String {
    val totalSeconds = (milliseconds / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) {
        String.format("%d:%02d:%02d", hours, minutes, seconds)
    } else {
        String.format("%02d:%02d", minutes, seconds)
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun LibraryListPickerDialog(
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

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun OfficialAppChooserDialog(
    title: String,
    targets: List<OfficialStreamingAppTarget>,
    onOpen: (OfficialStreamingAppTarget) -> Unit,
    onDismiss: () -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.detail_open_in_streaming_app_title),
        subtitle = stringResource(R.string.detail_open_in_streaming_app_subtitle, title)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            targets.forEach { target ->
                Button(
                    onClick = { onOpen(target) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(target.displayName)
                }
            }
            Button(
                onClick = onDismiss,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary
                )
            ) {
                Text(stringResource(R.string.action_cancel))
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun NoOfficialAppDialog(
    onDismiss: () -> Unit
) {
    NexioDialog(
        onDismiss = onDismiss,
        title = stringResource(R.string.detail_no_streaming_app_title),
        subtitle = stringResource(R.string.detail_no_streaming_app_subtitle)
    ) {
        Button(
            onClick = onDismiss,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.action_close))
        }
    }
}

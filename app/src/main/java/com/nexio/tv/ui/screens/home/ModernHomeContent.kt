@file:OptIn(
    androidx.compose.foundation.ExperimentalFoundationApi::class,
    androidx.compose.ui.ExperimentalComposeUiApi::class
)

package com.nexio.tv.ui.screens.home

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.focusable
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.BringIntoViewSpec
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.nexio.tv.R
import com.nexio.tv.LocalContentFocusRequester
import com.nexio.tv.core.ui.findLifecycleOwner
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nexio.tv.domain.model.CatalogRow
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.ui.components.ContinueWatchingCard
import com.nexio.tv.ui.components.ContinueWatchingOptionsDialog
import com.nexio.tv.ui.components.MonochromePosterPlaceholder
import com.nexio.tv.ui.theme.NexioColors
import kotlinx.coroutines.delay
import android.view.KeyEvent as AndroidKeyEvent
import kotlinx.coroutines.flow.distinctUntilChanged
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

private const val MODERN_HERO_RAPID_NAV_THRESHOLD_MS = 130L
private const val MODERN_HERO_RAPID_NAV_SETTLE_MS = 170L

internal enum class ModernHomeRepeatFocusDirection {
    Up,
    Down,
    Left,
    Right
}

internal fun modernHomeRepeatFocusDirectionForKeyCode(keyCode: Int): ModernHomeRepeatFocusDirection? {
    return when (keyCode) {
        AndroidKeyEvent.KEYCODE_DPAD_UP -> ModernHomeRepeatFocusDirection.Up
        AndroidKeyEvent.KEYCODE_DPAD_DOWN -> ModernHomeRepeatFocusDirection.Down
        AndroidKeyEvent.KEYCODE_DPAD_LEFT -> ModernHomeRepeatFocusDirection.Left
        AndroidKeyEvent.KEYCODE_DPAD_RIGHT -> ModernHomeRepeatFocusDirection.Right
        else -> null
    }
}

internal fun modernHomeRepeatThrottleMs(direction: ModernHomeRepeatFocusDirection): Long {
    return when (direction) {
        ModernHomeRepeatFocusDirection.Up,
        ModernHomeRepeatFocusDirection.Down -> 112L
        ModernHomeRepeatFocusDirection.Left,
        ModernHomeRepeatFocusDirection.Right -> 80L
    }
}

private fun ModernHomeRepeatFocusDirection.toComposeFocusDirection(): FocusDirection {
    return when (this) {
        ModernHomeRepeatFocusDirection.Up -> FocusDirection.Up
        ModernHomeRepeatFocusDirection.Down -> FocusDirection.Down
        ModernHomeRepeatFocusDirection.Left -> FocusDirection.Left
        ModernHomeRepeatFocusDirection.Right -> FocusDirection.Right
    }
}
private const val MODERN_HOME_CONTENT_LOG_TAG = "ModernHomeContent"

private fun modernHomeDebugLog(message: String) {
    if (!com.nexio.tv.BuildConfig.DEBUG) return
    runCatching { Log.d(MODERN_HOME_CONTENT_LOG_TAG, message) }
}

internal fun shouldActivateFocusedTrailerPreviewAfterDelay(
    shouldActivateFocusedPosterFlow: Boolean,
    isVerticalRowsScrolling: Boolean,
    selectionStillFocused: Boolean,
    lifecycleResumed: Boolean
): Boolean = shouldActivateFocusedPosterFlow &&
    !isVerticalRowsScrolling &&
    selectionStillFocused &&
    lifecycleResumed

internal fun shouldDismissModernHomeTrailerOnBack(
    heroTrailerActive: Boolean,
    expandedCardTrailerActive: Boolean,
    fullscreenTrailerActive: Boolean
): Boolean = heroTrailerActive || expandedCardTrailerActive || fullscreenTrailerActive

internal fun shouldPromoteModernHomeHeroTrailerToFullscreen(
    key: Key,
    eventType: KeyEventType,
    heroTrailerPlaying: Boolean,
    fullscreenTrailerActive: Boolean
): Boolean = key == Key.DirectionUp &&
    eventType == KeyEventType.KeyDown &&
    heroTrailerPlaying &&
    !fullscreenTrailerActive

internal fun shouldShowModernHomeFullscreenTextOverlay(
    fullscreenTrailerActive: Boolean,
    overlayTimedOut: Boolean
): Boolean = false

internal fun shouldShowModernHomeHeroFullscreenHint(
    heroTrailerPlaying: Boolean,
    fullscreenTrailerActive: Boolean,
    hintTimedOut: Boolean
): Boolean = heroTrailerPlaying && !fullscreenTrailerActive && !hintTimedOut

internal fun resolveModernHomeHeroFullscreenHintEndPadding(
    fullscreenTrailerActive: Boolean
): Dp = if (fullscreenTrailerActive) 20.dp else 76.dp

internal fun resolveModernHomeHeroTrailerMuted(
    fullscreenTrailerActive: Boolean
): Boolean = !fullscreenTrailerActive

internal fun shouldTreatFocusedPosterTrailerPlaybackAsActiveTime(
    hasActivePlayback: Boolean,
    trailerMuted: Boolean
): Boolean = hasActivePlayback && !trailerMuted

internal fun shouldTreatModernHomeTrailerPlaybackAsActiveTime(
    internalHomeTrailerPlaying: Boolean,
    trailerMuted: Boolean
): Boolean = internalHomeTrailerPlaying && !trailerMuted

internal data class ModernHomeTrailerEndedState(
    val unlockedTrailerFocusKey: String? = null,
    val pendingHeroTrailerFocusKey: String? = null,
    val heroTrailerFullscreenMode: Boolean = false,
    val fullscreenTrailerTextTimedOut: Boolean = false,
    val heroFullscreenHintTimedOut: Boolean = false
)

internal fun handleModernHomeTrailerEnded(
    focusedTrailerFocusKey: String?,
    activeItemIndex: Int,
    focusedTrailerRowKey: String?,
    state: ModernHomeTrailerEndedState
): Pair<ModernHomeTrailerEndedState, Pair<String, Int>?> {
    val clearedState = state.copy(
        unlockedTrailerFocusKey = null,
        pendingHeroTrailerFocusKey = null,
        heroTrailerFullscreenMode = false,
        fullscreenTrailerTextTimedOut = false,
        heroFullscreenHintTimedOut = false
    )
    val focusRestore = if (!focusedTrailerFocusKey.isNullOrBlank() && !focusedTrailerRowKey.isNullOrBlank()) {
        focusedTrailerRowKey to activeItemIndex
    } else {
        null
    }
    return clearedState to focusRestore
}

internal fun handleModernHomeTrailerInterruptedByExternalTakeover(
    state: ModernHomeTrailerEndedState
): ModernHomeTrailerEndedState {
    return state.copy(
        unlockedTrailerFocusKey = null,
        pendingHeroTrailerFocusKey = null,
        heroTrailerFullscreenMode = false,
        fullscreenTrailerTextTimedOut = false,
        heroFullscreenHintTimedOut = false
    )
}

internal fun shouldUnlockModernHomeTrailerAutoplay(
    autoplayEnabled: Boolean,
    screensaverVisible: Boolean,
    startupSplashVisible: Boolean,
    externalTrailerTakeoverActive: Boolean,
    selectionStillFocused: Boolean,
    lifecycleResumed: Boolean
): Boolean = autoplayEnabled &&
    !screensaverVisible &&
    !startupSplashVisible &&
    !externalTrailerTakeoverActive &&
    selectionStillFocused &&
    lifecycleResumed

internal fun shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
    trailerPlaybackUnlocked: Boolean,
    hasTrailerMetadataAvailable: Boolean,
    hasResolvedPreview: Boolean,
    hasResolvedExternalPreview: Boolean,
    isCurrentlyLoading: Boolean,
    externalTrailerTakeoverActive: Boolean,
    alreadyRetriedAfterUnlock: Boolean
): Boolean = trailerPlaybackUnlocked &&
    hasTrailerMetadataAvailable &&
    !hasResolvedPreview &&
    !hasResolvedExternalPreview &&
    !isCurrentlyLoading &&
    !externalTrailerTakeoverActive &&
    !alreadyRetriedAfterUnlock

internal fun resolveEffectiveModernHomeTrailerPlaybackTarget(
    requestedTarget: com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget,
    effectiveExpandEnabled: Boolean
): com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget {
    return if (
        requestedTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
        !effectiveExpandEnabled
    ) {
        com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    } else {
        requestedTarget
    }
}

@Composable
internal fun ModernHomeContent(
    contentState: ModernHomeContentState,
    idleScreensaverVisible: Boolean,
    startupSplashVisible: Boolean,
    focusState: HomeScreenFocusState,
    enrichingItemIdState: State<String?>,
    onNavigateToDetail: (String, String, String) -> Unit,
    onContinueWatchingClick: (ContinueWatchingItem) -> Unit,
    onContinueWatchingStartFromBeginning: (ContinueWatchingItem) -> Unit = {},
    onContinueWatchingManualStreamSelection: (ContinueWatchingItem) -> Unit = {},
    onLoadMoreCatalog: (String, String, String) -> Unit,
    onRemoveContinueWatching: (String, Int?, Int?, Boolean) -> Unit,
    onMarkContinueWatchingWatched: (ContinueWatchingItem) -> Unit = {},
    onCheckInContinueWatching: ((ContinueWatchingItem) -> Unit)? = null,
    cwWatchlistMembership: Map<String, Boolean> = emptyMap(),
    onToggleContinueWatchingLibrary: ((ContinueWatchingItem) -> Unit)? = null,
    isCatalogItemWatched: (MetaPreview) -> Boolean = { false },
    onCatalogItemLongPress: (MetaPreview, String) -> Unit = { _, _ -> },
    onItemFocus: (MetaPreview) -> Unit = {},
    onPreloadAdjacentItem: (MetaPreview) -> Unit = {},
    onRequestTrailerPreview: (String, String, String?, String, String?) -> Unit,
    onRetryTrailerPreview: (String, String, String?, String, String?) -> Unit,
    externalTrailerTakeoverActive: Boolean = false,
    onModernHomeTrailerPlaybackStarted: () -> Unit,
    onModernHomeTrailerPlaybackActiveChanged: (Boolean) -> Unit,
    onSaveFocusState: (Int, Int, Int, Int, Map<String, Int>) -> Unit
) {
    val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
    val useLandscapePosters = contentState.modernLandscapePostersEnabled
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val navLifecycleOwner = LocalLifecycleOwner.current
    val lifecycleOwner = remember(context, navLifecycleOwner) {
        context.findLifecycleOwner() ?: navLifecycleOwner
    }
    val isLandscapeModern = useLandscapePosters
    val expandControlAvailable = !isLandscapeModern
    val trailerPlaybackTarget = com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
    val landscapeExpandedCardMode = false
    val effectiveExpandEnabled =
        (contentState.focusedPosterBackdropExpandEnabled && expandControlAvailable) ||
            landscapeExpandedCardMode
    val effectiveTrailerPlaybackTarget = resolveEffectiveModernHomeTrailerPlaybackTarget(
        requestedTarget = trailerPlaybackTarget,
        effectiveExpandEnabled = effectiveExpandEnabled
    )
    val shouldActivateFocusedPosterFlow = effectiveExpandEnabled
    val strPressUpForFullscreen = stringResource(R.string.modern_home_press_up_fullscreen)
    val presentation = contentState.modernHomePresentation
    val carouselRows = presentation.rows
    if (carouselRows.isEmpty()) return
    val carouselLookups = presentation.lookups
    val rowIndexByKey = carouselLookups.rowIndexByKey
    val rowByKey = carouselLookups.rowByKey
    val activeRowKeys = carouselLookups.activeRowKeys
    val activeItemKeysByRow = carouselLookups.activeItemKeysByRow
    val activeCatalogItemIds = carouselLookups.activeCatalogItemIds
    val verticalRowListState = rememberLazyListState(
        initialFirstVisibleItemIndex = focusState.verticalScrollIndex,
        initialFirstVisibleItemScrollOffset = focusState.verticalScrollOffset
    )
    val isVerticalRowsScrolling by remember(verticalRowListState) {
        derivedStateOf { verticalRowListState.isScrollInProgress }
    }

    val uiCaches = remember { ModernHomeUiCaches() }
    val focusedItemByRow = uiCaches.focusedItemByRow
    val itemFocusRequesters = uiCaches.itemFocusRequesters
    val rowListStates = uiCaches.rowListStates
    val loadMoreRequestedTotals = uiCaches.loadMoreRequestedTotals
    val focusHolder = remember {
        object {
            var activeRowKey: String? = null
            var activeItemIndex: Int = 0
        }
    }
    var activeRowKey by remember { mutableStateOf<String?>(null) }
    var activeItemIndex by remember { mutableIntStateOf(0) }
    var pendingRowFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingRowFocusIndex by remember { mutableStateOf<Int?>(null) }
    var pendingRowFocusNonce by remember { mutableIntStateOf(0) }
    var heroItem by remember { mutableStateOf<HeroPreview?>(null) }
    var displayedHeroItemKey by remember { mutableStateOf<String?>(null) }
    var restoredFromSavedState by remember { mutableStateOf(false) }
    var optionsItem by remember { mutableStateOf<ContinueWatchingItem?>(null) }
    val lastFocusedContinueWatchingIndexRef = remember { AtomicInteger(-1) }
    val lastKeyRepeatDispatchRef = remember { AtomicLong(0L) }
    val lastHeroNavigationAtMsRef = remember { AtomicLong(0L) }
    val heroFocusSettleDelayMsRef = remember { AtomicLong(MODERN_HERO_FOCUS_DEBOUNCE_MS) }
    var focusedCatalogSelection by remember { mutableStateOf<FocusedCatalogSelection?>(null) }
    var expandedCatalogFocusKey by remember { mutableStateOf<String?>(null) }
    var expandedCatalogRowKey by remember { mutableStateOf<String?>(null) }
    var expansionInteractionNonce by remember { mutableIntStateOf(0) }
    var lastExternalTrailerLaunchKey by remember { mutableStateOf<String?>(null) }
    var unlockedTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    var autoplayUnlockRetriedFocusKey by remember { mutableStateOf<String?>(null) }
    var pendingHeroTrailerFocusKey by remember { mutableStateOf<String?>(null) }
    var heroTrailerFullscreenMode by remember { mutableStateOf(false) }
    var fullscreenTrailerTextTimedOut by remember { mutableStateOf(false) }
    var heroFullscreenHintTimedOut by remember { mutableStateOf(false) }
    var heroFullscreenHintSessionNonce by remember { mutableIntStateOf(0) }
    val fullscreenTrailerFocusRequester = remember { FocusRequester() }

    val focusedTrailerSelection = remember(activeRowKey, activeItemIndex, rowByKey) {
        resolveFocusedTrailerSelection(
            rowKey = activeRowKey,
            item = rowByKey[activeRowKey]
                ?.items
                ?.getOrNull(activeItemIndex)
                ?: rowByKey[activeRowKey]?.items?.firstOrNull()
        )
    }

    LaunchedEffect(
        focusedTrailerSelection?.focusKey,
        contentState.focusedPosterBackdropTrailerEnabled,
        contentState.homeTrailerAutoplayEnabled,
        contentState.homeTrailerAutoplayDelaySeconds,
        idleScreensaverVisible,
        startupSplashVisible,
        externalTrailerTakeoverActive
    ) {
        unlockedTrailerFocusKey = null
        val selection = focusedTrailerSelection ?: return@LaunchedEffect
        modernHomeDebugLog(
            "heroAutoplayUnlock wait focusKey=${selection.focusKey} itemId=${selection.itemId} type=${selection.itemType} autoplay=${contentState.homeTrailerAutoplayEnabled} delay=${contentState.homeTrailerAutoplayDelaySeconds} splash=$startupSplashVisible"
        )
        delay(contentState.homeTrailerAutoplayDelaySeconds.coerceAtLeast(0) * 1000L)
        val selectionStillFocused = focusedTrailerSelection.focusKey == selection.focusKey
        val lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
        val shouldUnlock = shouldUnlockModernHomeTrailerAutoplay(
            autoplayEnabled = contentState.homeTrailerAutoplayEnabled,
            screensaverVisible = idleScreensaverVisible,
            startupSplashVisible = startupSplashVisible,
            externalTrailerTakeoverActive = externalTrailerTakeoverActive,
            selectionStillFocused = selectionStillFocused,
            lifecycleResumed = lifecycleResumed
        )
        modernHomeDebugLog(
            "heroAutoplayUnlock eval focusKey=${selection.focusKey} itemId=${selection.itemId} selectionStillFocused=$selectionStillFocused lifecycleResumed=$lifecycleResumed screensaver=$idleScreensaverVisible splash=$startupSplashVisible shouldUnlock=$shouldUnlock"
        )
        if (shouldUnlock) {
            unlockedTrailerFocusKey = selection.focusKey
            modernHomeDebugLog(
                "heroAutoplayUnlock success focusKey=${selection.focusKey} itemId=${selection.itemId}"
            )
        }
    }

    LaunchedEffect(externalTrailerTakeoverActive) {
        if (!externalTrailerTakeoverActive) return@LaunchedEffect
        val clearedState = handleModernHomeTrailerInterruptedByExternalTakeover(
            ModernHomeTrailerEndedState(
                unlockedTrailerFocusKey = unlockedTrailerFocusKey,
                pendingHeroTrailerFocusKey = pendingHeroTrailerFocusKey,
                heroTrailerFullscreenMode = heroTrailerFullscreenMode,
                fullscreenTrailerTextTimedOut = fullscreenTrailerTextTimedOut,
                heroFullscreenHintTimedOut = heroFullscreenHintTimedOut
            )
        )
        unlockedTrailerFocusKey = clearedState.unlockedTrailerFocusKey
        pendingHeroTrailerFocusKey = clearedState.pendingHeroTrailerFocusKey
        heroTrailerFullscreenMode = clearedState.heroTrailerFullscreenMode
        fullscreenTrailerTextTimedOut = clearedState.fullscreenTrailerTextTimedOut
        heroFullscreenHintTimedOut = clearedState.heroFullscreenHintTimedOut
        heroFullscreenHintSessionNonce = 0
    }

    LaunchedEffect(unlockedTrailerFocusKey, focusedTrailerSelection?.focusKey) {
        if (unlockedTrailerFocusKey != focusedTrailerSelection?.focusKey) {
            autoplayUnlockRetriedFocusKey = null
            pendingHeroTrailerFocusKey = null
            heroTrailerFullscreenMode = false
            fullscreenTrailerTextTimedOut = false
            heroFullscreenHintTimedOut = false
            heroFullscreenHintSessionNonce = 0
        }
    }

    LaunchedEffect(
        focusedCatalogSelection?.focusKey,
        expansionInteractionNonce,
        shouldActivateFocusedPosterFlow,
        contentState.focusedPosterBackdropExpandDelaySeconds,
        isVerticalRowsScrolling
    ) {
        expandedCatalogFocusKey = null
        expandedCatalogRowKey = null
        if (!shouldActivateFocusedPosterFlow) return@LaunchedEffect
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val selection = focusedCatalogSelection ?: return@LaunchedEffect
        delay(contentState.focusedPosterBackdropExpandDelaySeconds.coerceAtLeast(0) * 1000L)
        if (shouldActivateFocusedTrailerPreviewAfterDelay(
                shouldActivateFocusedPosterFlow = shouldActivateFocusedPosterFlow,
                isVerticalRowsScrolling = isVerticalRowsScrolling,
                selectionStillFocused = focusedCatalogSelection?.focusKey == selection.focusKey,
                lifecycleResumed = lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            )
        ) {
            expandedCatalogFocusKey = selection.focusKey
            expandedCatalogRowKey = selection.rowKey
        }
    }

    LaunchedEffect(carouselRows, focusState.hasSavedFocus, focusState.focusedRowIndex, focusState.focusedItemIndex) {
        focusedItemByRow.keys.retainAll(activeRowKeys)
        itemFocusRequesters.keys.retainAll(activeRowKeys)
        rowListStates.keys.retainAll(activeRowKeys)
        loadMoreRequestedTotals.keys.retainAll(activeRowKeys)
        carouselRows.forEach { row ->
            val rowRequesters = itemFocusRequesters[row.key] ?: return@forEach
            val allowedKeys = activeItemKeysByRow[row.key] ?: emptySet()
            rowRequesters.keys.retainAll(allowedKeys)
        }
        if (focusedCatalogSelection?.payload?.itemId !in activeCatalogItemIds) {
            focusedCatalogSelection = null
            expandedCatalogFocusKey = null
            expandedCatalogRowKey = null
        }

        carouselRows.forEach { row ->
            if (row.items.isNotEmpty() && row.key !in focusedItemByRow) {
                focusedItemByRow[row.key] = 0
            }
        }

        if (!restoredFromSavedState && focusState.hasSavedFocus) {
            val savedRowKey = when {
                focusState.focusedRowIndex == -1 && contentState.continueWatchingItems.isNotEmpty() -> "continue_watching"
                focusState.focusedRowIndex >= 0 -> carouselRows.firstOrNull {
                    it.globalRowIndex == focusState.focusedRowIndex
                }?.key
                else -> null
            }

            val resolvedRow = carouselRows.firstOrNull { it.key == savedRowKey } ?: carouselRows.first()
            val resolvedIndex = focusState.focusedItemIndex
                .coerceAtLeast(0)
                .coerceAtMost((resolvedRow.items.size - 1).coerceAtLeast(0))

            focusHolder.activeRowKey = resolvedRow.key
            focusHolder.activeItemIndex = resolvedIndex
            activeRowKey = resolvedRow.key
            activeItemIndex = resolvedIndex
            focusedItemByRow[resolvedRow.key] = resolvedIndex
            heroItem = resolvedRow.items.getOrNull(resolvedIndex)?.heroPreview
                ?: resolvedRow.items.firstOrNull()?.heroPreview
            displayedHeroItemKey = resolvedRow.items.getOrNull(resolvedIndex)?.key
                ?: resolvedRow.items.firstOrNull()?.key
            pendingRowFocusKey = resolvedRow.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
            restoredFromSavedState = true
            return@LaunchedEffect
        }

        val hadActiveRow = focusHolder.activeRowKey != null
        val existingActive = focusHolder.activeRowKey?.let { key -> carouselRows.firstOrNull { it.key == key } }
        val resolvedActive = existingActive ?: carouselRows.first()
        val resolvedIndex = focusedItemByRow[resolvedActive.key]
            ?.coerceIn(0, (resolvedActive.items.size - 1).coerceAtLeast(0))
            ?: 0
        focusHolder.activeRowKey = resolvedActive.key
        focusHolder.activeItemIndex = resolvedIndex
        activeRowKey = resolvedActive.key
        activeItemIndex = resolvedIndex
        focusedItemByRow[resolvedActive.key] = resolvedIndex
        heroItem = resolvedActive.items.getOrNull(resolvedIndex)?.heroPreview
            ?: resolvedActive.items.firstOrNull()?.heroPreview
        displayedHeroItemKey = resolvedActive.items.getOrNull(resolvedIndex)?.key
            ?: resolvedActive.items.firstOrNull()?.key
        if (!focusState.hasSavedFocus && (!hadActiveRow || existingActive == null)) {
            pendingRowFocusKey = resolvedActive.key
            pendingRowFocusIndex = resolvedIndex
            pendingRowFocusNonce++
        }
    }

    LaunchedEffect(
        unlockedTrailerFocusKey,
        focusedTrailerSelection?.focusKey,
        focusedTrailerSelection?.itemType,
        focusedTrailerSelection?.itemId,
        contentState.trailerPreviewUrls,
        contentState.trailerPreviewExternalUrls,
        contentState.trailerPreviewLoadingIds,
        contentState.trailerPreviewNegativeCacheIds,
        contentState.trailerMetadataAvailableKeys
    ) {
        val selection = focusedTrailerSelection ?: return@LaunchedEffect
        val hasTrailerMetadataAvailable = homeTrailerAvailabilityKey(
            selection.itemId,
            selection.itemType
        ) in contentState.trailerMetadataAvailableKeys
        val hasResolvedPreview = !contentState.trailerPreviewUrls[selection.itemId].isNullOrBlank()
        val hasResolvedExternalPreview = !contentState.trailerPreviewExternalUrls[selection.itemId].isNullOrBlank()
        val isCurrentlyLoading = selection.itemId in contentState.trailerPreviewLoadingIds
        val alreadyRetriedAfterUnlock = autoplayUnlockRetriedFocusKey == selection.focusKey
        val trailerPlaybackUnlocked = unlockedTrailerFocusKey == selection.focusKey
        val shouldRequest = shouldRequestFocusedTrailerPreviewAfterAutoplayUnlock(
            trailerPlaybackUnlocked = trailerPlaybackUnlocked,
            hasTrailerMetadataAvailable = hasTrailerMetadataAvailable,
            hasResolvedPreview = hasResolvedPreview,
            hasResolvedExternalPreview = hasResolvedExternalPreview,
            isCurrentlyLoading = isCurrentlyLoading,
            externalTrailerTakeoverActive = externalTrailerTakeoverActive,
            alreadyRetriedAfterUnlock = alreadyRetriedAfterUnlock
        )
        modernHomeDebugLog(
            "heroAutoplayRequest eval focusKey=${selection.focusKey} itemId=${selection.itemId} unlocked=$trailerPlaybackUnlocked metadata=$hasTrailerMetadataAvailable resolved=$hasResolvedPreview external=$hasResolvedExternalPreview loading=$isCurrentlyLoading retried=$alreadyRetriedAfterUnlock shouldRequest=$shouldRequest"
        )
        if (!shouldRequest) {
            return@LaunchedEffect
        }

        autoplayUnlockRetriedFocusKey = selection.focusKey
        pendingHeroTrailerFocusKey = selection.focusKey
        modernHomeDebugLog(
            "heroAutoplayRequest start focusKey=${selection.focusKey} itemId=${selection.itemId} negativeCache=${selection.itemId in contentState.trailerPreviewNegativeCacheIds}"
        )
        if (selection.itemId in contentState.trailerPreviewNegativeCacheIds) {
            onRetryTrailerPreview(
                selection.itemId,
                selection.trailerTitle,
                selection.trailerReleaseInfo,
                selection.trailerApiType,
                selection.fallbackTrailerYtId
            )
        } else {
            onRequestTrailerPreview(
                selection.itemId,
                selection.trailerTitle,
                selection.trailerReleaseInfo,
                selection.trailerApiType,
                selection.fallbackTrailerYtId
            )
        }
    }

    LaunchedEffect(
        pendingHeroTrailerFocusKey,
        focusedTrailerSelection?.focusKey,
        focusedTrailerSelection?.itemId,
        contentState.trailerPreviewUrls,
        contentState.trailerPreviewExternalUrls,
        contentState.trailerPreviewLoadingIds,
        contentState.trailerPreviewNegativeCacheIds
    ) {
        val pendingFocusKey = pendingHeroTrailerFocusKey ?: return@LaunchedEffect
        val selection = focusedTrailerSelection
        if (selection?.focusKey != pendingFocusKey) {
            pendingHeroTrailerFocusKey = null
            return@LaunchedEffect
        }
        val itemId = selection.itemId
        if (
            !contentState.trailerPreviewUrls[itemId].isNullOrBlank() ||
            !contentState.trailerPreviewExternalUrls[itemId].isNullOrBlank()
        ) {
            pendingHeroTrailerFocusKey = null
            return@LaunchedEffect
        }
        if (
            itemId in contentState.trailerPreviewNegativeCacheIds &&
            itemId !in contentState.trailerPreviewLoadingIds
        ) {
            pendingHeroTrailerFocusKey = null
        }
    }

    LaunchedEffect(focusState.verticalScrollIndex, focusState.verticalScrollOffset) {
        val targetIndex = focusState.verticalScrollIndex
        val targetOffset = focusState.verticalScrollOffset
        if (verticalRowListState.firstVisibleItemIndex == targetIndex &&
            verticalRowListState.firstVisibleItemScrollOffset == targetOffset
        ) {
            return@LaunchedEffect
        }
        if (targetIndex > 0 || targetOffset > 0) {
            verticalRowListState.scrollToItem(targetIndex, targetOffset)
        }
    }

    val activeRow = remember(carouselRows, rowByKey, activeRowKey) {
        val activeKey = activeRowKey
        if (activeKey == null) {
            null
        } else {
            rowByKey[activeKey] ?: carouselRows.firstOrNull()
        }
    }
    val clampedActiveItemIndex = remember(activeRow, activeItemIndex) {
        activeRow?.let { row ->
            activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        } ?: 0
    }

    LaunchedEffect(activeRow?.key, activeRow?.items?.size) {
        val row = activeRow ?: return@LaunchedEffect
        val clampedIndex = focusHolder.activeItemIndex.coerceIn(0, (row.items.size - 1).coerceAtLeast(0))
        if (focusHolder.activeItemIndex != clampedIndex) {
            focusHolder.activeItemIndex = clampedIndex
            activeItemIndex = clampedIndex
        }
        focusedItemByRow[row.key] = clampedIndex
    }

    val activeHeroItemKey = remember(activeRow, clampedActiveItemIndex) {
        val row = activeRow ?: return@remember null
        row.items.getOrNull(clampedActiveItemIndex)?.key ?: row.items.firstOrNull()?.key
    }
    val activeHeroPreviewKey = remember(activeRow, clampedActiveItemIndex) {
        val row = activeRow ?: return@remember null
        heroPreviewContentKey(
            row.items.getOrNull(clampedActiveItemIndex) ?: row.items.firstOrNull()
        )
    }
    val latestHeroRow by rememberUpdatedState(activeRow)
    val latestHeroIndex by rememberUpdatedState(clampedActiveItemIndex)
    LaunchedEffect(activeHeroItemKey, isVerticalRowsScrolling) {
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val targetHeroKey = activeHeroItemKey ?: return@LaunchedEffect
        val settleDelayMs = heroFocusSettleDelayMsRef.get()
        delay(settleDelayMs)
        if (isVerticalRowsScrolling) return@LaunchedEffect
        if (System.currentTimeMillis() - lastHeroNavigationAtMsRef.get() < settleDelayMs) return@LaunchedEffect
        val row = latestHeroRow ?: return@LaunchedEffect
        val latestKey = row.items.getOrNull(latestHeroIndex)?.key ?: row.items.firstOrNull()?.key
        if (latestKey != targetHeroKey) return@LaunchedEffect
        val latestHero = resolveActiveHeroPreview(row, latestHeroIndex)
        if (latestHero != null && heroItem != latestHero) {
            displayedHeroItemKey = latestKey
            heroItem = latestHero
        }
    }
    LaunchedEffect(activeHeroPreviewKey, isVerticalRowsScrolling) {
        if (isVerticalRowsScrolling) return@LaunchedEffect
        val latestHero = resolveActiveHeroPreview(latestHeroRow, latestHeroIndex) ?: return@LaunchedEffect
        if (heroItem != latestHero) {
            heroItem = latestHero
        }
    }
    val latestActiveRow by rememberUpdatedState(activeRow)
    val latestActiveItemIndex by rememberUpdatedState(clampedActiveItemIndex)
    val latestCarouselRows by rememberUpdatedState(carouselRows)
    val latestVerticalRowListState by rememberUpdatedState(verticalRowListState)
    DisposableEffect(Unit) {
        onDispose {
            val row = latestActiveRow
            val focusedRowIndex = row?.globalRowIndex ?: 0
            val catalogRowScrollStates = latestCarouselRows
                .filter { it.globalRowIndex >= 0 }
                .associate { rowState -> rowState.key to (focusedItemByRow[rowState.key] ?: 0) }

            onSaveFocusState(
                latestVerticalRowListState.firstVisibleItemIndex,
                latestVerticalRowListState.firstVisibleItemScrollOffset,
                focusedRowIndex,
                latestActiveItemIndex,
                catalogRowScrollStates
            )
        }
    }

    val portraitBaseWidth = contentState.posterCardWidthDp.dp
    val portraitBaseHeight = contentState.posterCardHeightDp.dp
    val modernPosterScale = if (useLandscapePosters) 1.34f else 1.08f
    val modernCatalogCardWidth = if (useLandscapePosters) {
        portraitBaseWidth * 1.24f * modernPosterScale
    } else {
        portraitBaseWidth * 0.84f * modernPosterScale
    }
    val modernCatalogCardHeight = if (useLandscapePosters) {
        modernCatalogCardWidth / 1.77f
    } else {
        portraitBaseHeight * 0.84f * modernPosterScale
    }
    val continueWatchingScale = 1.34f
    val continueWatchingCardWidth = portraitBaseWidth * 1.24f * continueWatchingScale
    val continueWatchingCardHeight = continueWatchingCardWidth / 1.77f

    BoxWithConstraints(
        modifier = Modifier.fillMaxSize()
    ) {
        val rowHorizontalPadding = 52.dp
        val posterCardCornerRadius = remember(contentState.posterCardCornerRadiusDp) { contentState.posterCardCornerRadiusDp.dp }
        val activeCarouselItem = remember(activeRow, clampedActiveItemIndex) {
            activeRow?.items?.getOrNull(clampedActiveItemIndex)
        }
        val activeItemId = activeCarouselItem?.metaPreview?.id
        val liveActiveHeroPreview = remember(activeRow, clampedActiveItemIndex) {
            resolveActiveHeroPreview(activeRow, clampedActiveItemIndex)
        }
        val resolvedHero = resolveDisplayedHeroPreview(
            displayedHeroItemKey = displayedHeroItemKey,
            activeHeroItemKey = activeHeroItemKey,
            displayedHeroPreview = heroItem,
            liveActiveHeroPreview = liveActiveHeroPreview
        )
        val activeRowFallbackBackdrop = remember(activeRow?.key, activeRow?.items) {
            activeRow?.items?.firstNotNullOfOrNull { item ->
                item.heroPreview.backdrop?.takeIf { it.isNotBlank() }
            }
        }
        val heroBackdrop = remember(heroItem, resolvedHero, activeRowFallbackBackdrop) {
            firstNonBlank(
                resolvedHero?.backdrop,
                resolvedHero?.imageUrl,
                resolvedHero?.poster,
                if (heroItem == null) activeRowFallbackBackdrop else null
            )
        }
        val heroTrailerItemId = focusedTrailerSelection?.itemId
        val heroTrailerPending = pendingHeroTrailerFocusKey == focusedTrailerSelection?.focusKey
        val unlockedTrailerForFocusedItem = unlockedTrailerFocusKey == focusedTrailerSelection?.focusKey
        val heroTrailerPreviewUrl = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
            unlockedTrailerForFocusedItem
        ) {
            heroTrailerItemId?.let { contentState.trailerPreviewUrls[it] }
        } else {
            null
        }
        val heroTrailerPreviewAudioUrl = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
            unlockedTrailerForFocusedItem
        ) {
            heroTrailerItemId?.let { contentState.trailerPreviewAudioUrls[it] }
        } else {
            null
        }
        val heroTrailerPreviewUserAgent = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
            unlockedTrailerForFocusedItem
        ) {
            heroTrailerItemId?.let { contentState.trailerPreviewUserAgents[it] }
        } else {
            null
        }
        val heroTrailerExternalUrl = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA &&
            unlockedTrailerForFocusedItem
        ) {
            heroTrailerItemId?.let { contentState.trailerPreviewExternalUrls[it] }
        } else {
            null
        }
        val heroTrailerActive = heroTrailerPending ||
            !heroTrailerPreviewUrl.isNullOrBlank() ||
            !heroTrailerExternalUrl.isNullOrBlank()
        val heroTrailerInternalPlaying = !heroTrailerPreviewUrl.isNullOrBlank()
        val fullscreenTrailerActive = heroTrailerFullscreenMode && heroTrailerInternalPlaying
        val fullscreenTextOverlayVisible = shouldShowModernHomeFullscreenTextOverlay(
            fullscreenTrailerActive = fullscreenTrailerActive,
            overlayTimedOut = fullscreenTrailerTextTimedOut
        )
        val heroFullscreenHintVisible = shouldShowModernHomeHeroFullscreenHint(
            heroTrailerPlaying = heroTrailerInternalPlaying,
            fullscreenTrailerActive = fullscreenTrailerActive,
            hintTimedOut = heroFullscreenHintTimedOut
        )
        val expandedTrailerItemId = focusedTrailerSelection?.itemId
        val expandedCardTrailerActive = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.EXPANDED_CARD &&
            !expandedCatalogFocusKey.isNullOrBlank() &&
            expandedTrailerItemId != null &&
            unlockedTrailerForFocusedItem
        ) {
            !contentState.trailerPreviewUrls[expandedTrailerItemId].isNullOrBlank() ||
                !contentState.trailerPreviewExternalUrls[expandedTrailerItemId].isNullOrBlank()
        } else {
            false
        }
        val internalHomeTrailerPlaying = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
        ) {
            heroTrailerInternalPlaying
        } else {
            val expandedId = expandedTrailerItemId
            !expandedCatalogFocusKey.isNullOrBlank() &&
                unlockedTrailerForFocusedItem &&
                expandedId != null &&
                !contentState.trailerPreviewUrls[expandedId].isNullOrBlank()
        }
        var previousHomeTrailerPlaying by remember { mutableStateOf(false) }
        val activeTrailerMuted = if (
            effectiveTrailerPlaybackTarget == com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA
        ) {
            resolveModernHomeHeroTrailerMuted(
                fullscreenTrailerActive = fullscreenTrailerActive
            )
        } else {
            contentState.focusedPosterBackdropTrailerMuted
        }
        LaunchedEffect(internalHomeTrailerPlaying, activeTrailerMuted) {
            onModernHomeTrailerPlaybackActiveChanged(
                shouldTreatModernHomeTrailerPlaybackAsActiveTime(
                    internalHomeTrailerPlaying = internalHomeTrailerPlaying,
                    trailerMuted = activeTrailerMuted
                )
            )
            if (internalHomeTrailerPlaying && !previousHomeTrailerPlaying) {
                onModernHomeTrailerPlaybackStarted()
            }
            previousHomeTrailerPlaying = internalHomeTrailerPlaying
        }
        DisposableEffect(Unit) {
            onDispose {
                onModernHomeTrailerPlaybackActiveChanged(false)
            }
        }
        BackHandler(
            enabled = shouldDismissModernHomeTrailerOnBack(
                heroTrailerActive = heroTrailerActive,
                expandedCardTrailerActive = expandedCardTrailerActive,
                fullscreenTrailerActive = fullscreenTrailerActive
            )
        ) {
            if (fullscreenTrailerActive) {
                heroTrailerFullscreenMode = false
                fullscreenTrailerTextTimedOut = false
                heroFullscreenHintTimedOut = false
                unlockedTrailerFocusKey = null
                pendingHeroTrailerFocusKey = null
                focusedTrailerSelection?.let { selection ->
                    pendingRowFocusKey = selection.rowKey
                    pendingRowFocusIndex = activeItemIndex
                    pendingRowFocusNonce++
                }
            } else {
                expansionInteractionNonce++
                expandedCatalogFocusKey = null
                expandedCatalogRowKey = null
                focusedCatalogSelection = null
            }
        }
        val catalogBottomPadding = 0.dp
        val heroToCatalogGap = 16.dp
        val rowTitleBottom = 14.dp
        val rowsViewportHeightFraction = if (useLandscapePosters) 0.49f else 0.52f
        val rowsViewportHeight = maxHeight * rowsViewportHeightFraction
        val localDensity = LocalDensity.current
        val verticalRowBringIntoViewSpec = remember(localDensity, defaultBringIntoViewSpec) {
            val topInsetPx = with(localDensity) { MODERN_ROW_HEADER_FOCUS_INSET.toPx() }
            @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
            object : BringIntoViewSpec {
                @Deprecated("Overrides deprecated BringIntoViewSpec.scrollAnimationSpec.")
                override val scrollAnimationSpec: AnimationSpec<Float> =
                    defaultBringIntoViewSpec.scrollAnimationSpec

                override fun calculateScrollDistance(
                    offset: Float,
                    size: Float,
                    containerSize: Float
                ): Float = offset - topInsetPx
            }
        }
        val bgColor = NexioColors.Background
        val contentFocusRequester = LocalContentFocusRequester.current
        val heroMediaWidthPx = remember(maxWidth, localDensity, heroTrailerFullscreenMode) {
            with(localDensity) {
                if (heroTrailerFullscreenMode) {
                    maxWidth.roundToPx()
                } else {
                    (maxWidth * 0.75f).roundToPx()
                }
            }
        }
        val heroMediaHeightPx = remember(maxHeight, localDensity, heroTrailerFullscreenMode) {
            with(localDensity) {
                if (heroTrailerFullscreenMode) {
                    maxHeight.roundToPx()
                } else {
                    (maxHeight * MODERN_HERO_BACKDROP_HEIGHT_FRACTION).roundToPx()
                }
            }
        }

        val heroMediaModifier = remember {
            Modifier
                .align(Alignment.TopEnd)
                .offset(x = 56.dp)
                .fillMaxWidth(0.75f)
                .fillMaxHeight(MODERN_HERO_BACKDROP_HEIGHT_FRACTION)
        }
        val effectiveHeroMediaModifier = if (heroTrailerFullscreenMode) {
            Modifier.fillMaxSize()
        } else {
            heroMediaModifier
        }
        val heroTitleModifier = if (heroTrailerFullscreenMode) {
            Modifier
                .align(Alignment.BottomStart)
                .padding(start = rowHorizontalPadding, end = 48.dp, bottom = 64.dp)
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        } else {
            Modifier
                .align(Alignment.BottomStart)
                .padding(
                    start = rowHorizontalPadding,
                    end = 48.dp,
                    bottom = catalogBottomPadding + rowsViewportHeight + heroToCatalogGap
                )
                .fillMaxWidth(MODERN_HERO_TEXT_WIDTH_FRACTION)
        }

        ModernHeroSection(
            heroBackdrop = heroBackdrop,
            trailerPreviewUrl = heroTrailerPreviewUrl,
            trailerPreviewAudioUrl = heroTrailerPreviewAudioUrl,
            trailerPreviewUserAgent = heroTrailerPreviewUserAgent,
            showLoadingIndicator = heroTrailerPending && heroTrailerPreviewUrl.isNullOrBlank(),
            showTextOverlay = !heroTrailerFullscreenMode || fullscreenTextOverlayVisible,
            showFullscreenHint = heroFullscreenHintVisible,
            fullscreenHintText = strPressUpForFullscreen,
            trailerMuted = resolveModernHomeHeroTrailerMuted(
                fullscreenTrailerActive = fullscreenTrailerActive
            ),
            onTrailerEnded = {
                val (endedState, focusRestore) = handleModernHomeTrailerEnded(
                    focusedTrailerFocusKey = focusedTrailerSelection?.focusKey,
                    activeItemIndex = activeItemIndex,
                    focusedTrailerRowKey = focusedTrailerSelection?.rowKey,
                    state = ModernHomeTrailerEndedState(
                        unlockedTrailerFocusKey = unlockedTrailerFocusKey,
                        pendingHeroTrailerFocusKey = pendingHeroTrailerFocusKey,
                        heroTrailerFullscreenMode = heroTrailerFullscreenMode,
                        fullscreenTrailerTextTimedOut = fullscreenTrailerTextTimedOut,
                        heroFullscreenHintTimedOut = heroFullscreenHintTimedOut
                    )
                )
                unlockedTrailerFocusKey = endedState.unlockedTrailerFocusKey
                pendingHeroTrailerFocusKey = endedState.pendingHeroTrailerFocusKey
                heroTrailerFullscreenMode = endedState.heroTrailerFullscreenMode
                fullscreenTrailerTextTimedOut = endedState.fullscreenTrailerTextTimedOut
                heroFullscreenHintTimedOut = endedState.heroFullscreenHintTimedOut
                focusRestore?.let { (rowKey, index) ->
                    pendingRowFocusKey = rowKey
                    pendingRowFocusIndex = index
                    pendingRowFocusNonce++
                }
            },
            onTrailerFirstFrameRendered = {
                heroFullscreenHintTimedOut = false
                heroFullscreenHintSessionNonce++
            },
            preview = resolvedHero,
            activeItemId = activeItemId,
            enrichingItemIdState = enrichingItemIdState,
            bgColor = bgColor,
            portraitMode = !useLandscapePosters,
            mediaModifier = effectiveHeroMediaModifier,
            requestWidthPx = heroMediaWidthPx,
            requestHeightPx = heroMediaHeightPx,
            modifier = heroTitleModifier
        )

        LaunchedEffect(heroTrailerPreviewUrl, heroTrailerFullscreenMode, heroTrailerItemId) {
            if (heroTrailerPreviewUrl.isNullOrBlank() || heroTrailerFullscreenMode) {
                heroFullscreenHintTimedOut = false
                if (heroTrailerPreviewUrl.isNullOrBlank()) {
                    heroFullscreenHintSessionNonce = 0
                }
            }
        }

        LaunchedEffect(heroTrailerItemId, heroTrailerExternalUrl, effectiveTrailerPlaybackTarget) {
            if (heroTrailerItemId == null || heroTrailerExternalUrl.isNullOrBlank()) return@LaunchedEffect
            if (effectiveTrailerPlaybackTarget != com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA) return@LaunchedEffect
            val launchKey = "$heroTrailerItemId|$heroTrailerExternalUrl"
            if (lastExternalTrailerLaunchKey == launchKey) return@LaunchedEffect
            lastExternalTrailerLaunchKey = launchKey
            runCatching {
                context.startActivity(
                    Intent(Intent.ACTION_VIEW, Uri.parse(heroTrailerExternalUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                )
            }
        }

        LaunchedEffect(heroFullscreenHintSessionNonce, heroTrailerFullscreenMode, heroTrailerPreviewUrl) {
            if (heroFullscreenHintSessionNonce <= 0 || heroTrailerFullscreenMode || heroTrailerPreviewUrl.isNullOrBlank()) {
                return@LaunchedEffect
            }
            heroFullscreenHintTimedOut = false
            delay(10_000L)
            if (!heroTrailerFullscreenMode && !heroTrailerPreviewUrl.isNullOrBlank()) {
                heroFullscreenHintTimedOut = true
            }
        }

        LaunchedEffect(heroTrailerFullscreenMode, heroTrailerPreviewUrl) {
            if (heroTrailerFullscreenMode && !heroTrailerPreviewUrl.isNullOrBlank()) {
                fullscreenTrailerTextTimedOut = false
                delay(10_000L)
                if (heroTrailerFullscreenMode && !heroTrailerPreviewUrl.isNullOrBlank()) {
                    fullscreenTrailerTextTimedOut = true
                }
            } else {
                fullscreenTrailerTextTimedOut = false
            }
        }

        LaunchedEffect(heroTrailerFullscreenMode) {
            if (heroTrailerFullscreenMode) {
                fullscreenTrailerFocusRequester.requestFocus()
            }
        }

        LaunchedEffect(heroTrailerPreviewUrl, heroTrailerFullscreenMode) {
            if (heroTrailerFullscreenMode && heroTrailerPreviewUrl.isNullOrBlank()) {
                heroTrailerFullscreenMode = false
                fullscreenTrailerTextTimedOut = false
            }
        }

        if (heroTrailerFullscreenMode) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(fullscreenTrailerFocusRequester)
                    .focusable()
                    .onPreviewKeyEvent { event ->
                        when (event.key) {
                            Key.DirectionUp,
                            Key.DirectionDown,
                            Key.DirectionLeft,
                            Key.DirectionRight,
                            Key.DirectionCenter,
                            Key.Enter,
                            Key.NumPadEnter -> true
                            else -> false
                        }
                    }
            )
        }

        val onPendingRowFocusCleared = remember {
            {
                pendingRowFocusKey = null
                pendingRowFocusIndex = null
            }
        }
        val onRowItemFocused = remember {
            { rowKey: String, index: Int, isContinueWatchingRow: Boolean ->
                val rowBecameActive = focusHolder.activeRowKey != rowKey
                val itemChanged = focusHolder.activeItemIndex != index
                if (rowBecameActive || itemChanged) {
                    val now = System.currentTimeMillis()
                    val timeSinceLastHeroNav = now - lastHeroNavigationAtMsRef.get()
                    heroFocusSettleDelayMsRef.set(if (
                        lastHeroNavigationAtMsRef.get() != 0L &&
                        timeSinceLastHeroNav in 1 until MODERN_HERO_RAPID_NAV_THRESHOLD_MS
                    ) {
                        MODERN_HERO_RAPID_NAV_SETTLE_MS
                    } else {
                        MODERN_HERO_FOCUS_DEBOUNCE_MS
                    })
                    lastHeroNavigationAtMsRef.set(now)
                }
                if (focusedItemByRow[rowKey] != index) {
                    focusedItemByRow[rowKey] = index
                }
                if (rowBecameActive) {
                    focusHolder.activeRowKey = rowKey
                    activeRowKey = rowKey
                }
                if (rowBecameActive || itemChanged) {
                    focusHolder.activeItemIndex = index
                    activeItemIndex = index
                }
                if (isContinueWatchingRow) {
                    if (lastFocusedContinueWatchingIndexRef.get() != index) {
                        lastFocusedContinueWatchingIndexRef.set(index)
                    }
                    if (focusedCatalogSelection != null) {
                        focusedCatalogSelection = null
                    }
                }
            }
        }
        val onContinueWatchingOptions = remember {
            { item: ContinueWatchingItem ->
                optionsItem = item
            }
        }
        val onCatalogSelectionFocused = remember {
            { selection: FocusedCatalogSelection ->
                if (focusedCatalogSelection != selection) {
                    focusedCatalogSelection = selection
                }
            }
        }
        val onBackdropInteraction = remember {
            {
                expansionInteractionNonce++
                Unit
            }
        }

        if (!heroTrailerFullscreenMode) {
            CompositionLocalProvider(LocalBringIntoViewSpec provides verticalRowBringIntoViewSpec) {
                LazyColumn(
                    state = verticalRowListState,
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(rowsViewportHeight)
                        .padding(bottom = catalogBottomPadding)
                        .focusRequester(contentFocusRequester)
                        .onPreviewKeyEvent { event ->
                            val native = event.nativeKeyEvent
                            if (native.action == AndroidKeyEvent.ACTION_DOWN && native.repeatCount > 0) {
                                val direction = modernHomeRepeatFocusDirectionForKeyCode(native.keyCode)
                                    ?: return@onPreviewKeyEvent false
                                val now = SystemClock.uptimeMillis()
                                if (now - lastKeyRepeatDispatchRef.get() < modernHomeRepeatThrottleMs(direction)) {
                                    return@onPreviewKeyEvent true
                                }
                                lastKeyRepeatDispatchRef.set(now)
                                focusManager.moveFocus(direction.toComposeFocusDirection())
                                return@onPreviewKeyEvent true
                            }
                            false
                        },
                    contentPadding = PaddingValues(bottom = rowsViewportHeight),
                    verticalArrangement = Arrangement.spacedBy(24.dp)
                ) {
                    itemsIndexed(
                        items = carouselRows,
                        key = { _, row -> row.key },
                        contentType = { _, row -> modernHomeRowContentType(row) }
                    ) { _, row ->
                        ModernRowSection(
                            row = row,
                            isActiveRow = activeRowKey == row.key,
                            isVerticalRowsScrolling = isVerticalRowsScrolling,
                            rowTitleBottom = rowTitleBottom,
                            defaultBringIntoViewSpec = defaultBringIntoViewSpec,
                            initialScrollIndex = focusState.catalogRowScrollStates[row.key] ?: 0,
                            uiCaches = uiCaches,
                            pendingRowFocusIndex = if (pendingRowFocusKey == row.key) pendingRowFocusIndex else null,
                            pendingRowFocusNonce = pendingRowFocusNonce,
                            onPendingRowFocusCleared = onPendingRowFocusCleared,
                            onRowItemFocused = onRowItemFocused,
                            useLandscapePosters = useLandscapePosters,
                            showLabels = contentState.posterLabelsEnabled,
                            posterCardCornerRadius = posterCardCornerRadius,
                            effectiveExpandEnabled = effectiveExpandEnabled,
                            expandedCatalogFocusKeyForRow = if (expandedCatalogRowKey == row.key) {
                                expandedCatalogFocusKey
                            } else {
                                null
                            },
                            focusedPosterBackdropTrailerPlaybackTarget = effectiveTrailerPlaybackTarget,
                            trailerPlaybackUnlockedFocusKey = unlockedTrailerFocusKey,
                            focusedPosterBackdropTrailerMuted =
                                contentState.focusedPosterBackdropTrailerMuted,
                            trailerPreviewUrls = contentState.trailerPreviewUrls,
                            trailerPreviewAudioUrls = contentState.trailerPreviewAudioUrls,
                            trailerPreviewUserAgents = contentState.trailerPreviewUserAgents,
                            trailerPreviewExternalUrls = contentState.trailerPreviewExternalUrls,
                            modernCatalogCardWidth = modernCatalogCardWidth,
                            modernCatalogCardHeight = modernCatalogCardHeight,
                            continueWatchingCardWidth = continueWatchingCardWidth,
                            continueWatchingCardHeight = continueWatchingCardHeight,
                            onContinueWatchingClick = onContinueWatchingClick,
                            onContinueWatchingOptions = onContinueWatchingOptions,
                            isCatalogItemWatched = isCatalogItemWatched,
                            onCatalogItemLongPress = onCatalogItemLongPress,
                            onItemFocus = onItemFocus,
                            onPreloadAdjacentItem = onPreloadAdjacentItem,
                            onCatalogSelectionFocused = onCatalogSelectionFocused,
                            onNavigateToDetail = onNavigateToDetail,
                            onLoadMoreCatalog = onLoadMoreCatalog,
                            canPromoteHeroTrailerToFullscreen = heroTrailerInternalPlaying &&
                                effectiveTrailerPlaybackTarget ==
                                com.nexio.tv.domain.model.FocusedPosterTrailerPlaybackTarget.HERO_MEDIA,
                            fullscreenTrailerActive = fullscreenTrailerActive,
                            onPromoteHeroTrailerToFullscreen = {
                                heroTrailerFullscreenMode = true
                            },
                            onBackdropInteraction = onBackdropInteraction
                        )
                    }
                }
            }
        }
    }

    val selectedOptionsItem = optionsItem
    if (selectedOptionsItem != null) {
        ContinueWatchingOptionsDialog(
            item = selectedOptionsItem,
            onDismiss = { optionsItem = null },
            onRemove = {
                val targetIndex = if (contentState.continueWatchingItems.size <= 1) {
                    null
                } else {
                    minOf(lastFocusedContinueWatchingIndexRef.get(), contentState.continueWatchingItems.size - 2)
                        .coerceAtLeast(0)
                }
                pendingRowFocusKey = if (targetIndex != null) "continue_watching" else null
                pendingRowFocusIndex = targetIndex
                pendingRowFocusNonce++
                onRemoveContinueWatching(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.season(),
                    selectedOptionsItem.episode(),
                    selectedOptionsItem is ContinueWatchingItem.NextUp
                )
                optionsItem = null
            },
            onMarkAsWatched = {
                onMarkContinueWatchingWatched(selectedOptionsItem)
                optionsItem = null
            },
            showManualStreamSelection = shouldShowContinueWatchingManualStreamSelection(
                deterministicAutoplayEnabled = contentState.deterministicAutoplayEnabled,
                item = selectedOptionsItem
            ),
            onPlayWithManualStreamSelection = {
                onContinueWatchingManualStreamSelection(selectedOptionsItem)
                optionsItem = null
            },
            onDetails = {
                onNavigateToDetail(
                    selectedOptionsItem.contentId(),
                    selectedOptionsItem.contentType(),
                    ""
                )
                optionsItem = null
            },
            onCheckIn = onCheckInContinueWatching?.let { callback ->
                {
                    callback(selectedOptionsItem)
                    optionsItem = null
                }
            },
            isInWatchlist = cwWatchlistMembership[selectedOptionsItem.contentId()] == true,
            onToggleLibrary = onToggleContinueWatchingLibrary?.let { callback ->
                {
                    callback(selectedOptionsItem)
                    optionsItem = null
                }
            },
            onStartFromBeginning = {
                onContinueWatchingStartFromBeginning(selectedOptionsItem)
                optionsItem = null
            }
        )
    }
}

@Composable
private fun ModernHeroSection(
    heroBackdrop: String?,
    trailerPreviewUrl: String?,
    trailerPreviewAudioUrl: String?,
    trailerPreviewUserAgent: String?,
    showLoadingIndicator: Boolean,
    showTextOverlay: Boolean,
    showFullscreenHint: Boolean,
    fullscreenHintText: String,
    trailerMuted: Boolean,
    onTrailerEnded: () -> Unit,
    onTrailerFirstFrameRendered: () -> Unit,
    preview: HeroPreview?,
    activeItemId: String?,
    enrichingItemIdState: State<String?>,
    bgColor: androidx.compose.ui.graphics.Color,
    portraitMode: Boolean,
    mediaModifier: Modifier,
    requestWidthPx: Int,
    requestHeightPx: Int,
    modifier: Modifier = Modifier
) {
    val enrichingItemId = enrichingItemIdState.value
    val enrichmentActive = activeItemId != null && activeItemId == enrichingItemId
    LaunchedEffect(preview, activeItemId, enrichingItemId, enrichmentActive) {
        Log.d(
            MODERN_HOME_CONTENT_LOG_TAG,
            "ModernHeroSection activeItemId=$activeItemId enrichingItemId=$enrichingItemId enrichmentActive=$enrichmentActive title=${preview?.title} imdb=${preview?.imdbText} tomatoes=${preview?.tomatoesText}"
        )
    }
    ModernHeroMediaLayer(
        heroBackdrop = heroBackdrop,
        trailerPreviewUrl = trailerPreviewUrl,
        trailerPreviewAudioUrl = trailerPreviewAudioUrl,
        trailerPreviewUserAgent = trailerPreviewUserAgent,
        showLoadingIndicator = showLoadingIndicator,
        trailerMuted = trailerMuted,
        showFullscreenHint = showFullscreenHint,
        fullscreenHintText = fullscreenHintText,
        onTrailerEnded = onTrailerEnded,
        onTrailerFirstFrameRendered = onTrailerFirstFrameRendered,
        enrichmentActive = enrichmentActive,
        modifier = mediaModifier,
        requestWidthPx = requestWidthPx,
        requestHeightPx = requestHeightPx
    )
    ModernHeroGradientLayer(
        bgColor = bgColor,
        modifier = mediaModifier
    )
    if (showTextOverlay) {
        HeroTitleBlock(
            preview = preview,
            enrichmentActive = enrichmentActive,
            portraitMode = portraitMode,
            modifier = modifier
        )
    }
}

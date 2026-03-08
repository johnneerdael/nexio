@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.stream

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import android.view.KeyEvent
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import coil.request.ImageRequest
import androidx.compose.ui.platform.LocalContext
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.FilterChip
import androidx.tv.material3.FilterChipDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import com.nexio.tv.core.player.ExternalPlayerLauncher
import com.nexio.tv.core.stream.StreamCardModel
import com.nexio.tv.data.local.PlayerPreference
import com.nexio.tv.domain.model.Stream
import com.nexio.tv.ui.components.SourceChipItem
import com.nexio.tv.ui.components.SourceChipStatus
import com.nexio.tv.ui.components.SourceStatusFilterChip
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.components.StreamsSkeletonList
import com.nexio.tv.ui.screens.player.LoadingOverlay
import com.nexio.tv.ui.theme.NexioTheme
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.delay as coroutineDelay
import kotlinx.coroutines.launch as coroutineLaunch
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R

private fun applyDither(bmp: android.graphics.Bitmap) {
    val pixels = IntArray(bmp.width * bmp.height)
    bmp.getPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
    val rng = java.util.Random(0)
    for (i in pixels.indices) {
        val p = pixels[i]
        val a = (p ushr 24) and 0xFF
        val r = (p ushr 16) and 0xFF
        val g = (p ushr 8) and 0xFF
        val b = p and 0xFF
        val noise = rng.nextInt(3) - 1
        pixels[i] = ((a shl 24) or
            ((r + noise).coerceIn(0, 255) shl 16) or
            ((g + noise).coerceIn(0, 255) shl 8) or
            (b + noise).coerceIn(0, 255))
    }
    bmp.setPixels(pixels, 0, bmp.width, 0, 0, bmp.width, bmp.height)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
fun StreamScreen(
    viewModel: StreamScreenViewModel = hiltViewModel(),
    onBackPress: () -> Unit,
    onStreamSelected: (StreamPlaybackInfo) -> Unit,
    onAutoPlayResolved: (StreamPlaybackInfo) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val playerPreference by viewModel.playerPreference.collectAsStateWithLifecycle(
        initialValue = PlayerPreference.INTERNAL
    )
    val lifecycleOwner = LocalLifecycleOwner.current
    val context = LocalContext.current
    var focusedStreamIndex by rememberSaveable { mutableStateOf(0) }
    var restoreFocusedStream by rememberSaveable { mutableStateOf(false) }
    var pendingRestoreOnResume by rememberSaveable { mutableStateOf(false) }
    var showPlayerChoiceDialog by remember { mutableStateOf(false) }
    var pendingPlaybackInfo by remember { mutableStateOf<StreamPlaybackInfo?>(null) }

    fun routePlayback(playbackInfo: StreamPlaybackInfo) {
        when (playerPreference) {
            PlayerPreference.INTERNAL -> {
                onStreamSelected(playbackInfo.copy(playerBackend = PlayerPreference.INTERNAL))
            }
            PlayerPreference.LIBMPV -> {
                onStreamSelected(playbackInfo.copy(playerBackend = PlayerPreference.LIBMPV))
            }
            PlayerPreference.EXTERNAL -> {
                playbackInfo.url?.let { url ->
                    ExternalPlayerLauncher.launch(
                        context = context,
                        url = url,
                        title = playbackInfo.title,
                        headers = playbackInfo.headers
                    )
                }
            }
            PlayerPreference.ASK_EVERY_TIME -> {
                pendingPlaybackInfo = playbackInfo
                showPlayerChoiceDialog = true
            }
        }
    }

    fun routeAutoPlay(playbackInfo: StreamPlaybackInfo) {
        if (uiState.isDirectAutoPlayFlow) {
            onAutoPlayResolved(playbackInfo)
            return
        } else {
            pendingRestoreOnResume = true
            routePlayback(playbackInfo)
            viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
        }
    }

    BackHandler {
        onBackPress()
    }

    LaunchedEffect(uiState.autoPlayStream) {
        val stream = uiState.autoPlayStream ?: return@LaunchedEffect
        val playbackInfo = viewModel.getStreamForPlayback(stream)
        if (playbackInfo.url != null) {
            routeAutoPlay(playbackInfo)
        }
    }

    LaunchedEffect(uiState.autoPlayPlaybackInfo) {
        val playbackInfo = uiState.autoPlayPlaybackInfo ?: return@LaunchedEffect
        if (playbackInfo.url != null) {
            routeAutoPlay(playbackInfo)
        }
    }

    DisposableEffect(lifecycleOwner, pendingRestoreOnResume) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME && pendingRestoreOnResume) {
                restoreFocusedStream = true
                pendingRestoreOnResume = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background)
    ) {
        // Full screen backdrop
        StreamBackdrop(
            backdrop = uiState.backdrop ?: uiState.poster,
            isLoading = uiState.isLoading
        )

        if (uiState.showDirectAutoPlayOverlay) {
            LoadingOverlay(
                visible = true,
                backdropUrl = uiState.backdrop ?: uiState.poster,
                logoUrl = uiState.logo,
                title = uiState.title,
                message = if (uiState.directAutoPlayMessage != null) {
                    stringResource(R.string.stream_finding_source)
                } else {
                    null
                },
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Content overlay
            Row(
                modifier = Modifier.fillMaxSize()
            ) {
                // Left side - Title/Logo (centered vertically)
                LeftContentSection(
                    title = uiState.title,
                    logo = uiState.logo,
                    isEpisode = uiState.isEpisode,
                    season = uiState.season,
                    episode = uiState.episode,
                    episodeName = uiState.episodeName,
                    runtime = uiState.runtime,
                    genres = uiState.genres,
                    year = uiState.year,
                    modifier = Modifier
                        .weight(0.4f)
                        .fillMaxHeight()
                )

                // Right side - Streams container
                RightStreamSection(
                    isLoading = uiState.isLoading,
                    error = uiState.error,
                    streams = uiState.presentedStreams,
                    availableAddons = uiState.availableAddons,
                    sourceChips = uiState.sourceChips,
                    selectedAddonFilter = uiState.selectedAddonFilter,
                    showAddonFilters = uiState.showAddonFilters,
                    onAddonFilterSelected = { viewModel.onEvent(StreamScreenEvent.OnAddonFilterSelected(it)) },
                    onStreamSelected = { item ->
                        val stream = item.stream
                        val currentIndex = uiState.presentedStreams.indexOfFirst {
                            it.stream.url == stream.url &&
                                it.stream.infoHash == stream.infoHash &&
                                it.stream.ytId == stream.ytId &&
                                it.stream.addonName == stream.addonName
                        }
                        if (currentIndex >= 0) {
                            focusedStreamIndex = currentIndex
                        }
                        val playbackInfo = viewModel.getStreamForPlayback(stream)
                        pendingRestoreOnResume = true
                        viewModel.onEvent(StreamScreenEvent.OnAutoPlayConsumed)
                        routePlayback(playbackInfo)
                    },
                    focusedStreamIndex = focusedStreamIndex,
                    shouldRestoreFocusedStream = restoreFocusedStream,
                    onRestoreFocusedStreamHandled = { restoreFocusedStream = false },
                    onRetry = { viewModel.onEvent(StreamScreenEvent.OnRetry) },
                    modifier = Modifier
                        .weight(0.6f)
                        .fillMaxHeight()
                )
            }
        }

        // Player choice dialog for "Ask every time" preference
        if (showPlayerChoiceDialog && pendingPlaybackInfo != null) {
            PlayerChoiceDialog(
                onInternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let {
                        onStreamSelected(it.copy(playerBackend = PlayerPreference.INTERNAL))
                    }
                    pendingPlaybackInfo = null
                },
                onLibmpvSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let {
                        onStreamSelected(it.copy(playerBackend = PlayerPreference.LIBMPV))
                    }
                    pendingPlaybackInfo = null
                },
                onExternalSelected = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo?.let { info ->
                        info.url?.let { url ->
                            ExternalPlayerLauncher.launch(
                                context = context,
                                url = url,
                                title = info.title,
                                headers = info.headers
                            )
                        }
                    }
                    pendingPlaybackInfo = null
                },
                onDismiss = {
                    showPlayerChoiceDialog = false
                    pendingPlaybackInfo = null
                }
            )
        }
    }
}

@Composable
private fun StreamBackdrop(
    backdrop: String?,
    isLoading: Boolean
) {
    val context = LocalContext.current
    val configuration = androidx.compose.ui.platform.LocalConfiguration.current
    val density = androidx.compose.ui.platform.LocalDensity.current
    val backgroundColor = NexioColors.Background
    val widthPx = remember(configuration, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val heightPx = remember(configuration, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }.coerceAtLeast(1)
    }
    val backdropModel = remember(context, backdrop) {
        backdrop?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val alpha by animateFloatAsState(
        targetValue = if (isLoading) 0.3f else 0.5f,
        animationSpec = tween(500),
        label = "backdrop_alpha"
    )
    val leftGradientBitmap = remember(backgroundColor, widthPx) {
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(widthPx, 2, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val shader = android.graphics.LinearGradient(
            0f, 0f, widthPx * 0.65f, 0f,
            intArrayOf(
                backgroundColor.toArgb(),
                backgroundColor.copy(alpha = 0.92f).toArgb(),
                backgroundColor.copy(alpha = 0.78f).toArgb(),
                backgroundColor.copy(alpha = 0.58f).toArgb(),
                backgroundColor.copy(alpha = 0.36f).toArgb(),
                backgroundColor.copy(alpha = 0.16f).toArgb(),
                backgroundColor.copy(alpha = 0.05f).toArgb(),
                transparent
            ),
            floatArrayOf(0f, 0.12f, 0.26f, 0.44f, 0.62f, 0.78f, 0.90f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, widthPx.toFloat(), 2f, android.graphics.Paint().apply {
            this.shader = shader
            isDither = true
        })
        applyDither(bmp)
        bmp.asImageBitmap()
    }
    val rightGradientBitmap = remember(backgroundColor, heightPx) {
        val transparent = backgroundColor.copy(alpha = 0f).toArgb()
        val bmp = android.graphics.Bitmap.createBitmap(2, heightPx, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val startX = widthPx * 0.35f
        val shader = android.graphics.LinearGradient(
            startX, 0f, widthPx.toFloat(), 0f,
            intArrayOf(
                transparent,
                backgroundColor.copy(alpha = 0.05f).toArgb(),
                backgroundColor.copy(alpha = 0.16f).toArgb(),
                backgroundColor.copy(alpha = 0.36f).toArgb(),
                backgroundColor.copy(alpha = 0.58f).toArgb(),
                backgroundColor.copy(alpha = 0.78f).toArgb(),
                backgroundColor.copy(alpha = 0.92f).toArgb(),
                backgroundColor.toArgb()
            ),
            floatArrayOf(0f, 0.10f, 0.22f, 0.38f, 0.56f, 0.74f, 0.88f, 1f),
            android.graphics.Shader.TileMode.CLAMP
        )
        canvas.drawRect(0f, 0f, 2f, heightPx.toFloat(), android.graphics.Paint().apply {
            this.shader = shader
            isDither = true
        })
        applyDither(bmp)
        bmp.asImageBitmap()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // Backdrop image
        if (backdropModel != null) {
            AsyncImage(
                model = backdropModel,
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .drawWithCache {
                    onDrawBehind {
                        drawRect(color = backgroundColor.copy(alpha = alpha), size = size)
                        drawImage(
                            leftGradientBitmap,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                        )
                        drawImage(
                            rightGradientBitmap,
                            dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt())
                        )
                    }
                }
        )
    }
}

@Composable
private fun LeftContentSection(
    title: String,
    logo: String?,
    isEpisode: Boolean,
    season: Int?,
    episode: Int?,
    episodeName: String?,
    runtime: Int?,
    genres: String?,
    year: String?,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var logoLoadFailed by remember(logo) { mutableStateOf(false) }
    val logoModel = remember(context, logo) {
        logo?.let { image ->
            ImageRequest.Builder(context)
                .data(image)
                .crossfade(false)
                .build()
        }
    }
    val infoText = remember(genres, year) {
        listOfNotNull(genres, year).joinToString(" • ")
    }
    Box(
        modifier = modifier.padding(start = 48.dp, end = 24.dp),
        contentAlignment = Alignment.CenterStart
    ) {
        Column(
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.fillMaxWidth(0.8f)
        ) {
            if (logoModel != null && !logoLoadFailed) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = title,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.Center
                )
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.displaySmall,
                    color = NexioColors.TextPrimary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center
                )
            }

            // Show episode info or movie info
            if (isEpisode && season != null && episode != null) {
                // Episode info
                Text(
                    text = "S$season E$episode",
                    style = MaterialTheme.typography.titleLarge,
                    color = NexioTheme.extendedColors.textSecondary,
                    textAlign = TextAlign.Center
                )
                if (episodeName != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = episodeName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
                if (runtime != null) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "${runtime}m",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexioTheme.extendedColors.textSecondary,
                        textAlign = TextAlign.Center
                    )
                }
            } else {
                // Movie info - genres and year
                if (infoText.isNotEmpty()) {
                    Text(
                        text = infoText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun RightStreamSection(
    isLoading: Boolean,
    error: String?,
    streams: List<StreamCardModel>,
    availableAddons: List<String>,
    sourceChips: List<SourceChipItem>,
    selectedAddonFilter: String?,
    showAddonFilters: Boolean,
    onAddonFilterSelected: (String?) -> Unit,
    onStreamSelected: (StreamCardModel) -> Unit,
    focusedStreamIndex: Int,
    shouldRestoreFocusedStream: Boolean,
    onRestoreFocusedStreamHandled: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier
) {
    var enter by remember { mutableStateOf(false) }
    var shouldFocusFirstStream by remember { mutableStateOf(false) }
    var wasLoading by remember { mutableStateOf(true) }
    var listHasFocus by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var focusJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    val orderedAddonNames = remember(availableAddons, sourceChips) {
        buildList {
            addAll(availableAddons)
            sourceChips.forEach { if (it.name !in this) add(it.name) }
        }
    }
    val chipFocusRequesters = remember(orderedAddonNames.size) {
        List(orderedAddonNames.size + 1) { FocusRequester() }
    }
    fun onAddonFilterSelectedGuarded(addon: String?) {
        onAddonFilterSelected(addon)
        val idx = if (addon == null) 0 else orderedAddonNames.indexOf(addon) + 1
        focusJob?.cancel()
        focusJob = scope.coroutineLaunch {
            withFrameNanos {}
            if (!listHasFocus && idx >= 0 && idx < chipFocusRequesters.size) {
                try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
            }
        }
    }

    LaunchedEffect(Unit) {
        enter = true
    }
    LaunchedEffect(isLoading, streams.size) {
        if (wasLoading && !isLoading && streams.isNotEmpty()) {
            shouldFocusFirstStream = true
        }
        wasLoading = isLoading
    }

    Column(
        modifier = modifier
            .padding(top = 48.dp, end = 48.dp, bottom = 48.dp)
    ) {
        val chipRowHeight = 56.dp

        // Addon filter chips
        Box(modifier = Modifier.height(chipRowHeight)) {
            androidx.compose.animation.AnimatedVisibility(
                visible = showAddonFilters && (sourceChips.isNotEmpty() || (!isLoading && availableAddons.isNotEmpty())),
                enter = fadeIn(animationSpec = tween(300)),
                exit = fadeOut(animationSpec = tween(300))
            ) {
                AddonFilterChips(
                    addons = availableAddons,
                    sourceChips = sourceChips,
                    selectedAddon = selectedAddonFilter,
                    onAddonSelected = { onAddonFilterSelectedGuarded(it) },
                    focusRequesters = chipFocusRequesters,
                    orderedNames = orderedAddonNames
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        androidx.compose.animation.AnimatedVisibility(
            visible = enter,
            enter = fadeIn(animationSpec = tween(260)) +
                slideInHorizontally(
                    animationSpec = tween(260),
                    initialOffsetX = { fullWidth -> (fullWidth * 0.06f).toInt() }
                ),
            exit = fadeOut(animationSpec = tween(120))
        ) {
            // Content area
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(16.dp))
                    .background(NexioColors.BackgroundCard.copy(alpha = 0.5f)),
                contentAlignment = Alignment.Center
            ) {
                when {
                    isLoading -> {
                        LoadingState()
                    }
                    error != null -> {
                        ErrorState(
                            message = error,
                            onRetry = onRetry
                        )
                    }
                    streams.isEmpty() -> {
                        EmptyState()
                    }
                    else -> {
                        StreamsList(
                            streams = streams,
                            onStreamSelected = onStreamSelected,
                            focusedStreamIndex = focusedStreamIndex,
                            shouldRestoreFocusedStream = shouldRestoreFocusedStream,
                            onRestoreFocusedStreamHandled = onRestoreFocusedStreamHandled,
                            requestInitialFocus = shouldFocusFirstStream,
                            onInitialFocusConsumed = { shouldFocusFirstStream = false },
                            availableAddons = availableAddons,
                            selectedAddonFilter = selectedAddonFilter,
                            onAddonFilterSelected = { onAddonFilterSelectedGuarded(it) },
                            chipFocusRequesters = chipFocusRequesters,
                            orderedAddonNames = orderedAddonNames,
                            onFocusChanged = { listHasFocus = it }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun AddonFilterChips(
    addons: List<String>,
    sourceChips: List<SourceChipItem>,
    selectedAddon: String?,
    onAddonSelected: (String?) -> Unit,
    focusRequesters: List<FocusRequester>,
    orderedNames: List<String>
) {
    val chipMap = sourceChips.associateBy { it.name }
    var chipRowHasFocus by remember { mutableStateOf(false) }
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
        modifier = Modifier
            .onFocusChanged { chipRowHasFocus = it.hasFocus }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != android.view.KeyEvent.ACTION_DOWN) return@onKeyEvent false
                val allOptions = listOf<String?>(null) + orderedNames
                val currentIdx = allOptions.indexOf(selectedAddon)
                when (event.key) {
                    androidx.compose.ui.input.key.Key.DirectionLeft -> {
                        if (currentIdx > 0) { onAddonSelected(allOptions[currentIdx - 1]); true } else false
                    }
                    androidx.compose.ui.input.key.Key.DirectionRight -> {
                        if (currentIdx < allOptions.lastIndex) { onAddonSelected(allOptions[currentIdx + 1]); true } else false
                    }
                    else -> false
                }
            }
    ) {
        item {
            SourceStatusFilterChip(
                name = "All",
                isSelected = selectedAddon == null,
                status = SourceChipStatus.SUCCESS,
                isSelectable = true,
                onClick = { onAddonSelected(null) },
                modifier = Modifier
                    .focusRequester(focusRequesters[0])
                    .focusProperties { canFocus = selectedAddon == null || chipRowHasFocus }
            )
        }

        items(orderedNames.size) { i ->
            val addon = orderedNames[i]
            val chipStatus = chipMap[addon]?.status ?: SourceChipStatus.SUCCESS
            val isSelectable = addon in addons && chipStatus == SourceChipStatus.SUCCESS
            SourceStatusFilterChip(
                name = addon,
                isSelected = selectedAddon == addon,
                status = chipStatus,
                isSelectable = isSelectable,
                onClick = { if (isSelectable) onAddonSelected(addon) },
                modifier = Modifier.focusRequester(focusRequesters[i + 1])
            )
        }
    }
}

@Composable
private fun LoadingState() {
    StreamsSkeletonList()
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun ErrorState(
    message: String,
    onRetry: () -> Unit
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            modifier = Modifier.size(48.dp),
            tint = NexioColors.Error
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = NexioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(24.dp))

        var isFocused by remember { mutableStateOf(false) }
        Card(
            onClick = onRetry,
            modifier = Modifier.onFocusChanged { isFocused = it.isFocused },
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundCard,
                focusedContainerColor = NexioColors.Secondary
            ),
            border = CardDefaults.border(
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(8.dp)
                )
            ),
            shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
            scale = CardDefaults.scale(focusedScale = 1.02f)
        ) {
            Text(
                text = stringResource(R.string.stream_retry),
                style = MaterialTheme.typography.labelLarge,
                color = if (isFocused) NexioColors.OnSecondary else NexioColors.TextPrimary,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 10.dp)
            )
        }
    }
}

@Composable
private fun EmptyState() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.padding(32.dp)
    ) {
        Text(
            text = stringResource(R.string.stream_no_streams),
            style = MaterialTheme.typography.bodyLarge,
            color = NexioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = stringResource(R.string.stream_no_streams_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = NexioTheme.extendedColors.textSecondary,
            textAlign = TextAlign.Center
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamsList(
    streams: List<StreamCardModel>,
    onStreamSelected: (StreamCardModel) -> Unit,
    focusedStreamIndex: Int = 0,
    shouldRestoreFocusedStream: Boolean = false,
    onRestoreFocusedStreamHandled: () -> Unit = {},
    requestInitialFocus: Boolean = false,
    onInitialFocusConsumed: () -> Unit = {},
    availableAddons: List<String> = emptyList(),
    selectedAddonFilter: String? = null,
    onAddonFilterSelected: (String?) -> Unit = {},
    chipFocusRequesters: List<FocusRequester> = emptyList(),
    orderedAddonNames: List<String> = emptyList(),
    onFocusChanged: (Boolean) -> Unit = {}
) {
    val firstCardFocusRequester = remember { FocusRequester() }
    val restoreFocusRequester = remember { FocusRequester() }
    val firstStreamKey = streams.firstOrNull()?.let { first ->
        "${first.stream.addonName}_${first.stream.url ?: first.stream.infoHash ?: first.stream.ytId ?: "unknown"}"
    }

    LaunchedEffect(requestInitialFocus, firstStreamKey) {
        if (!requestInitialFocus || streams.isEmpty()) return@LaunchedEffect
        repeat(2) { withFrameNanos { } }
        try {
            firstCardFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onInitialFocusConsumed()
    }

    LaunchedEffect(shouldRestoreFocusedStream, focusedStreamIndex, streams.size) {
        if (!shouldRestoreFocusedStream) return@LaunchedEffect
        if (streams.isEmpty()) {
            onRestoreFocusedStreamHandled()
            return@LaunchedEffect
        }
        repeat(2) { withFrameNanos { } }
        try {
            restoreFocusRequester.requestFocus()
        } catch (_: Exception) {
        }
        onRestoreFocusedStreamHandled()
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .onFocusChanged { onFocusChanged(it.hasFocus) }
            .onKeyEvent { event ->
                if (event.nativeKeyEvent.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                if (availableAddons.isEmpty()) return@onKeyEvent false
                val allOptions = listOf<String?>(null) + availableAddons
                val currentIdx = allOptions.indexOf(selectedAddonFilter)
                when (event.key) {
                    Key.DirectionLeft -> {
                        if (currentIdx > 0) { onAddonFilterSelected(allOptions[currentIdx - 1]); true } else false
                    }
                    Key.DirectionRight -> {
                        if (currentIdx < allOptions.lastIndex) { onAddonFilterSelected(allOptions[currentIdx + 1]); true } else false
                    }
                    else -> false
                }
            },
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
    ) {
        itemsIndexed(streams, key = { index, item ->
            "${item.stream.addonName}_${item.stream.url ?: item.stream.infoHash ?: item.stream.ytId ?: "unknown"}_$index"
        }) { index, item ->
            StreamCard(
                item = item,
                onClick = { onStreamSelected(item) },
                focusRequester = when {
                    shouldRestoreFocusedStream && index == focusedStreamIndex.coerceIn(0, (streams.lastIndex).coerceAtLeast(0)) -> restoreFocusRequester
                    index == 0 -> firstCardFocusRequester
                    else -> null
                },
                onUpKey = if (index == 0 && chipFocusRequesters.isNotEmpty()) {{
                    val idx = if (selectedAddonFilter == null) 0
                              else orderedAddonNames.indexOf(selectedAddonFilter) + 1
                    if (idx >= 0 && idx < chipFocusRequesters.size) {
                        try { chipFocusRequesters[idx].requestFocus() } catch (_: Exception) {}
                    }
                }} else null
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun StreamCard(
    item: StreamCardModel,
    onClick: () -> Unit,
    focusRequester: FocusRequester? = null,
    onUpKey: (() -> Unit)? = null
) {
    val stream = item.stream
    val context = LocalContext.current
    val streamName = remember(item) { item.title }
    val streamSubtitle = remember(item) { item.subtitle }
    val detailLines = remember(item) { item.detailLines }
    val addonLogoModel = remember(context, stream.addonLogo) {
        stream.addonLogo?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(false)
                .build()
        }
    }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .then(if (onUpKey != null) Modifier.onKeyEvent { event ->
                if (event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN && event.key == Key.DirectionUp) {
                    onUpKey(); true
                } else false
            } else Modifier),
        colors = CardDefaults.colors(
            containerColor = NexioColors.BackgroundElevated,
            focusedContainerColor = NexioColors.BackgroundElevated
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.08f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = streamName,
                    style = MaterialTheme.typography.titleMedium,
                    color = NexioColors.TextPrimary
                )

                streamSubtitle?.takeIf { it != streamName }?.let { subtitle ->
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioTheme.extendedColors.textSecondary
                    )
                }

                detailLines.forEachIndexed { index, detail ->
                    Text(
                        text = detail,
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioTheme.extendedColors.textSecondary,
                        maxLines = if (index == detailLines.lastIndex) 1 else 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (stream.isTorrent()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_torrent), color = NexioColors.Secondary)
                    }
                    if (stream.isYouTube()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_youtube), color = Color(0xFFFF0000))
                    }
                    if (stream.isExternal()) {
                        StreamTypeChip(text = stringResource(R.string.stream_type_external), color = NexioColors.Primary)
                    }
                }
            }

            Column(
                horizontalAlignment = Alignment.End
            ) {
                if (addonLogoModel != null) {
                    AsyncImage(
                        model = addonLogoModel,
                        contentDescription = stream.addonName,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(4.dp)),
                        contentScale = ContentScale.Fit
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stream.addonName,
                    style = MaterialTheme.typography.labelSmall,
                    color = NexioTheme.extendedColors.textTertiary,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun StreamTypeChip(
    text: String,
    color: Color
) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(color.copy(alpha = 0.2f))
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color
        )
    }
}

@Composable
private fun PlayerChoiceDialog(
    onInternalSelected: () -> Unit,
    onLibmpvSelected: () -> Unit,
    onExternalSelected: () -> Unit,
    onDismiss: () -> Unit
) {
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(16.dp))
                .background(NexioColors.BackgroundCard)
        ) {
            Column(
                modifier = Modifier
                    .width(400.dp)
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.stream_player_picker_title),
                    style = MaterialTheme.typography.headlineSmall,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )

                Spacer(modifier = Modifier.height(24.dp))

                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    var internalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onInternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester)
                            .onFocusChanged { internalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NexioColors.BackgroundElevated,
                            focusedContainerColor = NexioColors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NexioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_internal),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (internalFocused) NexioColors.OnSecondary else NexioColors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    var libmpvFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onLibmpvSelected,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { libmpvFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NexioColors.BackgroundElevated,
                            focusedContainerColor = NexioColors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NexioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_libmpv),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (libmpvFocused) NexioColors.OnSecondary else NexioColors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }

                    var externalFocused by remember { mutableStateOf(false) }
                    Card(
                        onClick = onExternalSelected,
                        modifier = Modifier
                            .weight(1f)
                            .onFocusChanged { externalFocused = it.isFocused },
                        colors = CardDefaults.colors(
                            containerColor = NexioColors.BackgroundElevated,
                            focusedContainerColor = NexioColors.Secondary
                        ),
                        border = CardDefaults.border(
                            focusedBorder = Border(
                                border = BorderStroke(2.dp, NexioColors.FocusRing),
                                shape = RoundedCornerShape(12.dp)
                            )
                        ),
                        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
                        scale = CardDefaults.scale(focusedScale = 1.05f)
                    ) {
                        Text(
                            text = stringResource(R.string.stream_player_external),
                            style = MaterialTheme.typography.titleMedium,
                            color = if (externalFocused) NexioColors.OnSecondary else NexioColors.TextPrimary,
                            modifier = Modifier
                                .padding(horizontal = 16.dp, vertical = 14.dp)
                                .fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}

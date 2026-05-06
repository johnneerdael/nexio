package com.nexio.tv.ui.screens.detail

import android.view.KeyEvent as AndroidKeyEvent
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.IconButton
import androidx.tv.material3.IconButtonDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.nexio.tv.R
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.MDBListRatings
import com.nexio.tv.domain.model.Video
import com.nexio.tv.domain.model.NextToWatch
import com.nexio.tv.domain.model.orDefault
import com.nexio.tv.ui.theme.NexioColors
import com.nexio.tv.ui.theme.NexioTheme
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.painter.Painter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nexio.tv.core.artwork.toCoilModelOrNull
import com.nexio.tv.core.artwork.toSafeArtworkCoilModelOrNull

private const val HERO_DESCRIPTION_MAX_LINES = 6

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
internal fun HeroContentSection(
    meta: Meta,
    nextEpisode: Video?,
    nextToWatch: NextToWatch?,
    onPlayClick: () -> Unit,
    isInLibrary: Boolean,
    onToggleLibrary: () -> Unit,
    onLibraryLongPress: () -> Unit,
    trailerAvailable: Boolean = false,
    onTrailerClick: () -> Unit = {},
    hideLogoDuringTrailer: Boolean = false,
    mdbListRatings: MDBListRatings? = null,
    hideMetaInfoImdb: Boolean = false,
    isTrailerPlaying: Boolean = false,
    playButtonFocusRequester: FocusRequester? = null,
    downFocusRequester: FocusRequester? = null,
    requestInitialFocus: Boolean = false,
    restorePlayFocusToken: Int = 0,
    onMoveDownRequested: (() -> Unit)? = null,
    onHeroActionFocused: (HeroAction) -> Unit = {},
    onPlayFocusRestored: () -> Unit = {},
    onPlayFocusChanged: (Boolean) -> Unit = {}
) {
    val context = LocalContext.current
    val isSeriesApi = remember(meta.apiType) {
        meta.apiType.equals("series", ignoreCase = true) || meta.apiType.equals("tv", ignoreCase = true)
    }
    val logoModel = remember(context, meta.artwork, meta.logo) {
        (meta.artwork?.logo.toCoilModelOrNull() ?: meta.logo.toSafeArtworkCoilModelOrNull())?.let { logo ->
            ImageRequest.Builder(context)
                .data(logo)
                .crossfade(true)
                .build()
        }
    }
    var logoLoadFailed by remember(meta.logo) { mutableStateOf(false) }
    val shouldShowLogo =
        logoModel != null &&
            !logoLoadFailed
    val libraryAddPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nexio.tv.R.raw.library_add_plus
    )
    val trailerPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nexio.tv.R.raw.trailer_play_button
    )
    val resolvedPlayFocusRequester = playButtonFocusRequester ?: remember { FocusRequester() }
    val libraryFocusRequester = remember { FocusRequester() }
    val trailerFocusRequester = remember { FocusRequester() }
    val heroActionOrder = remember(trailerAvailable) {
        buildHeroActionOrder(trailerAvailable = trailerAvailable)
    }
    val heroActionRequesters = remember(
        resolvedPlayFocusRequester,
        libraryFocusRequester,
        trailerFocusRequester
    ) {
        mapOf(
            HeroAction.PLAY to resolvedPlayFocusRequester,
            HeroAction.LIBRARY to libraryFocusRequester,
            HeroAction.TRAILER to trailerFocusRequester
        )
    }
    val strCreator = stringResource(R.string.hero_creator)
    val strDirector = stringResource(R.string.hero_director)
    val strWriter = stringResource(R.string.hero_writer)
    val creditLine = remember(meta.director, meta.writer, isSeriesApi) {
        val directorLine = meta.director.takeIf { it.isNotEmpty() }?.joinToString(", ")
        val writerLine = meta.writer.takeIf { it.isNotEmpty() }?.joinToString(", ")
        when {
            !directorLine.isNullOrBlank() -> {
                if (isSeriesApi) strCreator.format(directorLine) else strDirector.format(directorLine)
            }
            !writerLine.isNullOrBlank() -> strWriter.format(writerLine)
            else -> null
        }
    }

    val logoHeight = 100.dp
    val logoBottomPadding = 16.dp
    val logoMaxWidth = 0.4f

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(540.dp),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = tween(600))
                .padding(start = 48.dp, end = 48.dp, bottom = 16.dp),
            verticalArrangement = Arrangement.Bottom
        ) {
            if (shouldShowLogo) {
                AsyncImage(
                    model = logoModel,
                    contentDescription = meta.name,
                    onError = { logoLoadFailed = true },
                    modifier = Modifier
                        .height(logoHeight)
                        .fillMaxWidth(logoMaxWidth)
                        .padding(bottom = logoBottomPadding),
                    contentScale = ContentScale.Fit,
                    alignment = Alignment.CenterStart
                )
            } else {
                Text(
                    text = meta.name,
                    style = MaterialTheme.typography.displayMedium,
                    color = NexioColors.TextPrimary,
                    modifier = Modifier.padding(bottom = 8.dp)
                )
            }

            Column {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    PlayButton(
                        text = nextToWatch?.displayText ?: when {
                            nextEpisode != null -> stringResource(R.string.hero_play_episode, nextEpisode.season ?: 0, nextEpisode.episode ?: 0)
                            else -> stringResource(R.string.hero_play)
                        },
                        onClick = onPlayClick,
                        focusRequester = resolvedPlayFocusRequester,
                        directions = resolveHeroActionDirections(HeroAction.PLAY, heroActionOrder),
                        actionRequesters = heroActionRequesters,
                        downFocusRequester = downFocusRequester,
                        requestInitialFocus = requestInitialFocus,
                        restoreFocusToken = restorePlayFocusToken,
                        onMoveDownRequested = onMoveDownRequested,
                        onFocused = onHeroActionFocused,
                        onFocusRestored = onPlayFocusRestored,
                        onFocusChanged = onPlayFocusChanged
                    )

                    ActionIconButton(
                        action = HeroAction.LIBRARY,
                        icon = if (isInLibrary) Icons.Default.Check else null,
                        painter = if (!isInLibrary) {
                            libraryAddPainter
                        } else {
                            null
                        },
                        contentDescription = if (isInLibrary) stringResource(R.string.hero_remove_from_library) else stringResource(R.string.hero_add_to_library),
                        onClick = onToggleLibrary,
                        onLongPress = onLibraryLongPress,
                        focusRequester = libraryFocusRequester,
                        directions = resolveHeroActionDirections(HeroAction.LIBRARY, heroActionOrder),
                        actionRequesters = heroActionRequesters,
                        downFocusRequester = downFocusRequester,
                        onMoveDownRequested = onMoveDownRequested,
                        onFocused = onHeroActionFocused
                    )

                    if (trailerAvailable) {
                        ActionIconButtonPainter(
                            action = HeroAction.TRAILER,
                            painter = trailerPainter,
                            contentDescription = stringResource(R.string.hero_play_trailer),
                            onClick = onTrailerClick,
                            focusRequester = trailerFocusRequester,
                            directions = resolveHeroActionDirections(HeroAction.TRAILER, heroActionOrder),
                            actionRequesters = heroActionRequesters,
                            downFocusRequester = downFocusRequester,
                            onMoveDownRequested = onMoveDownRequested,
                            onFocused = onHeroActionFocused
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (!creditLine.isNullOrBlank()) {
                    Text(
                        text = creditLine,
                        style = MaterialTheme.typography.labelLarge,
                        color = NexioTheme.extendedColors.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(0.6f)
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                }

                if (mdbListRatings?.isEmpty() == false) {
                    MDBListRatingsRow(ratings = mdbListRatings)
                    Spacer(modifier = Modifier.height(14.dp))
                }

                if (meta.description != null) {
                    val fullDescription = meta.description
                    var displayedDescription by remember(fullDescription) { mutableStateOf(fullDescription) }
                    Text(
                        text = displayedDescription,
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexioColors.TextPrimary,
                        maxLines = HERO_DESCRIPTION_MAX_LINES,
                        overflow = TextOverflow.Clip,
                        onTextLayout = { layout ->
                            if (layout.hasVisualOverflow && displayedDescription == fullDescription) {
                                val visibleEnd = layout.getLineEnd(HERO_DESCRIPTION_MAX_LINES - 1, visibleEnd = true)
                                displayedDescription = collapseHeroDescriptionForOverflow(fullDescription, visibleEnd)
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth(0.6f)
                            .padding(bottom = 12.dp)
                    )
                }

                MetaInfoRow(meta = meta, hideImdbRating = hideMetaInfoImdb)
            }
        }
    }
}

internal fun collapseHeroDescriptionForOverflow(text: String, visibleEnd: Int): String {
    val visible = text
        .take(visibleEnd.coerceIn(0, text.length))
        .trimEnd()
    val withoutTrailingPeriod = if (visible.endsWith(".")) {
        visible.dropLast(1).trimEnd()
    } else {
        visible
    }
    return "$withoutTrailingPeriod..."
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun PlayButton(
    text: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    directions: HeroActionDirections,
    actionRequesters: Map<HeroAction, FocusRequester>,
    downFocusRequester: FocusRequester? = null,
    requestInitialFocus: Boolean = false,
    restoreFocusToken: Int = 0,
    onMoveDownRequested: (() -> Unit)? = null,
    onFocused: (HeroAction) -> Unit = {},
    onFocusRestored: () -> Unit = {},
    onFocusChanged: (Boolean) -> Unit = {}
) {
    var pendingFocusRestore by remember { mutableStateOf(false) }
    var playIsFocused by remember { mutableStateOf(false) }

    LaunchedEffect(restoreFocusToken, focusRequester) {
        pendingFocusRestore = restoreFocusToken > 0
        if (restoreFocusToken > 0) {
            focusRequester.requestFocusUntilFocused(
                isFocused = { playIsFocused },
                reason = "play_button_restore_token=$restoreFocusToken"
            )
        }
    }

    LaunchedEffect(requestInitialFocus, focusRequester) {
        if (requestInitialFocus) {
            focusRequester.requestFocusUntilFocused(
                isFocused = { playIsFocused },
                reason = "initial_detail_focus"
            )
        }
    }

    val context = LocalContext.current
    val playPainter = rememberRawSvgPainter(
        context = context,
        rawRes = com.nexio.tv.R.raw.ic_player_play
    )

    Button(
        onClick = onClick,
        modifier = Modifier
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    onMoveDownRequested != null &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onMoveDownRequested()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged {
                val wasFocused = playIsFocused
                playIsFocused = it.isFocused
                if (wasFocused != it.isFocused) {
                    onFocusChanged(it.isFocused)
                }
                if (it.isFocused) {
                    detailFocusDebug("heroActionFocused action=${HeroAction.PLAY} restored=$pendingFocusRestore")
                    onFocused(HeroAction.PLAY)
                    if (pendingFocusRestore) {
                        pendingFocusRestore = false
                        onFocusRestored()
                    }
                }
            }
            .focusProperties {
                up = FocusRequester.Cancel
                directions.left?.let { left = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapLeft) left = FocusRequester.Cancel
                }
                directions.right?.let { right = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapRight) right = FocusRequester.Cancel
                }
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            },
        colors = ButtonDefaults.colors(
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.Secondary,
            contentColor = NexioColors.TextPrimary,
            focusedContentColor = NexioColors.OnSecondary
        ),
        shape = ButtonDefaults.shape(
            shape = RoundedCornerShape(32.dp)
        ),
        scale = ButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        border = ButtonDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = RoundedCornerShape(32.dp)
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(32.dp)
            )
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 14.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                painter = playPainter,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButtonPainter(
    action: HeroAction,
    painter: Painter,
    contentDescription: String,
    onClick: () -> Unit,
    focusRequester: FocusRequester,
    directions: HeroActionDirections,
    actionRequesters: Map<HeroAction, FocusRequester>,
    downFocusRequester: FocusRequester? = null,
    onMoveDownRequested: (() -> Unit)? = null,
    onFocused: (HeroAction) -> Unit = {},
    enabled: Boolean = true
) {
    IconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .focusRequester(focusRequester)
            .onPreviewKeyEvent { event ->
                if (
                    onMoveDownRequested != null &&
                    event.nativeKeyEvent.action == AndroidKeyEvent.ACTION_DOWN &&
                    event.nativeKeyEvent.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onMoveDownRequested()
                    true
                } else {
                    false
                }
            }
            .onFocusChanged { state ->
                if (state.isFocused) {
                    detailFocusDebug("heroActionFocused action=$action restored=false")
                    onFocused(action)
                }
            }
            .focusProperties {
                up = FocusRequester.Cancel
                directions.left?.let { left = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapLeft) left = FocusRequester.Cancel
                }
                directions.right?.let { right = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapRight) right = FocusRequester.Cancel
                }
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.Secondary,
            contentColor = NexioColors.TextPrimary,
            focusedContentColor = NexioColors.OnSecondary
        ),
        scale = IconButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        border = IconButtonDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = CircleShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        Icon(
            painter = painter,
            contentDescription = contentDescription,
            modifier = Modifier.size(22.dp)
        )
    }
}

@OptIn(ExperimentalTvMaterial3Api::class, ExperimentalComposeUiApi::class)
@Composable
private fun ActionIconButton(
    action: HeroAction,
    icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
    painter: Painter? = null,
    contentDescription: String,
    onClick: () -> Unit,
    onLongPress: (() -> Unit)? = null,
    focusRequester: FocusRequester,
    directions: HeroActionDirections,
    actionRequesters: Map<HeroAction, FocusRequester>,
    downFocusRequester: FocusRequester? = null,
    onMoveDownRequested: (() -> Unit)? = null,
    enabled: Boolean = true,
    selected: Boolean = false,
    selectedContainerColor: Color = Color(0xFF7CFF9B),
    selectedContentColor: Color = Color.Black,
    onFocused: (HeroAction) -> Unit = {}
) {
    var longPressTriggered by remember { mutableStateOf(false) }

    IconButton(
        onClick = {
            if (longPressTriggered) {
                longPressTriggered = false
            } else {
                onClick()
            }
        },
        enabled = enabled,
        modifier = Modifier
            .size(48.dp)
            .focusRequester(focusRequester)
            .onFocusChanged { state ->
                if (state.isFocused) {
                    detailFocusDebug("heroActionFocused action=$action restored=false")
                    onFocused(action)
                }
            }
            .onPreviewKeyEvent { event ->
                val native = event.nativeKeyEvent
                if (
                    onMoveDownRequested != null &&
                    native.action == AndroidKeyEvent.ACTION_DOWN &&
                    native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_DOWN
                ) {
                    onMoveDownRequested()
                    return@onPreviewKeyEvent true
                }
                if (onLongPress != null && native.action == AndroidKeyEvent.ACTION_DOWN) {
                    if (native.keyCode == AndroidKeyEvent.KEYCODE_MENU) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }

                    val isSelectKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER
                    if ((native.isLongPress || native.repeatCount > 0) && isSelectKey) {
                        longPressTriggered = true
                        onLongPress()
                        return@onPreviewKeyEvent true
                    }
                }

                if (native.action == AndroidKeyEvent.ACTION_UP && longPressTriggered) {
                    val isSelectKey = native.keyCode == AndroidKeyEvent.KEYCODE_DPAD_CENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_NUMPAD_ENTER ||
                        native.keyCode == AndroidKeyEvent.KEYCODE_MENU
                    if (isSelectKey) {
                        longPressTriggered = false
                        return@onPreviewKeyEvent true
                    }
                }
                false
            }
            .focusProperties {
                up = FocusRequester.Cancel
                directions.left?.let { left = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapLeft) left = FocusRequester.Cancel
                }
                directions.right?.let { right = actionRequesters.getValue(it) } ?: run {
                    if (directions.trapRight) right = FocusRequester.Cancel
                }
                if (downFocusRequester != null) {
                    down = downFocusRequester
                }
            },
        colors = IconButtonDefaults.colors(
            containerColor = if (selected) selectedContainerColor else NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.Secondary,
            contentColor = if (selected) selectedContentColor else NexioColors.TextPrimary,
            focusedContentColor = NexioColors.OnSecondary
        ),
        scale = IconButtonDefaults.scale(
            focusedScale = 1f,
            pressedScale = 1f
        ),
        border = IconButtonDefaults.border(
            border = Border(
                border = BorderStroke(2.dp, Color.Transparent),
                shape = CircleShape
            ),
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = CircleShape
            )
        ),
        shape = IconButtonDefaults.shape(
            shape = CircleShape
        )
    ) {
        when {
            painter != null -> Icon(
                painter = painter,
                contentDescription = contentDescription,
                modifier = Modifier.size(22.dp)
            )
            icon != null -> Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoRow(
    meta: Meta,
    hideImdbRating: Boolean
) {
    val context = LocalContext.current
    val genresText = remember(meta.genres) { meta.genres.joinToString(" • ") }
    val runtimeText = remember(meta.runtime) { meta.runtime?.let { formatRuntime(it) } }
    val releaseText = remember(meta.localReleaseInfo, meta.releaseInfo) {
        meta.localReleaseInfo ?: meta.releaseInfo?.split("-")?.firstOrNull() ?: meta.releaseInfo
    }
    val imdbRating = if (hideImdbRating) null else meta.imdbRating
    val shouldShowImdbRating = imdbRating != null
    val ratingBadge = remember(meta.ratingSource) { titleRatingBadge(meta.ratingSource.orDefault()) }
    val ratingModel = remember(context, ratingBadge.logoRes) {
        ImageRequest.Builder(context)
            .data(ratingBadge.logoRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    val secondaryItems = remember(meta.ageRating, meta.country, meta.language) {
        buildList<String> {
            meta.ageRating?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.country?.trim()?.takeIf { it.isNotBlank() }?.let { add(it) }
            meta.language?.trim()?.takeIf { it.isNotBlank() }?.let { add(it.uppercase()) }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Primary row: Genres, Runtime, Release, Ratings
        Row(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Show all genres
            if (meta.genres.isNotEmpty()) {
                Text(
                    text = genresText,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexioTheme.extendedColors.textSecondary
                )
                MetaInfoDivider()
            }

            // Runtime
            runtimeText?.let { text ->
                Text(
                    text = text,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexioTheme.extendedColors.textSecondary
                )
                MetaInfoDivider()
            }

            releaseText?.let { year ->
                Text(
                    text = year,
                    style = MaterialTheme.typography.labelLarge,
                    color = NexioTheme.extendedColors.textSecondary
                )
                if (shouldShowImdbRating) {
                    MetaInfoDivider()
                }
            }

            imdbRating?.let { rating ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        AsyncImage(
                            model = ratingModel,
                            contentDescription = ratingBadge.contentDescription,
                            modifier = Modifier.size(30.dp),
                        contentScale = ContentScale.Fit
                    )
                    val ratingText = remember(rating) { String.format("%.1f", rating) }
                    Text(
                        text = ratingText,
                        style = MaterialTheme.typography.labelLarge,
                        color = NexioTheme.extendedColors.textSecondary
                    )
                }
            }
        }

        // Secondary row: Age Rating, Country, Language
        if (secondaryItems.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                secondaryItems.forEachIndexed { index, value ->
                    Text(
                        text = value,
                        style = MaterialTheme.typography.labelMedium,
                        color = NexioTheme.extendedColors.textTertiary
                    )
                    if (index < secondaryItems.lastIndex) {
                        MetaInfoDivider()
                    }
                }
            }
        }
    }
}

@Composable
private fun MDBListRatingsRow(ratings: MDBListRatings) {
    val context = LocalContext.current
    val items = remember(ratings) {
        listOf(
            Triple("trakt", com.nexio.tv.R.raw.mdblist_trakt, ratings.trakt),
            Triple("imdb", com.nexio.tv.R.raw.imdb_logo_2016, ratings.imdb),
            Triple("tmdb", com.nexio.tv.R.raw.mdblist_tmdb, ratings.tmdb),
            Triple("letterboxd", com.nexio.tv.R.raw.mdblist_letterboxd, ratings.letterboxd),
            Triple("tomatoes", com.nexio.tv.R.raw.mdblist_tomatoes, ratings.tomatoes)
        ).filter { it.third != null }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        items.forEach { (provider, logoRes, rating) ->
            val resolvedRating = rating ?: return@forEach
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                val model = remember(context, logoRes) {
                    ImageRequest.Builder(context)
                        .data(logoRes)
                        .decoderFactory(SvgDecoder.Factory())
                        .build()
                }
                AsyncImage(
                    model = model,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    contentScale = ContentScale.Fit
                )
                Text(
                    text = formatMDBListRating(provider, resolvedRating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.audience?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nexio.tv.R.drawable.mdblist_audience),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("audience", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexioTheme.extendedColors.textSecondary
                )
            }
        }

        ratings.metacritic?.let { rating ->
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Image(
                    painter = painterResource(id = com.nexio.tv.R.drawable.mdblist_metacritic),
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = formatMDBListRating("metacritic", rating),
                    style = MaterialTheme.typography.labelMedium,
                    color = NexioTheme.extendedColors.textSecondary
                )
            }
        }
    }
}

private fun formatMDBListRating(provider: String, rating: Double): String {
    return when (provider) {
        "imdb", "tmdb", "letterboxd" -> String.format("%.1f", rating)
        else -> {
            if (rating % 1.0 == 0.0) rating.toInt().toString() else String.format("%.1f", rating)
        }
    }
}

private fun formatRuntime(runtime: String): String {
    val minutes = runtime.filter { it.isDigit() }.toIntOrNull() ?: return runtime
    return if (minutes >= 60) {
        val hours = minutes / 60
        val mins = minutes % 60
        if (mins > 0) "${hours}h ${mins}m" else "${hours}h"
    } else {
        "${minutes}m"
    }
}

@Composable
private fun rememberRawSvgPainter(
    context: android.content.Context,
    @androidx.annotation.RawRes rawRes: Int
): Painter {
    val model = remember(rawRes, context) {
        ImageRequest.Builder(context)
            .data(rawRes)
            .decoderFactory(SvgDecoder.Factory())
            .build()
    }
    return coil.compose.rememberAsyncImagePainter(model = model)
}

@OptIn(ExperimentalTvMaterial3Api::class)
@Composable
private fun MetaInfoDivider() {
    Text(
        text = "•",
        style = MaterialTheme.typography.labelLarge,
        color = NexioTheme.extendedColors.textTertiary
    )
}

package com.nexio.tv.ui.screens.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nexio.tv.domain.model.MetaCastMember
import com.nexio.tv.ui.theme.NexioColors
import android.text.format.DateFormat
import java.util.Date
import kotlinx.coroutines.delay
import com.nexio.tv.R

@Composable
fun PauseOverlay(
    visible: Boolean,
    onClose: () -> Unit,
    title: String,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    backdropUrl: String?,
    logoUrl: String?,
    cast: List<MetaCastMember>,
    modifier: Modifier = Modifier
) {
    var selectedCastMember by remember { mutableStateOf<MetaCastMember?>(null) }

    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(animationSpec = tween(250)),
        exit = fadeOut(animationSpec = tween(200)),
        modifier = modifier
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .testTag("pause_overlay_root")
                .clickable(onClick = onClose)
        ) {
            PauseOverlayBackdrop(backdropUrl = backdropUrl)

            val leftGradient = remember {
                Brush.horizontalGradient(
                    colors = listOf(
                        Color.Black.copy(alpha = 0.92f),
                        Color.Black.copy(alpha = 0.64f),
                        Color.Transparent
                    )
                )
            }
            val topGradient = remember {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Black.copy(alpha = 0.6f),
                        0.3f to Color.Black.copy(alpha = 0.4f),
                        0.6f to Color.Black.copy(alpha = 0.2f),
                        1f to Color.Transparent
                    )
                )
            }
            val bottomGradient = remember {
                Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to Color.Transparent,
                        0.58f to Color.Black.copy(alpha = 0.16f),
                        1f to Color.Black.copy(alpha = 0.9f)
                    )
                )
            }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithCache {
                        onDrawBehind {
                            drawRect(brush = leftGradient, size = size)
                            drawRect(brush = topGradient, size = size)
                            drawRect(brush = bottomGradient, size = size)
                        }
                    }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(start = 56.dp, end = 56.dp, top = 40.dp, bottom = 120.dp)
            ) {
                PauseOverlayClock(
                    modifier = Modifier.align(Alignment.TopEnd)
                )

                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Bottom
                ) {
                    if (selectedCastMember != null) {
                        CastDetailView(
                            member = selectedCastMember!!,
                            onBack = { selectedCastMember = null }
                        )
                    } else {
                        PauseMetadataView(
                            title = title,
                            episodeTitle = episodeTitle,
                            season = season,
                            episode = episode,
                            year = year,
                            type = type,
                            description = description,
                            logoUrl = logoUrl,
                            cast = cast,
                            onCastSelected = { selectedCastMember = it }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PauseOverlayBackdrop(backdropUrl: String?) {
    var backdropLoadFailed by remember(backdropUrl) { mutableStateOf(false) }
    if (backdropUrl.isNullOrBlank() || backdropLoadFailed) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag("pause_overlay_backdrop_fallback")
        )
        return
    }

    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    val widthPx = remember(configuration.screenWidthDp, density) {
        with(density) { configuration.screenWidthDp.dp.roundToPx() }
    }
    val heightPx = remember(configuration.screenHeightDp, density) {
        with(density) { configuration.screenHeightDp.dp.roundToPx() }
    }
    val model = remember(context, backdropUrl, widthPx, heightPx) {
        ImageRequest.Builder(context)
            .data(backdropUrl)
            .crossfade(false)
            .size(width = widthPx, height = heightPx)
            .build()
    }

    AsyncImage(
        model = model,
        contentDescription = stringResource(R.string.cd_loading_backdrop),
        onError = { backdropLoadFailed = true },
        modifier = Modifier
            .fillMaxSize()
            .testTag("pause_overlay_backdrop"),
        contentScale = ContentScale.Crop
    )
}

@Composable
private fun PauseOverlayClock(modifier: Modifier = Modifier) {
    var nowMillis by remember { mutableStateOf(System.currentTimeMillis()) }
    val context = LocalContext.current
    val formatter = remember(context) { DateFormat.getTimeFormat(context) }

    LaunchedEffect(Unit) {
        while (true) {
            nowMillis = System.currentTimeMillis()
            delay(1_000)
        }
    }

    Text(
        text = formatter.format(Date(nowMillis)),
        style = MaterialTheme.typography.headlineSmall.copy(
            fontWeight = FontWeight.Normal,
            fontSize = 34.sp
        ),
        color = Color.White.copy(alpha = 0.95f),
        modifier = modifier
    )
}

@Composable
private fun PauseMetadataView(
    title: String,
    episodeTitle: String?,
    season: Int?,
    episode: Int?,
    year: String?,
    type: String?,
    description: String?,
    logoUrl: String?,
    cast: List<MetaCastMember>,
    onCastSelected: (MetaCastMember) -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Bottom
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = stringResource(R.string.pause_you_are_watching),
                style = MaterialTheme.typography.bodyLarge,
                color = NexioColors.TextTertiary
            )

            Spacer(modifier = Modifier.height(12.dp))

            PauseTitleLogo(
                logoUrl = logoUrl,
                title = title
            )

            if (!year.isNullOrBlank()) {
                val episodeLabel = if (type in listOf("series", "tv") && season != null && episode != null) {
                    " • S${season}E${episode}"
                } else {
                    ""
                }

                Text(
                    text = "$year$episodeLabel",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextSecondary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }

            if (!episodeTitle.isNullOrBlank()) {
                Text(
                    text = episodeTitle,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp)
                )
            }

            if (!description.isNullOrBlank()) {
                Text(
                    text = description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = NexioColors.TextSecondary,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 16.dp)
                )
            }

            if (cast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.pause_cast_label),
                    style = MaterialTheme.typography.titleSmall,
                    color = NexioColors.TextTertiary
                )

                Spacer(modifier = Modifier.height(12.dp))

                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    content = {
                        items(cast.take(8)) { member ->
                            CastPictureChip(member = member, onClick = { onCastSelected(member) })
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun PauseTitleLogo(
    logoUrl: String?,
    title: String
) {
    var logoLoadFailed by remember(logoUrl) { mutableStateOf(false) }
    val context = LocalContext.current
    val density = LocalDensity.current
    val logoWidth = 360.dp
    val logoHeight = 150.dp
    val logoWidthPx = remember(logoWidth, density) { with(density) { logoWidth.roundToPx() } }
    val logoHeightPx = remember(logoHeight, density) { with(density) { logoHeight.roundToPx() } }
    val logoModel = remember(context, logoUrl, logoWidthPx, logoHeightPx) {
        logoUrl?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = logoWidthPx, height = logoHeightPx)
                .build()
        }
    }

    if (logoModel != null && !logoLoadFailed) {
        AsyncImage(
            model = logoModel,
            contentDescription = stringResource(R.string.cd_loading_logo),
            onError = { logoLoadFailed = true },
            modifier = Modifier
                .width(logoWidth)
                .height(logoHeight)
                .testTag("pause_overlay_logo"),
            contentScale = ContentScale.Fit
        )
    } else {
        Text(
            text = title,
            style = MaterialTheme.typography.headlineLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.testTag("pause_overlay_title_fallback")
        )
    }
}

@Composable
private fun CastPictureChip(
    member: MetaCastMember,
    onClick: () -> Unit
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val cardSize = 100.dp
    val itemWidth = 150.dp
    val cardSizePx = remember(cardSize, density) {
        with(density) { cardSize.roundToPx() }
    }
    val typography = MaterialTheme.typography
    val nameStyle = remember(typography) { typography.labelMedium }
    val characterStyle = remember(typography) { typography.labelSmall }
    val initialsStyle = remember(typography) { typography.titleLarge }
    val photoModel = remember(context, member.photo, cardSizePx) {
        member.photo?.takeIf { it.isNotBlank() }?.let { url ->
            ImageRequest.Builder(context)
                .data(url)
                .crossfade(false)
                .size(width = cardSizePx, height = cardSizePx)
                .build()
        }
    }

    Column(
        modifier = Modifier.width(itemWidth),
        horizontalAlignment = Alignment.Start
    ) {
        Card(
            onClick = onClick,
            modifier = Modifier
                .size(cardSize)
                .align(Alignment.Start)
                .testTag("pause_overlay_cast_photo"),
            colors = CardDefaults.colors(
                containerColor = Color.White.copy(alpha = 0.1f),
                focusedContainerColor = Color.White.copy(alpha = 0.18f)
            ),
            shape = CardDefaults.shape(shape = CircleShape)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (photoModel != null) {
                    AsyncImage(
                        model = photoModel,
                        contentDescription = member.name,
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Text(
                        text = member.name.firstOrNull()?.uppercase() ?: "?",
                        style = initialsStyle,
                        color = Color.White
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        Text(
            text = member.name,
            style = nameStyle,
            color = NexioColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )

        val displayCharacter = pauseOverlayCastDisplayRole(
            character = member.character,
            creatorLabel = stringResource(R.string.cast_role_creator),
            directorLabel = stringResource(R.string.cast_role_director),
            writerLabel = stringResource(R.string.cast_role_writer)
        )
        if (!displayCharacter.isNullOrBlank()) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = displayCharacter,
                style = characterStyle,
                color = NexioColors.TextTertiary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

internal fun pauseOverlayCastDisplayRole(
    character: String?,
    creatorLabel: String,
    directorLabel: String,
    writerLabel: String
): String? {
    val normalized = character
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?: return null

    return when {
        normalized.equals("Creator", ignoreCase = true) -> creatorLabel
        normalized.equals("Director", ignoreCase = true) -> directorLabel
        normalized.equals("Writer", ignoreCase = true) -> writerLabel
        else -> normalized
    }
}

@Composable
private fun CastDetailView(
    member: MetaCastMember,
    onBack: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 28.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = stringResource(R.string.cd_back),
                tint = NexioColors.TextSecondary,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = stringResource(R.string.pause_back_to_details),
                style = MaterialTheme.typography.bodyMedium,
                color = NexioColors.TextSecondary,
                modifier = Modifier.clickable(onClick = onBack)
            )
        }

        Row(
            verticalAlignment = Alignment.Top
        ) {
            if (!member.photo.isNullOrBlank()) {
                AsyncImage(
                    model = ImageRequest.Builder(LocalContext.current)
                        .data(member.photo)
                        .crossfade(true)
                        .build(),
                    contentDescription = member.name,
                    modifier = Modifier
                        .size(width = 160.dp, height = 240.dp)
                        .clip(RoundedCornerShape(16.dp)),
                    contentScale = ContentScale.Crop
                )

                Spacer(modifier = Modifier.width(28.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = member.name,
                    style = MaterialTheme.typography.headlineMedium,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )

                if (!member.character.isNullOrBlank()) {
                    Text(
                        text = stringResource(R.string.pause_as_character, member.character),
                        style = MaterialTheme.typography.bodyLarge,
                        color = NexioColors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

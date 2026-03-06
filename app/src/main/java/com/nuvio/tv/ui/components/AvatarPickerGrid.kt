package com.nuvio.tv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.ui.theme.NuvioColors

private val AvatarCategories = listOf("all", "anime", "tv", "movie")

@Composable
fun AvatarPickerGrid(
    avatars: List<AvatarCatalogItem>,
    selectedAvatarId: String?,
    onAvatarSelected: (AvatarCatalogItem) -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedCategory by remember { mutableStateOf("all") }

    val filteredAvatars = remember(avatars, selectedCategory) {
        if (selectedCategory == "all") avatars
        else avatars.filter { it.category == selectedCategory }
    }

    Column(modifier = modifier) {
        // Category tabs
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            horizontalArrangement = Arrangement.Center
        ) {
            AvatarCategories.forEach { category ->
                CategoryTab(
                    label = categoryLabel(category),
                    isSelected = selectedCategory == category,
                    onClick = { selectedCategory = category }
                )
                if (category != AvatarCategories.last()) {
                    Spacer(modifier = Modifier.width(8.dp))
                }
            }
        }

        // Avatar grid
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 88.dp),
            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .wrapContentHeight()
        ) {
            items(filteredAvatars, key = { it.id }) { avatar ->
                AvatarGridItem(
                    avatar = avatar,
                    isSelected = avatar.id == selectedAvatarId,
                    onClick = { onAvatarSelected(avatar) }
                )
            }
        }
    }
}

@Composable
private fun CategoryTab(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            isSelected -> NuvioColors.Secondary.copy(alpha = 0.9f)
            isFocused -> Color.White.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.06f)
        },
        animationSpec = tween(150),
        label = "categoryBg"
    )
    val textColor by animateColorAsState(
        targetValue = if (isSelected || isFocused) Color.White else NuvioColors.TextSecondary,
        animationSpec = tween(150),
        label = "categoryText"
    )

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(bgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 18.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = label,
            color = textColor,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Medium
        )
    }
}

@Composable
private fun AvatarGridItem(
    avatar: AvatarCatalogItem,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val focusRequester = remember { FocusRequester() }

    val scale by animateFloatAsState(
        targetValue = if (isFocused) 1.1f else 1f,
        animationSpec = tween(150),
        label = "avatarScale"
    )
    val borderWidth by animateDpAsState(
        targetValue = when {
            isSelected -> 3.dp
            isFocused -> 2.dp
            else -> 0.dp
        },
        animationSpec = tween(120),
        label = "avatarBorder"
    )
    val borderColor by animateColorAsState(
        targetValue = when {
            isSelected -> NuvioColors.Secondary
            isFocused -> Color.White.copy(alpha = 0.7f)
            else -> Color.Transparent
        },
        animationSpec = tween(120),
        label = "avatarBorderColor"
    )

    Box(
        modifier = Modifier
            .size(80.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .clip(CircleShape)
            .border(borderWidth, borderColor, CircleShape)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AsyncImage(
            model = ImageRequest.Builder(LocalContext.current)
                .data(avatar.imageUrl)
                .crossfade(true)
                .build(),
            contentDescription = avatar.displayName,
            modifier = Modifier
                .fillMaxSize()
                .clip(CircleShape),
            contentScale = ContentScale.Crop
        )
    }
}

@Composable
private fun categoryLabel(category: String): String {
    return when (category) {
        "all" -> stringResource(R.string.profile_avatar_category_all)
        "anime" -> stringResource(R.string.profile_avatar_category_anime)
        "movie" -> stringResource(R.string.profile_avatar_category_movie)
        "tv" -> stringResource(R.string.profile_avatar_category_tv)
        else -> category.replaceFirstChar { it.uppercase() }
    }
}

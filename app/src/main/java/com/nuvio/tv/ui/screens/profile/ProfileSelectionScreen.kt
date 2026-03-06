package com.nuvio.tv.ui.screens.profile

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import androidx.compose.ui.res.stringResource
import com.nuvio.tv.R
import com.nuvio.tv.data.remote.supabase.AvatarCatalogItem
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.AvatarPickerGrid
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors

private object ProfileSelectionSpacing {
    val ScreenPaddingHorizontal = 56.dp
    val ScreenPaddingVertical = 48.dp
    val LogoWidth = 190.dp
    val LogoHeight = 44.dp
    val LogoToHeading = 28.dp
    val HeadingToSubheading = 12.dp
    val GridItemGap = 28.dp
    val CardWidth = 152.dp
    val CardPaddingHorizontal = 10.dp
    val CardPaddingVertical = 8.dp
    val AvatarContainer = 126.dp
    val AvatarToName = 12.dp
    val NameToMeta = 8.dp
    val MetaSlotHeight = 16.dp
}

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()
    val avatarCatalog by viewModel.avatarCatalog.collectAsState()
    val isCreating by viewModel.isCreating.collectAsState()
    var focusedAvatarColor by remember { mutableStateOf(Color(0xFF1E88E5)) }
    var showCreateProfile by remember { mutableStateOf(false) }

    LaunchedEffect(profiles) {
        if (profiles.isNotEmpty()) {
            focusedAvatarColor = parseProfileColor(profiles.first().avatarColorHex)
        }
    }

    LaunchedEffect(Unit) {
        viewModel.loadAvatarCatalog()
    }

    // Close overlay when profile creation succeeds
    LaunchedEffect(isCreating) {
        if (!isCreating && showCreateProfile) {
            // Check if a new profile was just added
        }
    }

    val animatedAvatarColor by animateColorAsState(
        targetValue = focusedAvatarColor,
        animationSpec = tween(durationMillis = 520),
        label = "focusedAvatarColor"
    )
    val gradientTop = lerp(NuvioColors.BackgroundElevated, animatedAvatarColor, 0.3f)
    val gradientMid = lerp(NuvioColors.Background, animatedAvatarColor, 0.14f)
    val halfFadeStrong = animatedAvatarColor.copy(alpha = 0.26f)
    val halfFadeSoft = animatedAvatarColor.copy(alpha = 0.08f)

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .background(
                brush = Brush.verticalGradient(
                    colorStops = arrayOf(
                        0f to gradientTop,
                        0.42f to gradientMid,
                        1f to NuvioColors.Background
                    )
                )
            )
            .background(
                brush = Brush.horizontalGradient(
                    colorStops = arrayOf(
                        0f to halfFadeStrong,
                        0.45f to halfFadeSoft,
                        0.72f to Color.Transparent,
                        1f to Color.Transparent
                    )
                )
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(
                    horizontal = ProfileSelectionSpacing.ScreenPaddingHorizontal,
                    vertical = ProfileSelectionSpacing.ScreenPaddingVertical
                ),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_logo_wordmark),
                contentDescription = "NuvioTV",
                modifier = Modifier
                    .width(ProfileSelectionSpacing.LogoWidth)
                    .height(ProfileSelectionSpacing.LogoHeight),
                contentScale = ContentScale.Fit
            )

            Spacer(modifier = Modifier.height(ProfileSelectionSpacing.LogoToHeading))

            Text(
                text = stringResource(R.string.profile_selection_title),
                color = NuvioColors.TextPrimary,
                fontSize = 44.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = (-0.5).sp
            )

            Spacer(modifier = Modifier.height(ProfileSelectionSpacing.HeadingToSubheading))

            Text(
                text = stringResource(R.string.profile_selection_subtitle),
                color = NuvioColors.TextSecondary,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))

            ProfileGrid(
                profiles = profiles,
                canAddProfile = viewModel.canAddProfile,
                avatarUrlResolver = { avatarId -> viewModel.getAvatarImageUrl(avatarId) },
                onProfileFocused = { colorHex ->
                    focusedAvatarColor = parseProfileColor(colorHex)
                },
                onProfileSelected = { id ->
                    viewModel.selectProfile(id, onComplete = onProfileSelected)
                },
                onAddProfileClick = { showCreateProfile = true }
            )

            Spacer(modifier = Modifier.weight(1f, fill = true))

            Text(
                text = stringResource(R.string.profile_selection_hint),
                color = NuvioColors.TextTertiary.copy(alpha = 0.9f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        }

        // Create Profile Overlay
        AnimatedVisibility(
            visible = showCreateProfile,
            enter = fadeIn(tween(200)),
            exit = fadeOut(tween(150))
        ) {
            CreateProfileOverlay(
                avatarCatalog = avatarCatalog,
                isCreating = isCreating,
                onDismiss = { showCreateProfile = false },
                onCreateProfile = { name, colorHex, avatarId ->
                    viewModel.createProfile(name, colorHex, avatarId)
                    showCreateProfile = false
                }
            )
        }
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    canAddProfile: Boolean,
    avatarUrlResolver: (String?) -> String?,
    onProfileFocused: (String) -> Unit,
    onProfileSelected: (Int) -> Unit,
    onAddProfileClick: () -> Unit
) {
    val totalItems = profiles.size + if (canAddProfile) 1 else 0
    val focusRequesters = remember(totalItems) {
        List(totalItems) { FocusRequester() }
    }

    LaunchedEffect(totalItems) {
        repeat(2) { withFrameNanos { } }
        if (focusRequesters.isNotEmpty()) {
            runCatching { focusRequesters.first().requestFocus() }
        }
    }

    if (profiles.isEmpty() && !canAddProfile) {
        Text(
            text = stringResource(R.string.profile_selection_empty),
            color = NuvioColors.TextSecondary,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium
        )
    } else {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(ProfileSelectionSpacing.GridItemGap),
                verticalAlignment = Alignment.Top
            ) {
                profiles.forEachIndexed { index, profile ->
                    ProfileCard(
                        profile = profile,
                        avatarImageUrl = avatarUrlResolver(profile.avatarId),
                        focusRequester = focusRequesters[index],
                        onFocused = { onProfileFocused(profile.avatarColorHex) },
                        onClick = { onProfileSelected(profile.id) }
                    )
                }
                if (canAddProfile) {
                    AddProfileCard(
                        focusRequester = focusRequesters[profiles.size],
                        onFocused = { onProfileFocused("#555555") },
                        onClick = onAddProfileClick
                    )
                }
            }
        }
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    avatarImageUrl: String?,
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "profileItemScale"
    )
    val avatarSize by animateDpAsState(
        targetValue = if (isFocused) 102.dp else 96.dp,
        animationSpec = tween(durationMillis = 150),
        label = "profileAvatarSize"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 140),
        label = "profileRingWidth"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.Secondary else NuvioColors.Border.copy(alpha = 0.75f),
        animationSpec = tween(durationMillis = 140),
        label = "profileRingColor"
    )
    val nameColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
        animationSpec = tween(durationMillis = 120),
        label = "profileNameColor"
    )
    val nameWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium

    Column(
        modifier = Modifier
            .width(ProfileSelectionSpacing.CardWidth)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ProfileSelectionSpacing.AvatarContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isFocused) 122.dp else 114.dp)
                    .clip(CircleShape)
                    .border(
                        width = ringWidth,
                        color = ringColor,
                        shape = CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = avatarSize,
                    avatarImageUrl = avatarImageUrl
                )
            }

            if (profile.isPrimary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 1.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB300), CircleShape)
                        .border(
                            width = 2.dp,
                            color = NuvioColors.Background,
                            shape = CircleShape
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2605",
                        color = Color.White,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.AvatarToName))

        Text(
            text = profile.name,
            color = nameColor,
            fontSize = 17.sp,
            fontWeight = nameWeight,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))

        Box(
            modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight),
            contentAlignment = Alignment.TopCenter
        ) {
            if (profile.isPrimary) {
                Text(
                    text = stringResource(R.string.profile_selection_primary_badge),
                    color = Color(0xFFFFB300),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    letterSpacing = 0.8.sp
                )
            }
        }
    }
}

private fun parseProfileColor(colorHex: String): Color {
    return runCatching { Color(android.graphics.Color.parseColor(colorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
}

private val ProfileColorOptions = listOf(
    "#1E88E5", "#E53935", "#8E24AA", "#43A047",
    "#FB8C00", "#D81B60", "#00ACC1", "#3949AB"
)

@Composable
private fun AddProfileCard(
    focusRequester: FocusRequester,
    onFocused: () -> Unit,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }
    val itemScale by animateFloatAsState(
        targetValue = if (isFocused) 1.04f else 1f,
        animationSpec = tween(durationMillis = 160),
        label = "addItemScale"
    )
    val ringWidth by animateDpAsState(
        targetValue = if (isFocused) 3.dp else 1.dp,
        animationSpec = tween(durationMillis = 140),
        label = "addRingWidth"
    )
    val ringColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.Secondary else NuvioColors.Border.copy(alpha = 0.5f),
        animationSpec = tween(durationMillis = 140),
        label = "addRingColor"
    )
    val nameColor by animateColorAsState(
        targetValue = if (isFocused) NuvioColors.TextPrimary else NuvioColors.TextTertiary,
        animationSpec = tween(durationMillis = 120),
        label = "addNameColor"
    )

    Column(
        modifier = Modifier
            .width(ProfileSelectionSpacing.CardWidth)
            .graphicsLayer {
                scaleX = itemScale
                scaleY = itemScale
            }
            .focusRequester(focusRequester)
            .onFocusChanged {
                isFocused = it.isFocused
                if (it.isFocused) onFocused()
            }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(
                horizontal = ProfileSelectionSpacing.CardPaddingHorizontal,
                vertical = ProfileSelectionSpacing.CardPaddingVertical
            ),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier.size(ProfileSelectionSpacing.AvatarContainer),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .size(if (isFocused) 122.dp else 114.dp)
                    .clip(CircleShape)
                    .border(
                        width = ringWidth,
                        color = ringColor,
                        shape = CircleShape
                    )
                    .background(
                        Color.White.copy(alpha = if (isFocused) 0.12f else 0.06f),
                        CircleShape
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "+",
                    color = if (isFocused) Color.White else NuvioColors.TextTertiary,
                    fontSize = 40.sp,
                    fontWeight = FontWeight.Light,
                    textAlign = TextAlign.Center
                )
            }
        }

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.AvatarToName))

        Text(
            text = stringResource(R.string.profile_add_new),
            color = nameColor,
            fontSize = 17.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1
        )

        Spacer(modifier = Modifier.height(ProfileSelectionSpacing.NameToMeta))
        Box(modifier = Modifier.height(ProfileSelectionSpacing.MetaSlotHeight))
    }
}

@Composable
private fun CreateProfileOverlay(
    avatarCatalog: List<AvatarCatalogItem>,
    isCreating: Boolean,
    onDismiss: () -> Unit,
    onCreateProfile: (name: String, colorHex: String, avatarId: String?) -> Unit
) {
    var profileName by remember { mutableStateOf("") }
    var selectedColorHex by remember { mutableStateOf(ProfileColorOptions.first()) }
    var selectedAvatar by remember { mutableStateOf<AvatarCatalogItem?>(null) }
    val nameFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        repeat(2) { withFrameNanos { } }
        runCatching { nameFocusRequester.requestFocus() }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.85f))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            ),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 620.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(Color(0xFF1A1A1A))
                .border(1.dp, Color.White.copy(alpha = 0.08f), RoundedCornerShape(20.dp))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {} // prevent dismiss when clicking the panel
                )
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = stringResource(R.string.profile_create_title),
                color = Color.White,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Preview avatar
            Box(
                modifier = Modifier.size(88.dp),
                contentAlignment = Alignment.Center
            ) {
                ProfileAvatarCircle(
                    name = profileName.ifEmpty { "?" },
                    colorHex = selectedColorHex,
                    size = 88.dp,
                    avatarImageUrl = selectedAvatar?.imageUrl
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // Name field
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.7f)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.White.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 14.dp)
            ) {
                if (profileName.isEmpty()) {
                    Text(
                        text = stringResource(R.string.profile_name_placeholder),
                        color = NuvioColors.TextTertiary,
                        fontSize = 16.sp
                    )
                }
                BasicTextField(
                    value = profileName,
                    onValueChange = { if (it.length <= 20) profileName = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(nameFocusRequester),
                    textStyle = TextStyle(
                        color = Color.White,
                        fontSize = 16.sp
                    ),
                    singleLine = true,
                    cursorBrush = SolidColor(NuvioColors.Secondary)
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // Color picker row
            Text(
                text = stringResource(R.string.profile_avatar_color),
                color = NuvioColors.TextSecondary,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProfileColorOptions.forEach { colorHex ->
                    val isSelected = colorHex == selectedColorHex
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(parseProfileColor(colorHex), CircleShape)
                            .then(
                                if (isSelected) Modifier.border(2.dp, Color.White, CircleShape)
                                else Modifier
                            )
                            .clickable { selectedColorHex = colorHex }
                    )
                }
            }

            // Avatar picker section
            if (avatarCatalog.isNotEmpty()) {
                Spacer(modifier = Modifier.height(20.dp))

                Text(
                    text = stringResource(R.string.profile_choose_avatar),
                    color = NuvioColors.TextSecondary,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.height(8.dp))

                AvatarPickerGrid(
                    avatars = avatarCatalog,
                    selectedAvatarId = selectedAvatar?.id,
                    onAvatarSelected = { avatar ->
                        selectedAvatar = if (selectedAvatar?.id == avatar.id) null else avatar
                    },
                    modifier = Modifier.heightIn(max = 260.dp)
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Action buttons
            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                OverlayButton(
                    text = stringResource(R.string.profile_cancel),
                    isPrimary = false,
                    onClick = onDismiss
                )
                OverlayButton(
                    text = if (isCreating) stringResource(R.string.profile_creating)
                           else stringResource(R.string.profile_create_btn),
                    isPrimary = true,
                    enabled = profileName.isNotBlank() && !isCreating,
                    onClick = {
                        onCreateProfile(profileName, selectedColorHex, selectedAvatar?.id)
                    }
                )
            }
        }
    }
}

@Composable
private fun OverlayButton(
    text: String,
    isPrimary: Boolean,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val interactionSource = remember { MutableInteractionSource() }

    val bgColor by animateColorAsState(
        targetValue = when {
            !enabled -> Color.White.copy(alpha = 0.04f)
            isPrimary && isFocused -> NuvioColors.Secondary
            isPrimary -> NuvioColors.Secondary.copy(alpha = 0.8f)
            isFocused -> Color.White.copy(alpha = 0.15f)
            else -> Color.White.copy(alpha = 0.08f)
        },
        animationSpec = tween(120),
        label = "btnBg"
    )
    val textColor = when {
        !enabled -> NuvioColors.TextDisabled
        else -> Color.White
    }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(horizontal = 28.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = textColor,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold
        )
    }
}

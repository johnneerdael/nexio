package com.nexio.tv.ui.screens.profile

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.domain.model.UserProfile
import com.nexio.tv.ui.components.ProfileAvatarCircle
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: (Int) -> Unit,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val activeProfileId by viewModel.activeProfileId.collectAsStateWithLifecycle()
    val profilePinEnabled by viewModel.profilePinEnabled.collectAsStateWithLifecycle()
    val pinState by viewModel.pinState.collectAsStateWithLifecycle()

    var focusedIndex by remember { mutableIntStateOf(0) }
    var activePinOverlayProfile by remember { mutableStateOf<UserProfile?>(null) }

    val initialFocusIndex = remember(profiles, activeProfileId) {
        val idx = profiles.indexOfFirst { it.id == activeProfileId }
        if (idx >= 0) idx else 0
    }

    val focusRequesters = remember(profiles.size) { List(profiles.size) { FocusRequester() } }

    LaunchedEffect(profiles.size) {
        repeat(2) { withFrameNanos { } }
        val targetIndex = profiles.indexOfFirst { it.id == activeProfileId }.takeIf { it >= 0 } ?: 0
        runCatching { focusRequesters[targetIndex].requestFocus() }
    }

    LaunchedEffect(Unit) {
        viewModel.pinUnlockedProfileId.collect { profileId ->
            if (activePinOverlayProfile?.id == profileId) {
                activePinOverlayProfile = null
                viewModel.resetPinState()
                onProfileSelected(profileId)
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NexioColors.Background),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = "Who's watching?",
                style = MaterialTheme.typography.headlineLarge,
                color = NexioColors.TextPrimary,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(Modifier.height(48.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(24.dp),
                modifier = Modifier.onPreviewKeyEvent { keyEvent ->
                    if (keyEvent.type == KeyEventType.KeyDown &&
                        (keyEvent.key == Key.DirectionCenter || keyEvent.key == Key.Enter || keyEvent.key == Key.NumPadEnter)
                    ) {
                        val profile = profiles.getOrNull(focusedIndex)
                        if (profile != null) {
                            if (profilePinEnabled[profile.id] == true) {
                                activePinOverlayProfile = profile
                            } else {
                                onProfileSelected(profile.id)
                            }
                        }
                        true
                    } else {
                        false
                    }
                }
            ) {
                profiles.forEachIndexed { index, profile ->
                    val isPinLocked = profilePinEnabled[profile.id] == true
                    ProfileCard(
                        profile = profile,
                        isFocused = focusedIndex == index,
                        isPinLocked = isPinLocked,
                        focusRequester = focusRequesters[index],
                        onClick = {
                            if (isPinLocked) {
                                activePinOverlayProfile = profile
                            } else {
                                onProfileSelected(profile.id)
                            }
                        },
                        modifier = Modifier
                            .focusRequester(focusRequesters[index])
                            .onFocusChanged { focusState ->
                                if (focusState.isFocused) {
                                    focusedIndex = index
                                }
                            }
                            .focusable()
                    )
                }
            }
        }

        // PIN overlay: shown when a PIN-locked profile is selected
        activePinOverlayProfile?.let { profile ->
            ProfilePinOverlay(
                profile = profile,
                onPinSubmit = { pin -> viewModel.verifyPin(profile.id, pin) },
                onDismiss = {
                    activePinOverlayProfile = null
                    viewModel.resetPinState()
                },
                isVerifying = pinState.isVerifying,
                isError = pinState.isError,
                errorMessage = pinState.errorMessage,
                retryAfterSeconds = pinState.retryAfterSeconds,
                onErrorConsumed = { viewModel.consumePinError() }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    isFocused: Boolean,
    isPinLocked: Boolean,
    focusRequester: FocusRequester,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val focusProgress by animateFloatAsState(
        targetValue = if (isFocused) 1f else 0f,
        animationSpec = tween(
            durationMillis = 210,
            easing = CubicBezierEasing(0.22f, 1f, 0.36f, 1f)
        ),
        label = "profileFocusProgress"
    )

    val itemScale = 1f + (0.15f * focusProgress)
    val borderAlpha = focusProgress
    val focusRingColor = NexioColors.FocusRing

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
            .width(120.dp)
            .clickable(onClick = onClick)
    ) {
        Box {
            Box(
                modifier = Modifier
                    .graphicsLayer {
                        scaleX = itemScale
                        scaleY = itemScale
                    }
                    .then(
                        if (borderAlpha > 0f) {
                            Modifier.border(
                                width = 2.dp,
                                color = focusRingColor.copy(alpha = borderAlpha),
                                shape = CircleShape
                            )
                        } else {
                            Modifier
                        }
                    )
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = 96.dp,
                    avatarImageUrl = profile.avatarUrl
                )
            }

            if (isPinLocked) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = (-2).dp, y = (-2).dp)
                        .size(20.dp)
                        .clip(CircleShape)
                        .background(NexioColors.BackgroundElevated),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "PIN protected",
                        modifier = Modifier.size(16.dp),
                        tint = NexioColors.TextSecondary
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))

        Text(
            text = profile.name,
            style = MaterialTheme.typography.titleLarge,
            color = if (isFocused) NexioColors.TextPrimary else NexioColors.TextSecondary,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

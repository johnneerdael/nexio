package com.nuvio.tv.ui.screens.profile

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Text
import com.nuvio.tv.R
import com.nuvio.tv.domain.model.UserProfile
import com.nuvio.tv.ui.components.ProfileAvatarCircle
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun ProfileSelectionScreen(
    onProfileSelected: () -> Unit,
    viewModel: ProfileSelectionViewModel = hiltViewModel()
) {
    val profiles by viewModel.profiles.collectAsState()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(60.dp))

            Image(
                painter = painterResource(id = R.drawable.app_logo_wordmark),
                contentDescription = "NuvioTV",
                modifier = Modifier
                    .fillMaxWidth(0.15f)
                    .height(42.dp)
            )

            Spacer(modifier = Modifier.height(48.dp))

            Text(
                text = "Who's watching?",
                color = NuvioColors.TextPrimary,
                fontSize = 28.sp,
                fontWeight = FontWeight.SemiBold
            )

            Spacer(modifier = Modifier.weight(1f))

            ProfileGrid(
                profiles = profiles,
                onProfileSelected = { id ->
                    viewModel.selectProfile(id, onComplete = onProfileSelected)
                }
            )

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ProfileGrid(
    profiles: List<UserProfile>,
    onProfileSelected: (Int) -> Unit
) {
    val focusRequesters = remember(profiles.size) {
        List(profiles.size) { FocusRequester() }
    }

    LaunchedEffect(profiles.size) {
        repeat(2) { withFrameNanos { } }
        if (focusRequesters.isNotEmpty()) {
            runCatching { focusRequesters.first().requestFocus() }
        }
    }

    Row(
        horizontalArrangement = Arrangement.spacedBy(32.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        profiles.forEachIndexed { index, profile ->
            ProfileCard(
                profile = profile,
                focusRequester = focusRequesters[index],
                onClick = { onProfileSelected(profile.id) }
            )
        }
    }
}

@Composable
private fun ProfileCard(
    profile: UserProfile,
    focusRequester: FocusRequester,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }
    val avatarColor = runCatching { Color(android.graphics.Color.parseColor(profile.avatarColorHex)) }
        .getOrDefault(Color(0xFF1E88E5))
    val borderColor = if (isFocused) avatarColor else Color.Transparent

    Column(
        modifier = Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { isFocused = it.isFocused }
            .clickable(onClick = onClick)
            .padding(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(modifier = Modifier.size(100.dp)) {
            Box(
                modifier = Modifier
                    .size(100.dp)
                    .border(
                        width = if (isFocused) 3.dp else 0.dp,
                        color = borderColor,
                        shape = CircleShape
                    )
                    .padding(4.dp)
            ) {
                ProfileAvatarCircle(
                    name = profile.name,
                    colorHex = profile.avatarColorHex,
                    size = 92.dp,
                    modifier = Modifier.align(Alignment.Center)
                )
            }

            // Primary profile badge (outside bordered box so it renders on top)
            if (profile.isPrimary) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 2.dp, y = 2.dp)
                        .size(26.dp)
                        .clip(CircleShape)
                        .background(NuvioColors.Background, CircleShape)
                        .padding(2.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFFFB300), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "\u2605",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = profile.name,
            color = if (isFocused) NuvioColors.TextPrimary else NuvioColors.TextSecondary,
            fontSize = 16.sp,
            fontWeight = if (isFocused) FontWeight.SemiBold else FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

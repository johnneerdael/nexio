@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthEvent
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthMode
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthUiState
import com.nexio.tv.ui.theme.NexioColors
import java.text.DateFormat
import java.util.Date

@Composable
fun YouTubeTrailerLoginScreen(
    viewModel: YouTubeTrailerLoginViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = stringResource(R.string.youtube_trailer_login_title),
        subtitle = stringResource(R.string.youtube_trailer_login_subtitle)
    ) {
        YouTubeTrailerLoginContent(viewModel = viewModel)
    }
}

@Composable
fun YouTubeTrailerLoginContent(
    viewModel: YouTubeTrailerLoginViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val verificationLink = uiState.verificationUrlComplete ?: uiState.verificationUrl
    val qrBitmap = remember(verificationLink) {
        verificationLink?.takeIf { it.isNotBlank() }?.let { QrCodeGenerator.generate(it, size = 420) }
    }
    var activationOverlayVisible by rememberSaveable(uiState.userCode, uiState.mode) {
        mutableStateOf(shouldPresentYouTubeTrailerActivationOverlay(uiState))
    }
    LaunchedEffect(uiState.mode, uiState.userCode) {
        activationOverlayVisible = shouldPresentYouTubeTrailerActivationOverlay(uiState)
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        item(key = "youtube_trailer_auth_header") {
            SettingsDetailHeader(
                title = stringResource(R.string.youtube_trailer_login_title),
                subtitle = stringResource(R.string.youtube_trailer_login_subtitle)
            )
        }

        item(key = "youtube_trailer_auth_status") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_status_title),
                    subtitle = uiState.sessionStatusMessage,
                    value = when (uiState.mode) {
                        YouTubeTrailerAuthMode.CONNECTED ->
                            stringResource(R.string.youtube_trailer_login_status_signed_in)
                        YouTubeTrailerAuthMode.AWAITING_APPROVAL ->
                            stringResource(R.string.youtube_trailer_login_status_pending)
                        YouTubeTrailerAuthMode.DISCONNECTED ->
                            stringResource(R.string.youtube_trailer_login_status_signed_out)
                    },
                    enabled = false,
                    onClick = {}
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_access_token_title),
                    subtitle = uiState.accessTokenExpiresAtEpochMs?.let(::formatEpochMs)
                        ?: stringResource(R.string.youtube_trailer_login_not_available),
                    value = if (uiState.hasRefreshToken) {
                        stringResource(R.string.youtube_trailer_login_access_token_connected)
                    } else {
                        stringResource(R.string.youtube_trailer_login_not_available)
                    },
                    enabled = false,
                    onClick = {}
                )
            }
        }

        item(key = "youtube_trailer_auth_actions") {
            SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                SettingsActionRow(
                    title = if (uiState.mode == YouTubeTrailerAuthMode.AWAITING_APPROVAL) {
                        stringResource(R.string.youtube_trailer_login_restart_title)
                    } else {
                        stringResource(R.string.youtube_trailer_login_sign_in_title)
                    },
                    subtitle = if (uiState.mode == YouTubeTrailerAuthMode.AWAITING_APPROVAL) {
                        stringResource(R.string.youtube_trailer_login_restart_subtitle)
                    } else {
                        stringResource(R.string.youtube_trailer_login_sign_in_subtitle)
                    },
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.SignIn) }
                )
                if (shouldPresentYouTubeTrailerActivationOverlay(uiState)) {
                    SettingsActionRow(
                        title = stringResource(R.string.youtube_trailer_login_show_code_title),
                        subtitle = stringResource(R.string.youtube_trailer_login_show_code_subtitle),
                        onClick = { activationOverlayVisible = true }
                    )
                }
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_refresh_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_refresh_subtitle),
                    enabled = uiState.mode != YouTubeTrailerAuthMode.DISCONNECTED || uiState.hasRefreshToken,
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.RefreshSession) }
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_cancel_pending_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_cancel_pending_subtitle),
                    enabled = uiState.mode == YouTubeTrailerAuthMode.AWAITING_APPROVAL,
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.DismissEmbeddedLoginSurface) }
                )
                SettingsActionRow(
                    title = stringResource(R.string.youtube_trailer_login_sign_out_title),
                    subtitle = stringResource(R.string.youtube_trailer_login_sign_out_subtitle),
                    enabled = uiState.isSignedIn,
                    onClick = { viewModel.onEvent(YouTubeTrailerAuthEvent.SignOut) }
                )
            }
        }
    }

    if (activationOverlayVisible && shouldPresentYouTubeTrailerActivationOverlay(uiState)) {
        YouTubeTrailerActivationDialog(
            uiState = uiState,
            verificationLink = verificationLink,
            qrBitmap = qrBitmap,
            onDismiss = { activationOverlayVisible = false }
        )
    }
}

private fun formatEpochMs(epochMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMs))
}

internal fun shouldPresentYouTubeTrailerActivationOverlay(
    uiState: YouTubeTrailerAuthUiState
): Boolean {
    return uiState.mode == YouTubeTrailerAuthMode.AWAITING_APPROVAL &&
        !uiState.userCode.isNullOrBlank()
}

@Composable
private fun YouTubeTrailerActivationDialog(
    uiState: YouTubeTrailerAuthUiState,
    verificationLink: String?,
    qrBitmap: android.graphics.Bitmap?,
    onDismiss: () -> Unit
) {
    BackHandler(onBack = onDismiss)
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight()
                .background(Color.Black.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Column(
                modifier = Modifier
                    .width(760.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .background(NexioColors.BackgroundElevated)
                    .border(1.dp, NexioColors.Border, androidx.compose.foundation.shape.RoundedCornerShape(24.dp))
                    .padding(horizontal = 36.dp, vertical = 28.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.youtube_trailer_login_qr_title),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.youtube_trailer_login_overlay_subtitle),
                    color = NexioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                qrBitmap?.let { bitmap ->
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = stringResource(R.string.youtube_trailer_login_qr_title),
                        modifier = Modifier.size(280.dp)
                    )
                }
                Text(
                    text = verificationLink ?: stringResource(R.string.youtube_trailer_login_not_available),
                    color = NexioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = uiState.userCode.orEmpty(),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 42.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 2.sp,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(R.string.youtube_trailer_login_overlay_footer),
                    color = NexioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = uiState.sessionStatusMessage,
                    color = NexioColors.TextPrimary,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = stringResource(
                        R.string.youtube_trailer_login_overlay_meta,
                        uiState.deviceCodeExpiresAtEpochMs?.let(::formatEpochMs)
                            ?: stringResource(R.string.youtube_trailer_login_not_available),
                        uiState.pollIntervalSeconds
                    ),
                    color = NexioColors.TextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

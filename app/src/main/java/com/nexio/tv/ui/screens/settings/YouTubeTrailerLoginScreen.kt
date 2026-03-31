@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthEvent
import com.nexio.tv.data.trailer.helper.YouTubeTrailerAuthMode
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

        if (!uiState.userCode.isNullOrBlank()) {
            item(key = "youtube_trailer_auth_code") {
                SettingsGroupCard(modifier = Modifier.fillMaxWidth()) {
                    Text(text = stringResource(R.string.youtube_trailer_login_qr_title))
                    Text(text = stringResource(R.string.youtube_trailer_login_qr_subtitle))
                    qrBitmap?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = stringResource(R.string.youtube_trailer_login_qr_title),
                            modifier = Modifier
                                .padding(top = 12.dp)
                                .height(220.dp)
                        )
                    }
                    SettingsActionRow(
                        title = stringResource(R.string.youtube_trailer_login_code_title),
                        subtitle = uiState.userCode,
                        value = uiState.userCode,
                        enabled = false,
                        onClick = {}
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.youtube_trailer_login_verification_url_title),
                        subtitle = verificationLink ?: stringResource(R.string.youtube_trailer_login_not_available),
                        value = verificationLink ?: stringResource(R.string.youtube_trailer_login_not_available),
                        enabled = false,
                        onClick = {}
                    )
                    SettingsActionRow(
                        title = stringResource(R.string.youtube_trailer_login_code_expiry_title),
                        subtitle = uiState.deviceCodeExpiresAtEpochMs?.let(::formatEpochMs)
                            ?: stringResource(R.string.youtube_trailer_login_not_available),
                        value = "${uiState.pollIntervalSeconds}s",
                        enabled = false,
                        onClick = {}
                    )
                }
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
}

private fun formatEpochMs(epochMs: Long): String {
    return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date(epochMs))
}

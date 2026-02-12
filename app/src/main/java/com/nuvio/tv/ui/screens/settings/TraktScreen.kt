@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.annotation.RawRes
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import coil.compose.rememberAsyncImagePainter
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import com.nuvio.tv.R
import com.nuvio.tv.core.qr.QrCodeGenerator
import com.nuvio.tv.ui.theme.NuvioColors
import kotlinx.coroutines.delay
import java.util.concurrent.TimeUnit

@Composable
fun TraktScreen(
    viewModel: TraktViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val primaryFocusRequester = remember { FocusRequester() }

    BackHandler { onBackPress() }

    val nowMillis by produceState(initialValue = System.currentTimeMillis(), key1 = uiState.mode) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }

    LaunchedEffect(uiState.mode) {
        primaryFocusRequester.requestFocus()
    }

    val userCode = uiState.deviceUserCode
    val qrBitmap = remember(userCode) {
        userCode?.let {
            runCatching { QrCodeGenerator.generate("https://trakt.tv/activate/$it", 420) }.getOrNull()
        }
    }
    val traktLogoPainter = rememberRawSvgPainter(R.raw.trakt_tv_favicon)

    Row(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(36.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(0.45f)
                .fillMaxHeight(),
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = traktLogoPainter,
                contentDescription = "Trakt Logo",
                modifier = Modifier.size(96.dp),
                contentScale = ContentScale.Fit
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Trakt",
                style = MaterialTheme.typography.headlineLarge,
                color = NuvioColors.TextPrimary
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "Cloud sync for watch progress, scrobbling, and continue watching.",
                style = MaterialTheme.typography.bodyLarge,
                color = NuvioColors.TextSecondary
            )
            if (uiState.mode == TraktConnectionMode.CONNECTED) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Connected as ${uiState.username ?: "Trakt user"}",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF7CFF9B)
                )
            }
        }

        Column(
            modifier = Modifier
                .weight(0.55f)
                .fillMaxHeight()
                .border(1.dp, NuvioColors.Border.copy(alpha = 0.5f), RoundedCornerShape(18.dp))
                .background(NuvioColors.BackgroundElevated.copy(alpha = 0.35f), RoundedCornerShape(18.dp))
                .padding(26.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            val expiresAt = uiState.deviceCodeExpiresAtMillis
            val remaining = expiresAt?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Account Login",
                    style = MaterialTheme.typography.titleLarge,
                    color = NuvioColors.TextPrimary
                )
                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Button(
                        onClick = { viewModel.onCancelDeviceFlow() },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            contentColor = NuvioColors.TextPrimary
                        )
                    ) {
                        Text("Cancel")
                    }
                }
            }

            if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                Text(
                    text = "Go to trakt.tv/activate and enter this code:",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
                Text(
                    text = userCode ?: "-",
                    color = NuvioColors.Primary,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 6.sp
                )
                if (qrBitmap != null) {
                    Image(
                        bitmap = qrBitmap.asImageBitmap(),
                        contentDescription = "Trakt activation QR",
                        modifier = Modifier.size(220.dp),
                        contentScale = ContentScale.Fit
                    )
                }
                Text(
                    text = "Code expires in ${formatDuration(remaining)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            } else if (uiState.mode == TraktConnectionMode.CONNECTED) {
                uiState.tokenExpiresAtMillis?.let { expiresAtMillis ->
                    Text(
                        text = "Token refresh target: ${formatDuration((expiresAtMillis - nowMillis).coerceAtLeast(0L))}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                }
                Text(
                    text = uiState.statusMessage ?: "Watch progress and scrobbling are Trakt-backed.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
                Button(
                    onClick = { viewModel.onDisconnectClick() },
                    modifier = Modifier.focusRequester(primaryFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Disconnect")
                }
            } else {
                Text(
                    text = "Press Login to start Trakt device authentication. A QR code will appear here.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = NuvioColors.TextSecondary
                )
                Button(
                    onClick = { viewModel.onConnectClick() },
                    enabled = uiState.credentialsConfigured && !uiState.isLoading,
                    modifier = Modifier.focusRequester(primaryFocusRequester),
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.Primary,
                        contentColor = Color.Black
                    )
                ) {
                    Text("Login")
                }
                if (!uiState.credentialsConfigured) {
                    Text(
                        text = "Missing TRAKT_CLIENT_ID / TRAKT_CLIENT_SECRET in local.properties.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFFFFB74D)
                    )
                }
            }

            uiState.statusMessage?.let { status ->
                Text(
                    text = status,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextSecondary
                )
            }

            uiState.errorMessage?.let { error ->
                Text(
                    text = error,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFFF6E6E)
                )
            }

            Spacer(modifier = Modifier.weight(1f))

            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                if (uiState.mode == TraktConnectionMode.AWAITING_APPROVAL) {
                    Button(
                        onClick = { viewModel.onRetryPolling() },
                        enabled = !uiState.isLoading,
                        modifier = Modifier.focusRequester(primaryFocusRequester)
                    ) {
                        Text("Retry")
                    }
                }
                Button(
                    onClick = onBackPress,
                    colors = ButtonDefaults.colors(
                        containerColor = NuvioColors.BackgroundCard,
                        contentColor = NuvioColors.TextPrimary
                    )
                ) {
                    Text("Back")
                }
            }
        }
    }
}

@Composable
private fun rememberRawSvgPainter(@RawRes iconRes: Int) = rememberAsyncImagePainter(
    model = ImageRequest.Builder(LocalContext.current)
        .data(iconRes)
        .decoderFactory(SvgDecoder.Factory())
        .build()
)

private fun formatDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000L).coerceAtLeast(0L)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
    val seconds = totalSeconds - (minutes * 60)
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}

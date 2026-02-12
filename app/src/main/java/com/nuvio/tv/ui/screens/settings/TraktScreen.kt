@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(NuvioColors.Background)
            .padding(horizontal = 48.dp, vertical = 28.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Trakt",
            style = MaterialTheme.typography.headlineLarge,
            color = NuvioColors.TextPrimary
        )
        Text(
            text = "Connect your Trakt account for cloud watch progress and scrobbling.",
            style = MaterialTheme.typography.bodyMedium,
            color = NuvioColors.TextSecondary
        )

        uiState.errorMessage?.let { error ->
            StatusCard(
                title = "Error",
                body = error,
                borderColor = Color(0xFFFF6E6E)
            )
        }

        when (uiState.mode) {
            TraktConnectionMode.DISCONNECTED -> {
                if (!uiState.credentialsConfigured) {
                    StatusCard(
                        title = "Credentials Missing",
                        body = "Set TRAKT_CLIENT_ID and TRAKT_CLIENT_SECRET in local.properties, then rebuild.",
                        borderColor = Color(0xFFFFB74D)
                    )
                }

                ActionCard {
                    Button(
                        onClick = { viewModel.onConnectClick() },
                        enabled = uiState.credentialsConfigured && !uiState.isLoading,
                        modifier = Modifier.focusRequester(primaryFocusRequester),
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.Primary,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Connect Trakt")
                    }
                }
            }

            TraktConnectionMode.AWAITING_APPROVAL -> {
                val expiresAt = uiState.deviceCodeExpiresAtMillis
                val remaining = expiresAt?.let { (it - nowMillis).coerceAtLeast(0L) } ?: 0L

                ActionCard {
                    Text(
                        text = "Go to trakt.tv/activate",
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    Text(
                        text = userCode ?: "-",
                        color = NuvioColors.Primary,
                        fontSize = 48.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 6.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Expires in ${formatDuration(remaining)}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    if (qrBitmap != null) {
                        Image(
                            bitmap = qrBitmap.asImageBitmap(),
                            contentDescription = "Trakt activation QR",
                            modifier = Modifier.size(220.dp),
                            contentScale = ContentScale.Fit
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = uiState.statusMessage ?: "Waiting for approval...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.onRetryPolling() },
                            enabled = !uiState.isLoading,
                            modifier = Modifier.focusRequester(primaryFocusRequester)
                        ) {
                            Text("Retry")
                        }
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
            }

            TraktConnectionMode.CONNECTED -> {
                ActionCard {
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF7CFF9B)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.username ?: "Trakt user",
                        style = MaterialTheme.typography.headlineSmall,
                        color = NuvioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    uiState.tokenExpiresAtMillis?.let { expiresAt ->
                        Text(
                            text = "Token refresh target: ${formatDuration((expiresAt - nowMillis).coerceAtLeast(0L))}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = NuvioColors.TextSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                    }
                    Text(
                        text = uiState.statusMessage ?: "Watch progress and scrobbling are now Trakt-backed.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = NuvioColors.TextSecondary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.onSyncNow() },
                            modifier = Modifier.focusRequester(primaryFocusRequester)
                        ) {
                            Text("Sync now")
                        }
                        Button(
                            onClick = { viewModel.onDisconnectClick() },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                contentColor = NuvioColors.TextPrimary
                            )
                        ) {
                            Text("Disconnect")
                        }
                    }
                }
            }
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onBackPress,
            colors = ButtonDefaults.colors(
                containerColor = NuvioColors.BackgroundCard,
                contentColor = NuvioColors.TextPrimary
            ),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp)
        ) {
            Text("Back")
        }
    }
}

@Composable
private fun ActionCard(
    content: @Composable () -> Unit
) {
    Card(
        onClick = { },
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundElevated,
            focusedContainerColor = NuvioColors.BackgroundElevated
        ),
        border = CardDefaults.border(
            border = Border(
                border = BorderStroke(1.dp, NuvioColors.Border),
                shape = RoundedCornerShape(16.dp)
            )
        ),
        shape = CardDefaults.shape(RoundedCornerShape(16.dp)),
        scale = CardDefaults.scale(focusedScale = 1f)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            content()
        }
    }
}

@Composable
private fun StatusCard(
    title: String,
    body: String,
    borderColor: Color
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(NuvioColors.BackgroundCard, RoundedCornerShape(14.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(10.dp)
                        .background(borderColor, RoundedCornerShape(50))
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    color = NuvioColors.TextPrimary
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = NuvioColors.TextSecondary
            )
        }
    }
}

private fun formatDuration(valueMs: Long): String {
    val totalSeconds = (valueMs / 1000L).coerceAtLeast(0L)
    val minutes = TimeUnit.SECONDS.toMinutes(totalSeconds)
    val seconds = totalSeconds - (minutes * 60)
    return if (minutes > 0) "${minutes}m ${seconds}s" else "${seconds}s"
}


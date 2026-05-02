@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.ui.theme.NexioColors

private const val WYZIE_REDEEM_URL = "https://sub.wyzie.io/redeem"

@Composable
fun WyzieSubtitleSettingsScreen(
    onEnterApiKey: () -> Unit,
    viewModel: WyzieSubtitleSettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var showQr by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "Wyzie subtitles",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Built-in subtitle search across OpenSubtitles, SubDL, Subf2m, Podnapisi, and more. Free with your own Wyzie API key.",
            fontSize = 16.sp,
        )

        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = state.enabled,
                onCheckedChange = viewModel::onSetEnabled,
            )
            Spacer(Modifier.width(16.dp))
            Text(text = if (state.enabled) "Enabled" else "Disabled")
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "API key:",
                modifier = Modifier.width(140.dp),
            )
            Text(text = maskWyzieKey(state.apiKey))
        }

        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Button(onClick = onEnterApiKey) { Text("Enter key") }
            Button(
                onClick = { showQr = !showQr },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) {
                Text(if (showQr) "Hide QR" else "Get a free key")
            }
            if (!state.apiKey.isNullOrBlank()) {
                Button(
                    onClick = viewModel::onClearApiKey,
                    colors = ButtonDefaults.colors(
                        containerColor = NexioColors.BackgroundCard,
                        contentColor = NexioColors.TextPrimary,
                    ),
                ) { Text("Clear key") }
            }
        }

        if (showQr) {
            WyzieQrCode(url = WYZIE_REDEEM_URL)
            Text(
                text = "Scan with your phone to redeem a free key at $WYZIE_REDEEM_URL",
                fontSize = 14.sp,
            )
        }
    }
}

@Composable
private fun WyzieQrCode(url: String) {
    val bitmap = remember(url) {
        runCatching { QrCodeGenerator.generate(url, 256) }.getOrNull()
    }
    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "QR code linking to $url",
            modifier = Modifier.size(256.dp),
        )
    }
}

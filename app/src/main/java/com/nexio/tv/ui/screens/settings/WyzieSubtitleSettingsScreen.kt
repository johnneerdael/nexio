@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import android.view.KeyEvent
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Switch
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.core.qr.QrCodeGenerator
import com.nexio.tv.ui.components.NexioDialog
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

/**
 * Settings-screen integration composable for the integration hub.
 * Owns the API-key input dialog state so [WyzieSubtitleSettingsScreen] stays
 * free of nav dependencies.
 */
@Composable
fun WyzieSubtitleSettingsContent(
    viewModel: WyzieSubtitleSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
) {
    var showApiKeyDialog by remember { mutableStateOf(false) }

    WyzieSubtitleSettingsScreen(
        onEnterApiKey = { showApiKeyDialog = true },
        viewModel = viewModel,
    )

    if (showApiKeyDialog) {
        val state by viewModel.state.collectAsStateWithLifecycle()
        WyzieApiKeyDialog(
            currentValue = state.apiKey.orEmpty(),
            onSave = { value ->
                viewModel.onSetApiKey(value)
                showApiKeyDialog = false
            },
            onDismiss = { showApiKeyDialog = false },
        )
    }
}

@Composable
private fun WyzieApiKeyDialog(
    currentValue: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember(currentValue) { mutableStateOf(currentValue) }
    var isInputFocused by remember { mutableStateOf(false) }
    val inputFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    NexioDialog(
        onDismiss = onDismiss,
        title = "Wyzie API key",
        subtitle = "Paste your Wyzie API key below. Get a free key at sub.wyzie.io/redeem.",
        width = 700.dp,
    ) {
        Card(
            onClick = { inputFocusRequester.requestFocus() },
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { isInputFocused = it.isFocused || it.hasFocus },
            colors = CardDefaults.colors(
                containerColor = NexioColors.BackgroundElevated,
                focusedContainerColor = NexioColors.BackgroundElevated,
            ),
            border = CardDefaults.border(
                border = Border(
                    border = BorderStroke(1.dp, NexioColors.Border),
                    shape = RoundedCornerShape(10.dp),
                ),
                focusedBorder = Border(
                    border = BorderStroke(2.dp, NexioColors.FocusRing),
                    shape = RoundedCornerShape(10.dp),
                ),
            ),
            shape = CardDefaults.shape(RoundedCornerShape(10.dp)),
            scale = CardDefaults.scale(focusedScale = 1f),
        ) {
            Box(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                BasicTextField(
                    value = value,
                    onValueChange = { value = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(inputFocusRequester)
                        .onKeyEvent { event ->
                            event.nativeKeyEvent.keyCode == KeyEvent.KEYCODE_DPAD_CENTER &&
                                event.nativeKeyEvent.action == KeyEvent.ACTION_DOWN
                        },
                    singleLine = true,
                    keyboardActions = KeyboardActions(onDone = { keyboardController?.hide() }),
                    textStyle = MaterialTheme.typography.bodyMedium.copy(color = NexioColors.TextPrimary),
                    cursorBrush = SolidColor(
                        if (isInputFocused) NexioColors.Primary
                        else androidx.compose.ui.graphics.Color.Transparent
                    ),
                    decorationBox = { innerTextField ->
                        if (value.isBlank()) {
                            Text(
                                text = "Paste API key…",
                                style = MaterialTheme.typography.bodyMedium,
                                color = NexioColors.TextTertiary,
                            )
                        }
                        innerTextField()
                    },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundElevated,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) { Text(stringResource(R.string.action_cancel)) }

            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSave(value) },
                colors = ButtonDefaults.colors(
                    containerColor = NexioColors.BackgroundCard,
                    contentColor = NexioColors.TextPrimary,
                ),
            ) { Text(stringResource(R.string.action_save)) }
        }
    }
}

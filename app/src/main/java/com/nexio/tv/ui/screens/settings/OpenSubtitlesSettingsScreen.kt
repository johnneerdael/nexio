@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Switch
import androidx.tv.material3.Text

@Composable
fun OpenSubtitlesSettingsContent(
    viewModel: OpenSubtitlesSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(48.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp),
    ) {
        Text(
            text = "OpenSubtitles",
            fontSize = 28.sp,
            fontWeight = FontWeight.SemiBold,
        )
        Text(
            text = "Native subtitle search through rest.opensubtitles.org with exact file-hash matching.",
            fontSize = 16.sp,
        )

        OpenSubtitlesToggleRow(
            label = if (state.enabled) "Enabled" else "Disabled",
            checked = state.enabled,
            onCheckedChange = viewModel::onSetEnabled,
        )
        OpenSubtitlesToggleRow(
            label = "Trusted uploaders only",
            checked = state.onlyTrusted,
            enabled = state.enabled,
            onCheckedChange = viewModel::onSetOnlyTrusted,
        )
        OpenSubtitlesToggleRow(
            label = "Include AI translated subtitles",
            checked = state.includeAiTranslated,
            enabled = state.enabled,
            onCheckedChange = viewModel::onSetIncludeAiTranslated,
        )
    }
}

@Composable
private fun OpenSubtitlesToggleRow(
    label: String,
    checked: Boolean,
    enabled: Boolean = true,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Switch(
            checked = checked,
            enabled = enabled,
            onCheckedChange = onCheckedChange,
        )
        Spacer(Modifier.width(16.dp))
        Text(text = label)
    }
}

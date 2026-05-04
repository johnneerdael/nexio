@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.tv.material3.ExperimentalTvMaterial3Api
import com.nexio.tv.R

@Composable
fun TheIntroDbSettingsContent(
    viewModel: TheIntroDbSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = stringResource(R.string.theid_title),
            subtitle = stringResource(R.string.theid_subtitle)
        )

        SettingsGroupCard(
            modifier = Modifier.fillMaxWidth().weight(1f)
        ) {
            LazyColumn(
                contentPadding = PaddingValues(bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                item(key = "theintrodb_show_intro") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_intro_title),
                        subtitle = stringResource(R.string.theid_show_intro_subtitle),
                        checked = settings.showIntroButton,
                        onToggle = { viewModel.setShowIntroButton(!settings.showIntroButton) },
                        modifier = if (initialFocusRequester != null) Modifier.focusRequester(initialFocusRequester) else Modifier
                    )
                }
                item(key = "theintrodb_show_recap") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_recap_title),
                        subtitle = stringResource(R.string.theid_show_recap_subtitle),
                        checked = settings.showRecapButton,
                        onToggle = { viewModel.setShowRecapButton(!settings.showRecapButton) }
                    )
                }
                item(key = "theintrodb_show_credits") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_credits_title),
                        subtitle = stringResource(R.string.theid_show_credits_subtitle),
                        checked = settings.showCreditsButton,
                        onToggle = { viewModel.setShowCreditsButton(!settings.showCreditsButton) }
                    )
                }
                item(key = "theintrodb_show_preview") {
                    SettingsToggleRow(
                        title = stringResource(R.string.theid_show_preview_title),
                        subtitle = stringResource(R.string.theid_show_preview_subtitle),
                        checked = settings.showPreviewButton,
                        onToggle = { viewModel.setShowPreviewButton(!settings.showPreviewButton) }
                    )
                }
            }
        }
    }
}

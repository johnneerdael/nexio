package com.nexio.tv.ui.screens.settings

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import com.nexio.tv.integrations.hyperhdr.ui.HyperHdrSettingsContent
import com.nexio.tv.integrations.hyperhdr.ui.HyperHdrSettingsViewModel

@Composable
fun HyperHdrSettingsScreen(
    viewModel: HyperHdrSettingsViewModel = hiltViewModel(),
    onBackPress: () -> Unit
) {
    BackHandler { onBackPress() }

    SettingsStandaloneScaffold(
        title = "HyperHDR ambilight",
        subtitle = "Stream decoded video frames to a HyperHDR LED server during playback. Default off."
    ) {
        HyperHdrSettingsContent(viewModel = viewModel)
    }
}

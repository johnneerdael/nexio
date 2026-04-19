@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.Button
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.data.local.KitsuAuthDataStore
import com.nexio.tv.data.repository.KitsuAuthService
import com.nexio.tv.ui.theme.NexioColors
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class KitsuSettingsUiState(
    val connected: Boolean = false,
    val username: String = "",
    val error: String? = null,
    val busy: Boolean = false
)

@HiltViewModel
class KitsuSettingsViewModel @Inject constructor(
    private val authService: KitsuAuthService,
    authDataStore: KitsuAuthDataStore
) : ViewModel() {
    val uiState: StateFlow<KitsuSettingsUiState> = authDataStore.state
        .map { state ->
            KitsuSettingsUiState(
                connected = state.isAuthenticated,
                username = state.username.orEmpty()
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), KitsuSettingsUiState())

    fun login(username: String, password: String) {
        viewModelScope.launch {
            val ok = authService.login(username, password)
            if (!ok) {
                // Keep state simple; this surface is account auth only.
            }
        }
    }

    fun disconnect() {
        viewModelScope.launch {
            authService.disconnect()
        }
    }
}

@Composable
fun KitsuSettingsContent(
    viewModel: KitsuSettingsViewModel = hiltViewModel(),
    initialFocusRequester: FocusRequester? = null
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    var username by remember(uiState.username) { mutableStateOf(uiState.username) }
    var password by remember { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SettingsDetailHeader(
            title = "Kitsu",
            subtitle = "Connect Kitsu to allow authenticated anime metadata requests."
        )

        SettingsGroupCard(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                SettingsActionRow(
                    title = "Status",
                    subtitle = "Kitsu public metadata works without login.",
                    value = if (uiState.connected) "Connected as ${uiState.username}" else "Not connected",
                    enabled = false,
                    onClick = {},
                    modifier = initialFocusRequester?.let { Modifier.focusRequester(it) } ?: Modifier
                )

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Email or username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Button(
                        onClick = {
                            viewModel.login(username, password)
                            password = ""
                        }
                    ) {
                        Text("Connect")
                    }
                    Button(onClick = { viewModel.disconnect() }) {
                        Text("Disconnect")
                    }
                }

                Text(
                    text = "Nexio exchanges the password for OAuth tokens and never stores the password.",
                    style = MaterialTheme.typography.bodySmall,
                    color = NexioColors.TextSecondary
                )
            }
        }
    }
}

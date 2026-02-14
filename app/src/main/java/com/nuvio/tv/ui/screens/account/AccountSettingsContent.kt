@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nuvio.tv.ui.screens.account

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Logout
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Button
import androidx.tv.material3.ButtonDefaults
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nuvio.tv.domain.model.AuthState
import com.nuvio.tv.ui.theme.NuvioColors

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    onNavigateToSyncGenerate: () -> Unit = {},
    onNavigateToSyncClaim: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item {
                    Box(
                        modifier = Modifier.fillMaxWidth(),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Loading...",
                            style = MaterialTheme.typography.bodyLarge,
                            color = NuvioColors.TextSecondary
                        )
                    }
                }
            }

            is AuthState.SignedOut -> {
                item {
                    Text(
                        text = "Sync your library, watch progress, addons, and plugins across devices. Library and watch progress sync only when Trakt is not connected.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = NuvioColors.TextSecondary
                    )
                }
                item {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = "Generate Sync Code",
                        subtitle = "Create a code to share with your other devices",
                        onClick = onNavigateToSyncGenerate
                    )
                }
                item {
                    SettingsActionButton(
                        icon = Icons.Default.Sync,
                        title = "Enter Sync Code",
                        subtitle = "Link this device using a code from another device",
                        onClick = onNavigateToSyncClaim
                    )
                }
            }

            is AuthState.FullAccount -> {
                item {
                    StatusCard(
                        label = "Signed in",
                        value = authState.email
                    )
                }
                item {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = "Generate Sync Code",
                        subtitle = "Share a code so other devices can link to this account",
                        onClick = onNavigateToSyncGenerate
                    )
                }
                item {
                    SettingsActionButton(
                        icon = Icons.Default.Sync,
                        title = "Enter Sync Code",
                        subtitle = "Link this device using a code from another device",
                        onClick = onNavigateToSyncClaim
                    )
                }
                item {
                    SignOutSettingsButton(onClick = { viewModel.signOut() })
                }
            }

            is AuthState.Anonymous -> {
                item {
                    StatusCard(
                        label = "Synced",
                        value = "Using sync code for cross-device sync"
                    )
                }
                item {
                    ShowSyncCodeSection(
                        uiState = uiState,
                        viewModel = viewModel
                    )
                }
                item {
                    SignOutSettingsButton(onClick = { viewModel.signOut() })
                }
            }
        }
    }
}

@Composable
private fun ShowSyncCodeSection(
    uiState: AccountUiState,
    viewModel: AccountViewModel
) {
    var expanded by remember { mutableStateOf(false) }
    var pin by remember { mutableStateOf("") }

    Column(
        modifier = Modifier.fillMaxWidth()
    ) {
        if (uiState.generatedSyncCode != null) {
            // Show the revealed sync code
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioColors.Secondary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "Your Sync Code",
                        style = MaterialTheme.typography.labelMedium,
                        color = NuvioColors.TextSecondary
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = uiState.generatedSyncCode ?: "",
                        style = MaterialTheme.typography.titleLarge.copy(
                            letterSpacing = 1.sp,
                            fontWeight = FontWeight.Bold
                        ),
                        color = NuvioColors.Secondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "Enter this code and your PIN on your other device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = NuvioColors.TextTertiary,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Button(
                        onClick = {
                            viewModel.clearGeneratedSyncCode()
                            expanded = false
                            pin = ""
                        },
                        colors = ButtonDefaults.colors(
                            containerColor = NuvioColors.BackgroundCard,
                            focusedContainerColor = NuvioColors.FocusBackground,
                            contentColor = NuvioColors.TextPrimary,
                            focusedContentColor = NuvioColors.TextPrimary
                        ),
                        shape = ButtonDefaults.shape(RoundedCornerShape(50))
                    ) {
                        Text("Hide Code", modifier = Modifier.padding(vertical = 2.dp))
                    }
                }
            }
        } else if (expanded) {
            // PIN entry to reveal the code
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        color = NuvioColors.BackgroundCard,
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(20.dp)
            ) {
                Column {
                    Text(
                        text = "Enter your PIN to reveal sync code",
                        style = MaterialTheme.typography.titleMedium,
                        color = NuvioColors.TextPrimary
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    InputField(
                        value = pin,
                        onValueChange = { if (it.length <= 8) pin = it },
                        placeholder = "Enter PIN",
                        keyboardType = KeyboardType.NumberPassword,
                        isPassword = true,
                        imeAction = ImeAction.Done,
                        onImeAction = {
                            if (pin.length >= 4) viewModel.getSyncCode(pin)
                        }
                    )
                    if (uiState.error != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = uiState.error ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFF44336)
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(
                            onClick = { viewModel.getSyncCode(pin) },
                            enabled = !uiState.isLoading && pin.length >= 4,
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.Secondary,
                                focusedContainerColor = NuvioColors.SecondaryVariant,
                                contentColor = Color.White,
                                focusedContentColor = Color.White
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text(
                                text = if (uiState.isLoading) "Loading..." else "Show Code",
                                modifier = Modifier.padding(vertical = 2.dp),
                                fontWeight = FontWeight.Medium
                            )
                        }
                        Button(
                            onClick = {
                                expanded = false
                                pin = ""
                                viewModel.clearError()
                            },
                            colors = ButtonDefaults.colors(
                                containerColor = NuvioColors.BackgroundCard,
                                focusedContainerColor = NuvioColors.FocusBackground,
                                contentColor = NuvioColors.TextSecondary,
                                focusedContentColor = NuvioColors.TextPrimary
                            ),
                            shape = ButtonDefaults.shape(RoundedCornerShape(50))
                        ) {
                            Text("Cancel", modifier = Modifier.padding(vertical = 2.dp))
                        }
                    }
                }
            }
        } else {
            // Collapsed state — show the action button
            SettingsActionButton(
                icon = Icons.Default.VpnKey,
                title = "Show Sync Code",
                subtitle = "Enter your PIN to reveal your sync code",
                onClick = { expanded = true }
            )
        }
    }
}

@Composable
private fun SettingsActionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    var isFocused by remember { mutableStateOf(false) }

    Card(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .onFocusChanged { isFocused = it.isFocused },
        colors = CardDefaults.colors(
            containerColor = NuvioColors.BackgroundCard,
            focusedContainerColor = NuvioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NuvioColors.FocusRing),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(28.dp),
                tint = if (isFocused) NuvioColors.Primary else NuvioColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = NuvioColors.TextPrimary
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = NuvioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NuvioColors.Secondary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(12.dp)
            )
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = NuvioColors.Secondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelSmall,
                    color = NuvioColors.TextTertiary
                )
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NuvioColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
private fun SignOutSettingsButton(onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.colors(
            containerColor = Color(0xFFC62828).copy(alpha = 0.12f),
            focusedContainerColor = Color(0xFFC62828).copy(alpha = 0.25f)
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, Color(0xFFF44336).copy(alpha = 0.5f)),
                shape = RoundedCornerShape(12.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(12.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Logout,
                contentDescription = null,
                modifier = Modifier.size(20.dp),
                tint = Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "Sign Out",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

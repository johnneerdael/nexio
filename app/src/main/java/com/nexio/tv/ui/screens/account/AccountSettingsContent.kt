@file:OptIn(ExperimentalTvMaterial3Api::class)

package com.nexio.tv.ui.screens.account

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Border
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.nexio.tv.R
import com.nexio.tv.domain.model.AuthState
import com.nexio.tv.ui.theme.NexioColors

@Composable
fun AccountSettingsContent(
    uiState: AccountUiState,
    viewModel: AccountViewModel,
    onNavigateToAuthQrSignIn: () -> Unit = {}
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        when (val authState = uiState.authState) {
            is AuthState.Loading -> {
                item(key = "account_loading") {
                    Text(
                        text = stringResource(R.string.account_loading),
                        style = MaterialTheme.typography.bodyMedium,
                        color = NexioColors.TextSecondary
                    )
                }
            }

            is AuthState.SignedOut -> {
                item(key = "account_signed_out_info") {
                    Text(
                        text = stringResource(R.string.account_sync_description),
                        style = MaterialTheme.typography.bodySmall,
                        color = NexioColors.TextSecondary
                    )
                }
                item(key = "account_sign_in_qr") {
                    SettingsActionButton(
                        icon = Icons.Default.VpnKey,
                        title = stringResource(R.string.account_signin_qr_title),
                        subtitle = stringResource(R.string.account_signin_qr_subtitle),
                        onClick = onNavigateToAuthQrSignIn
                    )
                }
            }

            is AuthState.FullAccount -> {
                item(key = "account_status") {
                    StatusCard(label = stringResource(R.string.account_signed_in_label), value = authState.email)
                }
                item(key = "account_sign_out") { SignOutSettingsButton(onClick = { viewModel.signOut() }) }
            }
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
            containerColor = NexioColors.BackgroundCard,
            focusedContainerColor = NexioColors.FocusBackground
        ),
        border = CardDefaults.border(
            focusedBorder = Border(
                border = BorderStroke(2.dp, NexioColors.FocusRing),
                shape = RoundedCornerShape(8.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(22.dp),
                tint = if (isFocused) NexioColors.Primary else NexioColors.TextSecondary
            )
            Spacer(modifier = Modifier.width(12.dp))
            androidx.compose.foundation.layout.Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.bodyMedium,
                    color = NexioColors.TextPrimary,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = NexioColors.TextSecondary
                )
            }
        }
    }
}

@Composable
private fun StatusCard(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                color = NexioColors.Secondary.copy(alpha = 0.1f),
                shape = RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(16.dp),
            tint = NexioColors.Secondary
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = "$label  ",
            style = MaterialTheme.typography.labelSmall,
            color = NexioColors.TextTertiary
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = NexioColors.TextPrimary,
            fontWeight = FontWeight.Medium
        )
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
                shape = RoundedCornerShape(8.dp)
            )
        ),
        shape = CardDefaults.shape(shape = RoundedCornerShape(8.dp)),
        scale = CardDefaults.scale(focusedScale = 1.02f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Logout,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = Color(0xFFF44336)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = stringResource(R.string.account_sign_out),
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFF44336),
                fontWeight = FontWeight.Medium
            )
        }
    }
}

package com.nexio.tv.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

@Composable
fun ProfileAvatarImage(
    avatarUrl: String?,
    avatarColorHex: String,
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 80.dp,
    isSelected: Boolean = false
) {
    ProfileAvatarCircle(
        name = name,
        colorHex = avatarColorHex,
        modifier = modifier,
        size = size,
        isSelected = isSelected,
        avatarImageUrl = avatarUrl
    )
}

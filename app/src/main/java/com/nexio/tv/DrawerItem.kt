package com.nexio.tv

import androidx.compose.ui.graphics.vector.ImageVector

data class DrawerItem(
    val route: String,
    val label: String,
    val iconRes: Int? = null,
    val icon: ImageVector? = null
)

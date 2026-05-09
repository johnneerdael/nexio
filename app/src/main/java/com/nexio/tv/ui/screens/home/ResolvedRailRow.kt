package com.nexio.tv.ui.screens.home

import androidx.compose.runtime.Immutable

@Immutable
data class ResolvedRailRow(
    val catalogId: String,
    val title: String,
    val items: List<ModernHomeRowItem>
)

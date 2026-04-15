package com.nexio.tv.data.repository

import com.nexio.tv.domain.model.TrackingProvider

data class TrackingAuthSession(
    val provider: TrackingProvider,
    val profileId: Int
)

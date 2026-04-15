package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.SecondaryProfileRuntimeContext

internal sealed interface HomeProfileSession {
    val profileId: Int
    val generation: Long

    data class DefaultLegacy(
        override val generation: Long
    ) : HomeProfileSession {
        override val profileId: Int = 1
    }

    data class Secondary(
        override val profileId: Int,
        override val generation: Long,
        val boundaryContext: SecondaryProfileRuntimeContext
    ) : HomeProfileSession
}

package com.nexio.tv.ui.screens.home

import com.nexio.tv.core.profile.SecondaryProfileRuntimeContext
import kotlinx.coroutines.flow.StateFlow

internal sealed interface HomeProfileSession {
    val profileId: Int
    val generation: Long
    val sessionId: String
    val profileSessionKey: String
    val language: String
    val subtitleLanguage: String?
    val startedAtMs: Long

    data class DefaultLegacy(
        override val generation: Long,
        override val sessionId: String,
        override val profileSessionKey: String,
        override val language: String,
        override val subtitleLanguage: String?,
        override val startedAtMs: Long
    ) : HomeProfileSession {
        override val profileId: Int = 1
    }

    data class Secondary(
        override val profileId: Int,
        override val generation: Long,
        override val sessionId: String,
        override val profileSessionKey: String,
        override val language: String,
        override val subtitleLanguage: String?,
        override val startedAtMs: Long,
        val boundaryContext: SecondaryProfileRuntimeContext
    ) : HomeProfileSession
}

// Temporary Task 2 compatibility shim for staged migration; remove when Task 3 updates remaining callers.
internal val StateFlow<HomeProfileSession>.profileId: Int
    get() = value.profileId

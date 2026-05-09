package com.nexio.tv.ui.screens.home.testutil

import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.HomeDisplayMetadata
import com.nexio.tv.domain.model.HomeItemHydrationState
import com.nexio.tv.domain.model.HydratedHomeOverlay
import com.nexio.tv.domain.model.ProviderId
import com.nexio.tv.domain.model.hydratedHomeDisplayHash
import com.nexio.tv.domain.model.hydratedHomeOverlayKey

object HydratedHomeOverlayFixtures {
    fun minimal(itemKey: String, updatedAtMs: Long = 0L): HydratedHomeOverlay {
        val fields = HomeDisplayMetadata(title = "test:$itemKey")
        return HydratedHomeOverlay(
            overlayKey = hydratedHomeOverlayKey(ProviderId.TMDB, itemKey, ContentType.MOVIE, "en-US", 1),
            itemKey = itemKey,
            canonicalProvider = ProviderId.TMDB,
            canonicalId = itemKey,
            imdbId = null,
            contentType = ContentType.MOVIE,
            languageTag = "en-US",
            policyVersion = 1,
            fields = fields,
            fieldTrace = emptyList(),
            displayHash = fields.hydratedHomeDisplayHash(),
            updatedAtMs = updatedAtMs,
            staleAtMs = updatedAtMs + 1_000L,
            expiresAtMs = updatedAtMs + 2_000L,
            state = HomeItemHydrationState.CANONICAL_READY
        )
    }
}

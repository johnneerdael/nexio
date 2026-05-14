package com.nexio.tv.data.repository

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.repository.mdblist.MDBListIdMapper
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListScrobbleService @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
) {
    suspend fun scrobbleStart(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("start", item, progressPercent)
    }

    suspend fun scrobblePause(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("pause", item, progressPercent)
    }

    suspend fun scrobbleStop(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("stop", item, progressPercent)
    }

    private suspend fun scrobble(action: String, item: TrackingScrobbleItem, progressPercent: Float) {
        val settings = settingsReader.settings.first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) return
        api.scrobble(
            action = action,
            apiKey = apiKey,
            body = MDBListIdMapper.scrobblePayloadFor(item, progressPercent),
        )
    }
}

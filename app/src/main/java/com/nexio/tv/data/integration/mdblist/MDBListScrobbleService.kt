package com.nexio.tv.data.integration.mdblist

import com.nexio.tv.data.remote.api.MDBListApi
import com.nexio.tv.data.repository.MDBListSettingsReader
import com.nexio.tv.data.repository.TrackingScrobbleItem
import com.nexio.tv.data.repository.mdblist.MDBListIdMapper
import com.nexio.tv.core.profile.ProfileManager
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MDBListScrobbleService @Inject constructor(
    private val api: MDBListApi,
    private val settingsReader: MDBListSettingsReader,
    private val profileManager: ProfileManager,
) {
    suspend fun scrobbleStart(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("start", item, progressPercent, ownerProfileId)
    }

    suspend fun scrobblePause(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("pause", item, progressPercent, ownerProfileId)
    }

    suspend fun scrobbleStop(
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int? = null,
        ownerSessionId: String? = null,
    ) {
        scrobble("stop", item, progressPercent, ownerProfileId)
    }

    private suspend fun scrobble(
        action: String,
        item: TrackingScrobbleItem,
        progressPercent: Float,
        ownerProfileId: Int?
    ) {
        val profileId = ownerProfileId ?: profileManager.activeProfileId.value
        val settings = settingsReader.settingsForProfile(profileId).first()
        val apiKey = settings.apiKey.trim()
        if (!settings.enabled || apiKey.isBlank()) return
        api.scrobble(
            action = action,
            apiKey = apiKey,
            body = MDBListIdMapper.scrobblePayloadFor(item, progressPercent),
        )
    }
}

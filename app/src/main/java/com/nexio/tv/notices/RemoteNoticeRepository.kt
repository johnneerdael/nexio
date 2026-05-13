package com.nexio.tv.notices

import com.nexio.tv.BuildConfig
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.data.integration.github.GitHubRawContentIntegrationProvider
import com.nexio.tv.notices.model.RemoteNoticeDisplay
import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.squareup.moshi.Moshi
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.flow.first

@Singleton
class RemoteNoticeRepository internal constructor(
    private val gitHubRawContentIntegrationProvider: GitHubRawContentIntegrationProvider,
    private val remoteNoticePreferences: RemoteNoticePreferences,
    private val moshi: Moshi,
    private val manifestUrl: String,
    private val appVersion: String
) {
    @Inject
    constructor(
        gitHubRawContentIntegrationProvider: GitHubRawContentIntegrationProvider,
        remoteNoticePreferences: RemoteNoticePreferences,
        moshi: Moshi
    ) : this(
        gitHubRawContentIntegrationProvider = gitHubRawContentIntegrationProvider,
        remoteNoticePreferences = remoteNoticePreferences,
        moshi = moshi,
        manifestUrl = BuildConfig.NOTICES_MANIFEST_URL,
        appVersion = BuildConfig.VERSION_NAME
    )

    suspend fun fetchStartupNotice(now: Instant = Instant.now()): RemoteNoticeDisplay? {
        return try {
            remoteNoticePreferences.setLastCheckAtMs(now.toEpochMilli())

            val manifestText = when (val result = gitHubRawContentIntegrationProvider.fetchNoticeManifest(manifestUrl)) {
                is IntegrationCallResult.Success -> result.value
                is IntegrationCallResult.HttpError,
                IntegrationCallResult.Missing,
                is IntegrationCallResult.NetworkError -> return null
            }

            val manifest = try {
                moshi.adapter(RemoteNoticeManifest::class.java).fromJson(manifestText)
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                null
            } ?: return null

            if (manifest.schemaVersion != 1) return null

            val existingBaseline = remoteNoticePreferences.noticeBaselineAt.first()
            val baselineAt = existingBaseline ?: remoteNoticePreferences.setNoticeBaselineAtIfAbsent(now)

            val selected = RemoteNoticeSelector.selectNewestEligible(
                manifest = manifest,
                now = now,
                baselineAt = baselineAt,
                seenIds = remoteNoticePreferences.seenNoticeIds.first(),
                appVersion = appVersion
            ) ?: return null

            val markdown = when (val result = gitHubRawContentIntegrationProvider.fetchNoticeMarkdown(selected.markdownUrl)) {
                is IntegrationCallResult.Success -> result.value.trim().takeIf { it.isNotBlank() } ?: return null
                is IntegrationCallResult.HttpError,
                IntegrationCallResult.Missing,
                is IntegrationCallResult.NetworkError -> return null
            }

            RemoteNoticeDisplay(
                id = selected.id,
                title = selected.title,
                markdown = markdown,
                markdownUrl = selected.markdownUrl,
                publishedAt = selected.publishedAt
            )
        } catch (exception: Exception) {
            if (exception is CancellationException) throw exception
            null
        }
    }
}

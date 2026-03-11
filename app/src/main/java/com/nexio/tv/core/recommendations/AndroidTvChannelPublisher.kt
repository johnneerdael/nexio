package com.nexio.tv.core.recommendations

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.BaseColumns
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toBitmap
import androidx.tvprovider.media.tv.PreviewChannel
import androidx.tvprovider.media.tv.PreviewChannelHelper
import androidx.tvprovider.media.tv.PreviewProgram
import androidx.tvprovider.media.tv.TvContractCompat
import com.nexio.tv.R
import com.nexio.tv.data.local.AndroidTvRecommendationsDataStore
import com.nexio.tv.data.repository.ContinueWatchingSnapshotService
import com.nexio.tv.domain.model.MetaPreview
import com.nexio.tv.domain.model.PosterShape
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "AndroidTvChannels"
private const val CHANNEL_ID_PREFIX = "nexio_android_tv_channel_"
private const val PROGRAM_ID_PREFIX = "nexio_android_tv_program_"
private const val MAX_PROGRAMS_PER_CHANNEL = 30
private const val EXTRA_RECOMMENDATION_FEED_KEY = "recommendation_feed_key"
private const val EXTRA_RECOMMENDATION_CONTENT_ID = "recommendation_content_id"
private const val EXTRA_RECOMMENDATION_CONTENT_TYPE = "recommendation_content_type"
private const val EXTRA_RECOMMENDATION_ADDON_BASE_URL = "recommendation_addon_base_url"

@Singleton
class AndroidTvChannelPublisher @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dataStore: AndroidTvRecommendationsDataStore,
    private val feedCatalogService: AndroidTvFeedCatalogService,
    private val continueWatchingSnapshotService: ContinueWatchingSnapshotService
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val mutex = Mutex()
    private val previewChannelHelper = PreviewChannelHelper(context)
    private val channelLogoBitmap by lazy(LazyThreadSafetyMode.NONE) {
        ContextCompat.getDrawable(context, R.drawable.android_tv_channel_logo)?.toBitmap()
    }

    init {
        scope.launch {
            dataStore.preferences
                .drop(1)
                .collect { requestSync("preferences_changed") }
        }
        scope.launch {
            combine(
                dataStore.preferences,
                continueWatchingSnapshotService.observeSnapshot()
            ) { prefs, snapshot ->
                prefs.enabled &&
                    AndroidTvFeedCatalogService.CONTINUE_WATCHING_FEED_KEY in prefs.selectedFeedKeys &&
                    snapshot.updatedAtMs > 0L
            }
                .distinctUntilChanged()
                .collect { shouldSync ->
                    if (shouldSync) {
                        requestSync("continue_watching_changed")
                    }
                }
        }
    }

    fun requestSync(reason: String = "manual") {
        scope.launch {
            syncNow(reason)
        }
    }

    suspend fun syncNow(reason: String = "manual") {
        if (!supportsAndroidTvChannels()) return
        mutex.withLock {
            val prefs = dataStore.preferences.first()
            if (!prefs.enabled || prefs.selectedFeedKeys.isEmpty()) {
                clearOwnedChannelsLocked()
                return
            }

            val selectedRows = runCatching {
                feedCatalogService.resolveSelectedRows(prefs.selectedFeedKeys)
            }.getOrElse { error ->
                Log.w(TAG, "Failed to resolve Android TV feed rows for reason=$reason", error)
                emptyList()
            }

            if (selectedRows.isEmpty()) {
                clearOwnedChannelsLocked()
                return
            }

            val selectedByKey = selectedRows.associateBy { it.option.key }
            val existingChannels = queryOwnedChannels().associateBy { it.internalProviderId ?: "" }
            val activeProviderIds = mutableSetOf<String>()
            val hadBrowsableOwnedChannel = existingChannels.values.any { it.isBrowsable }
            var autoBrowsableChannelId: Long? = null

            prefs.selectedFeedKeys.forEach { key ->
                val row = selectedByKey[key] ?: return@forEach
                val providerId = channelProviderId(key)
                activeProviderIds += providerId
                val existing = existingChannels[providerId]
                val channelResult = upsertChannel(
                    option = row.option,
                    providerId = providerId,
                    existingChannel = existing
                )
                val channelId = channelResult?.channelId ?: return@forEach
                if (channelResult.needsBrowsableRequest) {
                    if (!hadBrowsableOwnedChannel && autoBrowsableChannelId == null) {
                        autoBrowsableChannelId = channelId
                        dataStore.clearBrowsableChannelRequest(channelId)
                    } else {
                        dataStore.enqueueBrowsableChannelId(channelId)
                    }
                } else {
                    dataStore.clearBrowsableChannelRequest(channelId)
                }
                clearPrograms(channelId)
                row.items
                    .take(MAX_PROGRAMS_PER_CHANNEL)
                    .forEachIndexed { position, item ->
                        publishProgram(
                            channelId = channelId,
                            item = item,
                            option = row.option,
                            addonBaseUrl = row.addonBaseUrl,
                            position = position
                        )
                }
            }

            autoBrowsableChannelId?.let { channelId ->
                runCatching {
                    TvContractCompat.requestChannelBrowsable(context, channelId)
                }.onFailure { error ->
                    Log.w(TAG, "Failed to request Android TV browsable channelId=$channelId", error)
                }
            }

            existingChannels.values.forEach { channel ->
                val providerId = channel.internalProviderId ?: return@forEach
                if (providerId !in activeProviderIds) {
                    deleteChannel(channel.id)
                }
            }
        }
    }

    private fun supportsAndroidTvChannels(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return false
        return context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK)
    }

    private suspend fun clearOwnedChannelsLocked() {
        queryOwnedChannels().forEach { channel ->
            deleteChannel(channel.id)
        }
    }

    private fun queryOwnedChannels(): List<PreviewChannel> {
        return runCatching {
            previewChannelHelper.allChannels
                .filter { channel ->
                    (channel.internalProviderId ?: "").startsWith(CHANNEL_ID_PREFIX)
                }
        }.getOrElse { error ->
            Log.w(TAG, "Failed to query existing Android TV channels", error)
            emptyList()
        }
    }

    private fun upsertChannel(
        option: AndroidTvFeedOption,
        providerId: String,
        existingChannel: PreviewChannel?
    ): UpsertedChannel? {
        return runCatching {
            val logoBitmap = channelLogoBitmap
                ?: throw IllegalStateException("Android TV channel logo bitmap unavailable")

            val channel = PreviewChannel.Builder()
                .setDisplayName(option.title)
                .setDescription(option.subtitle)
                .setInternalProviderId(providerId)
                .setLogo(logoBitmap)
                .setAppLinkIntent(buildChannelLaunchIntent(option.key))
                .build()

            val existingChannelId = existingChannel?.id?.takeIf { it > 0L }
            val channelId = if (existingChannelId != null) {
                previewChannelHelper.updatePreviewChannel(existingChannelId, channel)
                existingChannelId
            } else {
                previewChannelHelper.publishChannel(channel)
            }

            UpsertedChannel(
                channelId = channelId,
                needsBrowsableRequest = existingChannel?.isBrowsable != true
            )
        }.getOrElse { error ->
            Log.w(TAG, "Failed to upsert Android TV channel ${option.key}", error)
            null
        }
    }

    private fun publishProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int
    ) {
        runCatching {
            previewChannelHelper.publishPreviewProgram(
                buildProgram(
                    channelId = channelId,
                    item = item,
                    option = option,
                    addonBaseUrl = addonBaseUrl,
                    position = position
                )
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to publish Android TV program key=${option.key} item=${item.id}", error)
        }
    }

    private fun buildProgram(
        channelId: Long,
        item: MetaPreview,
        option: AndroidTvFeedOption,
        addonBaseUrl: String?,
        position: Int
    ): PreviewProgram {
        val contentType = item.apiType
        val programType = when (contentType.lowercase()) {
            "series", "tv" -> TvContractCompat.PreviewPrograms.TYPE_TV_SERIES
            else -> TvContractCompat.PreviewPrograms.TYPE_MOVIE
        }
        val posterAspectRatio = when (item.posterShape) {
            PosterShape.LANDSCAPE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_16_9
            PosterShape.SQUARE -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_1_1
            else -> TvContractCompat.PreviewPrograms.ASPECT_RATIO_2_3
        }
        val primaryArt = when (item.posterShape) {
            PosterShape.LANDSCAPE -> item.background ?: item.poster
            else -> item.poster ?: item.background
        }

        return PreviewProgram.Builder()
            .setChannelId(channelId)
            .setType(programType)
            .setTitle(item.name)
            .setDescription(item.description ?: item.releaseInfo ?: option.subtitle)
            .setPosterArtAspectRatio(posterAspectRatio)
            .setWeight(MAX_PROGRAMS_PER_CHANNEL - position)
            .setInternalProviderId("$PROGRAM_ID_PREFIX${option.key}_${contentType}_${item.id}")
            .setIntentUri(
                Uri.parse(
                    (baseLaunchIntent() ?: Intent(Intent.ACTION_MAIN))
                        .setAction(Intent.ACTION_VIEW)
                        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        .putExtra(EXTRA_RECOMMENDATION_FEED_KEY, option.key)
                        .putExtra(EXTRA_RECOMMENDATION_CONTENT_ID, item.id)
                        .putExtra(EXTRA_RECOMMENDATION_CONTENT_TYPE, contentType)
                        .putExtra(EXTRA_RECOMMENDATION_ADDON_BASE_URL, addonBaseUrl.orEmpty())
                        .toUri(Intent.URI_INTENT_SCHEME)
                )
            )
            .apply {
                primaryArt?.takeIf { it.isNotBlank() }?.let { setPosterArtUri(Uri.parse(it)) }
                item.background?.takeIf { it.isNotBlank() }?.let { setThumbnailUri(Uri.parse(it)) }
            }
            .build()
    }

    private fun clearPrograms(channelId: Long) {
        runCatching {
            context.contentResolver.delete(
                TvContractCompat.buildPreviewProgramsUriForChannel(channelId),
                null,
                null
            )
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear preview programs for channelId=$channelId", error)
        }
    }

    private suspend fun deleteChannel(channelId: Long) {
        runCatching {
            previewChannelHelper.deletePreviewChannel(channelId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to delete Android TV channelId=$channelId", error)
        }
        runCatching {
            dataStore.clearBrowsableChannelRequest(channelId)
        }.onFailure { error ->
            Log.w(TAG, "Failed to clear Android TV browsable request state channelId=$channelId", error)
        }
    }

    private val PreviewChannel.id: Long
        get() {
            return runCatching {
                val value = toContentValues().getAsLong(BaseColumns._ID)
                value ?: -1L
            }.getOrDefault(-1L)
        }

    private fun channelProviderId(feedKey: String): String {
        return "$CHANNEL_ID_PREFIX$feedKey"
    }

    private fun buildChannelLaunchIntent(feedKey: String): Intent {
        return (baseLaunchIntent() ?: Intent(Intent.ACTION_MAIN))
            .setAction(Intent.ACTION_VIEW)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_RECOMMENDATION_FEED_KEY, feedKey)
    }

    private fun baseLaunchIntent(): Intent? {
        return context.packageManager.getLaunchIntentForPackage(context.packageName)
    }

    private data class UpsertedChannel(
        val channelId: Long,
        val needsBrowsableRequest: Boolean
    )
}

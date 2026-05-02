package com.nexio.tv.data.integration.subtitles.wyzie

import android.util.Log
import com.nexio.tv.core.integration.IntegrationCallResult
import com.nexio.tv.core.integration.IntegrationCallSpec
import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass
import com.nexio.tv.core.integration.SubtitleApiShapes
import com.nexio.tv.data.integration.subtitles.wyzie.transport.WyzieSubtitleApi
import com.nexio.tv.data.local.WyzieSettingsDataStore
import com.nexio.tv.data.remote.dto.WyzieSubtitleDto
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.WyzieIdHints
import com.nexio.tv.domain.model.WyzieSource
import kotlinx.coroutines.flow.first
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException

private const val TAG = "WyzieSubtitleIntegrationProvider"
private const val LOG_PREFIX = "WYZIE_SUBS"

/**
 * Calls https://sub.wyzie.io/search through the IntegrationRuntime.
 *
 * Returns an empty list (silent degrade) for any failure or skip condition. Re-throws
 * [CancellationException] so the caller's coroutine cancellation propagates correctly.
 */
@Singleton
class WyzieSubtitleIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val api: WyzieSubtitleApi,
    private val settingsDataStore: WyzieSettingsDataStore,
) {

    suspend fun search(
        type: ContentType,
        hints: WyzieIdHints,
        sources: List<WyzieSource>,
        season: Int?,
        episode: Int?,
    ): List<WyzieSubtitleDto> {
        val settings = settingsDataStore.settings.first()
        if (!settings.enabled) {
            return emptyList() // No log per spec table for `enabled == false`.
        }
        if (settings.apiKey.isNullOrBlank()) {
            Log.d(TAG, "$LOG_PREFIX skipped: no key")
            return emptyList()
        }
        if (sources.isEmpty()) {
            Log.d(TAG, "$LOG_PREFIX skipped: no sources for type=$type anime=${hints.isAnime}")
            return emptyList()
        }
        if (!hints.hasUsableWyzieId) {
            Log.d(TAG, "$LOG_PREFIX skipped: no usable id for type=$type")
            return emptyList()
        }
        if ((season == null) != (episode == null)) {
            Log.d(TAG, "$LOG_PREFIX skipped: season/episode partial")
            return emptyList()
        }

        val id = hints.imdb ?: hints.tmdb!!.toString()
        val sourceParam = sources.joinToString(",") { it.apiName }

        val callResult = runtime.call(
            IntegrationCallSpec(
                provider = IntegrationProvider.WYZIE_SUBTITLES,
                apiShapeId = SubtitleApiShapes.WYZIE_SEARCH,
                operationKey = "wyzie.search",
                workClass = IntegrationWorkClass.USER_VISIBLE,
                scope = IntegrationScope.ProviderConfig("wyzie:subtitles"),
                call = {
                    val outcome: IntegrationCallResult<List<WyzieSubtitleDto>> = try {
                        val started = System.currentTimeMillis()
                        val response = api.search(
                            id = id,
                            source = sourceParam,
                            format = "srt,ass,vtt",
                            season = season,
                            episode = episode,
                        )
                        if (!response.isSuccessful) {
                            Log.w(TAG, "$LOG_PREFIX http error status=${response.code()} reason=${response.message()}")
                            IntegrationCallResult.HttpError(
                                statusCode = response.code(),
                                reason = "wyzie_search_failed",
                            )
                        } else {
                            val body = response.body() ?: emptyList()
                            Log.d(
                                TAG,
                                "$LOG_PREFIX ok sources=$sourceParam count=${body.size} latencyMs=${System.currentTimeMillis() - started}",
                            )
                            IntegrationCallResult.Success(body)
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "$LOG_PREFIX network failure: ${e.javaClass.simpleName}: ${e.message}")
                        IntegrationCallResult.NetworkError(e)
                    }
                    outcome
                },
            )
        )

        return when (callResult) {
            is IntegrationCallResult.Success -> callResult.value
            is IntegrationCallResult.HttpError,
            is IntegrationCallResult.NetworkError,
            IntegrationCallResult.Missing -> emptyList()
        }
    }
}

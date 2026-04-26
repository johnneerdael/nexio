package com.nexio.tv.data.repository.trakt

import android.util.Log
import com.google.gson.JsonObject
import com.nexio.tv.BuildConfig
import com.nexio.tv.data.integration.trakt.TraktIntegrationProvider
import com.nexio.tv.data.local.PlayerSettingsDataStore
import com.nexio.tv.data.remote.dto.trakt.TraktCheckinRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktEpisodeDto
import com.nexio.tv.data.remote.dto.trakt.TraktIdsDto
import com.nexio.tv.data.remote.dto.trakt.TraktMovieDto
import com.nexio.tv.data.remote.dto.trakt.TraktScrobbleRequestDto
import com.nexio.tv.data.remote.dto.trakt.TraktShowDto
import com.nexio.tv.data.repository.TraktScrobbleItem
import com.nexio.tv.data.repository.TraktProgressService
import com.nexio.tv.data.repository.TrackingAuthSession
import com.nexio.tv.data.trakt.outbox.TraktMutationAdapter
import com.nexio.tv.data.trakt.outbox.TraktMutationEnvelope
import com.nexio.tv.data.trakt.outbox.TraktMutationExecutionResult
import com.nexio.tv.data.trakt.outbox.TraktMutationPriorityBucket
import com.nexio.tv.data.trakt.outbox.TraktMutationSettlement
import com.nexio.tv.domain.model.TrackingProvider
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import kotlinx.coroutines.flow.first
import retrofit2.Response
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TraktScrobbleMutationAdapter @Inject constructor(
    private val traktIntegrationProvider: TraktIntegrationProvider,
    private val traktProgressService: TraktProgressService,
    private val watchingNowStateController: TraktWatchingNowStateController,
    private val playerSettingsDataStore: PlayerSettingsDataStore
) : TraktMutationAdapter {

    override val adapterKey: String = ADAPTER_KEY

    override suspend fun applyOptimistic(envelope: TraktMutationEnvelope) = Unit

    override suspend fun execute(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        return when (envelope.mutationKind) {
            MUTATION_KIND_CHECKIN -> executeCheckin(envelope)
            MUTATION_KIND_SCROBBLE -> executeScrobble(envelope)
            else -> TraktMutationExecutionResult.Failure(
                httpStatusCode = 400,
                reason = "Unsupported scrobble mutation kind ${envelope.mutationKind}"
            )
        }
    }

    override suspend fun reconcileSuccess(envelope: TraktMutationEnvelope) {
        when {
            envelope.isCheckIn() -> traktProgressService.refreshNow()
            envelope.scrobbleAction() == "stop" -> traktProgressService.refreshNow()
        }
    }

    override suspend fun rollbackToServerTruth(
        envelope: TraktMutationEnvelope,
        failure: TraktMutationSettlement.TerminalFailure
    ) {
        watchingNowStateController.rollbackIfCurrent(
            expectedVersion = envelope.optimisticVersion(),
            rollbackState = envelope.rollbackState()
        )
    }

    private suspend fun executeCheckin(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val requestBody = envelope.buildCheckinRequestBody()
        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val logApi = isScrobbleApiLoggingEnabled()
        if (logApi) {
            logTraktScrobbleRequest(
                endpoint = "POST /checkin",
                envelope = envelope,
                body = requestBody
            )
        }
        val response = traktIntegrationProvider.checkin(
            session = session,
            body = requestBody
        ) ?: run {
            if (logApi) logTraktScrobbleNoResponse("POST /checkin", envelope)
            return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")
        }
        if (logApi) logTraktScrobbleResponse("POST /checkin", envelope, response)

        return if (response.isSuccessful || response.code() == 409) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Trakt check-in failed (${response.code()})"
            )
        }
    }

    private suspend fun executeScrobble(envelope: TraktMutationEnvelope): TraktMutationExecutionResult {
        val requestBody = envelope.buildScrobbleRequestBody()
        val session = TrackingAuthSession(TrackingProvider.TRAKT, envelope.profileId)
        val action = envelope.scrobbleAction()
        val endpoint = "POST /scrobble/$action"
        val logApi = isScrobbleApiLoggingEnabled()
        if (logApi) {
            logTraktScrobbleRequest(
                endpoint = endpoint,
                envelope = envelope,
                body = requestBody
            )
        }
        val response = traktIntegrationProvider.scrobble(
            session = session,
            action = action,
            body = requestBody
        ) ?: run {
            if (logApi) logTraktScrobbleNoResponse(endpoint, envelope)
            return TraktMutationExecutionResult.Failure(reason = "Trakt request failed")
        }
        if (logApi) logTraktScrobbleResponse(endpoint, envelope, response)

        return if (response.isSuccessful || response.code() == 409) {
            TraktMutationExecutionResult.Success(httpStatusCode = response.code())
        } else {
            TraktMutationExecutionResult.Failure(
                httpStatusCode = response.code(),
                retryAfterHeader = response.headers()["Retry-After"],
                reason = "Trakt scrobble failed (${response.code()})"
            )
        }
    }

    private suspend fun isScrobbleApiLoggingEnabled(): Boolean {
        return runCatching {
            playerSettingsDataStore.playerSettings.first().traktScrobbleApiLoggingEnabled
        }.getOrDefault(false)
    }

    private fun logTraktScrobbleRequest(
        endpoint: String,
        envelope: TraktMutationEnvelope,
        body: Any
    ) {
        Log.i(
            LOG_TAG,
            "request endpoint=$endpoint profile=${envelope.profileId} " +
                "kind=${envelope.mutationKind} collapseKey=${envelope.collapseKey} body=${body.toString().boundedForLog()}"
        )
    }

    private fun logTraktScrobbleNoResponse(endpoint: String, envelope: TraktMutationEnvelope) {
        Log.w(
            LOG_TAG,
            "response endpoint=$endpoint profile=${envelope.profileId} kind=${envelope.mutationKind} result=no_response"
        )
    }

    private fun logTraktScrobbleResponse(
        endpoint: String,
        envelope: TraktMutationEnvelope,
        response: Response<*>
    ) {
        val responseText = response.body()?.toString()
            ?: response.errorBody()?.string()
            ?: "<empty>"
        Log.i(
            LOG_TAG,
            "response endpoint=$endpoint profile=${envelope.profileId} kind=${envelope.mutationKind} " +
                "http=${response.code()} successful=${response.isSuccessful} body=${responseText.boundedForLog()}"
        )
    }

    private fun String.boundedForLog(maxLength: Int = 1_500): String {
        return replace('\n', ' ').replace('\r', ' ')
            .let { value ->
                if (value.length <= maxLength) value else value.take(maxLength) + "...<truncated>"
            }
    }

    companion object {
        private const val LOG_TAG = "TraktScrobbleApi"
        private const val PAYLOAD_ITEM_TYPE = "itemType"
        private const val PAYLOAD_TITLE = "title"
        private const val PAYLOAD_YEAR = "year"
        private const val PAYLOAD_IMDB = "imdb"
        private const val PAYLOAD_TMDB = "tmdb"
        private const val PAYLOAD_TRAKT = "trakt"
        private const val PAYLOAD_SHOW_TITLE = "showTitle"
        private const val PAYLOAD_SHOW_YEAR = "showYear"
        private const val PAYLOAD_SHOW_IMDB = "showImdb"
        private const val PAYLOAD_SHOW_TMDB = "showTmdb"
        private const val PAYLOAD_SHOW_TRAKT = "showTrakt"
        private const val PAYLOAD_SEASON = "season"
        private const val PAYLOAD_NUMBER = "number"
        private const val PAYLOAD_EPISODE_TITLE = "episodeTitle"
        private const val PAYLOAD_ACTION = "action"
        private const val PAYLOAD_PROGRESS = "progress"
        private const val PAYLOAD_MESSAGE = "message"

        private const val METADATA_ROLLBACK_ACTIVE = "rollbackActive"
        private const val METADATA_ROLLBACK_TITLE = "rollbackTitle"
        private const val METADATA_ROLLBACK_CONTENT_TYPE = "rollbackContentType"
        private const val METADATA_ROLLBACK_PROGRESS = "rollbackProgress"
        private const val METADATA_OPTIMISTIC_VERSION = "optimisticVersion"

        const val ADAPTER_KEY = "scrobble"
        const val MUTATION_KIND_SCROBBLE = "scrobble.state"
        const val MUTATION_KIND_CHECKIN = "scrobble.checkin"

        fun buildScrobbleEnvelope(
            item: TraktScrobbleItem,
            action: String,
            progressPercent: Float,
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long,
            profileId: Int = 1
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                addProperty(PAYLOAD_ACTION, action)
                addProperty(PAYLOAD_PROGRESS, progressPercent.coerceIn(0f, 100f))
            }
            return TraktMutationEnvelope(
                profileId = profileId,
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_SCROBBLE,
                priority = TraktMutationPriorityBucket.SCROBBLE,
                collapseKey = "scrobble:${item.itemKey}",
                payload = payload,
                metadata = buildRollbackMetadata(rollbackState, optimisticVersion)
            )
        }

        fun buildCheckinEnvelope(
            item: TraktScrobbleItem,
            message: String?,
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long,
            profileId: Int = 1
        ): TraktMutationEnvelope {
            val payload = JsonObject().apply {
                populateItem(item)
                message?.let { addProperty(PAYLOAD_MESSAGE, it) }
            }
            return TraktMutationEnvelope(
                profileId = profileId,
                adapterKey = ADAPTER_KEY,
                mutationKind = MUTATION_KIND_CHECKIN,
                priority = TraktMutationPriorityBucket.SCROBBLE,
                collapseKey = "scrobble:${item.itemKey}",
                payload = payload,
                metadata = buildRollbackMetadata(rollbackState, optimisticVersion)
            )
        }

        private fun JsonObject.populateItem(item: TraktScrobbleItem) {
            when (item) {
                is TraktScrobbleItem.Movie -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "movie")
                    item.title?.let { addProperty(PAYLOAD_TITLE, it) }
                    item.year?.let { addProperty(PAYLOAD_YEAR, it) }
                    item.ids.imdb?.let { addProperty(PAYLOAD_IMDB, it) }
                    item.ids.tmdb?.let { addProperty(PAYLOAD_TMDB, it) }
                    item.ids.trakt?.let { addProperty(PAYLOAD_TRAKT, it) }
                }

                is TraktScrobbleItem.Episode -> {
                    addProperty(PAYLOAD_ITEM_TYPE, "episode")
                    item.showTitle?.let { addProperty(PAYLOAD_SHOW_TITLE, it) }
                    item.showYear?.let { addProperty(PAYLOAD_SHOW_YEAR, it) }
                    item.showIds.imdb?.let { addProperty(PAYLOAD_SHOW_IMDB, it) }
                    item.showIds.tmdb?.let { addProperty(PAYLOAD_SHOW_TMDB, it) }
                    item.showIds.trakt?.let { addProperty(PAYLOAD_SHOW_TRAKT, it) }
                    addProperty(PAYLOAD_SEASON, item.season)
                    addProperty(PAYLOAD_NUMBER, item.number)
                    item.episodeTitle?.let { addProperty(PAYLOAD_EPISODE_TITLE, it) }
                }
            }
        }

        private fun buildRollbackMetadata(
            rollbackState: TraktWatchingNowStateController.Snapshot,
            optimisticVersion: Long
        ): JsonObject {
            return JsonObject().apply {
                addProperty(METADATA_ROLLBACK_ACTIVE, rollbackState.active)
                rollbackState.title?.let { addProperty(METADATA_ROLLBACK_TITLE, it) }
                rollbackState.contentType?.let { addProperty(METADATA_ROLLBACK_CONTENT_TYPE, it) }
                rollbackState.progressPercent?.let { addProperty(METADATA_ROLLBACK_PROGRESS, it) }
                addProperty(METADATA_OPTIMISTIC_VERSION, optimisticVersion)
            }
        }

        private fun TraktMutationEnvelope.isCheckIn(): Boolean = mutationKind == MUTATION_KIND_CHECKIN

        private fun TraktMutationEnvelope.scrobbleAction(): String {
            return payload.get(PAYLOAD_ACTION)?.asString ?: "stop"
        }

        private fun TraktMutationEnvelope.optimisticVersion(): Long {
            return metadata.get(METADATA_OPTIMISTIC_VERSION)?.asLong
                ?: error("Missing optimistic version metadata")
        }

        private fun TraktMutationEnvelope.rollbackState(): TraktWatchingNowStateController.Snapshot {
            return TraktWatchingNowStateController.Snapshot(
                active = metadata.get(METADATA_ROLLBACK_ACTIVE)?.asBoolean ?: false,
                title = metadata.get(METADATA_ROLLBACK_TITLE)?.asString,
                contentType = metadata.get(METADATA_ROLLBACK_CONTENT_TYPE)?.asString,
                progressPercent = metadata.get(METADATA_ROLLBACK_PROGRESS)?.takeIf { !it.isJsonNull }?.asFloat
            )
        }

        private fun TraktMutationEnvelope.buildScrobbleRequestBody(): TraktScrobbleRequestDto {
            return when (payload.get(PAYLOAD_ITEM_TYPE)?.asString) {
                "movie" -> TraktScrobbleRequestDto(
                    movie = TraktMovieDto(
                        title = payload.get(PAYLOAD_TITLE)?.asString,
                        year = payload.get(PAYLOAD_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = TraktIdsDto(
                            imdb = payload.get(PAYLOAD_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_TMDB)?.takeIf { !it.isJsonNull }?.asInt,
                            trakt = payload.get(PAYLOAD_TRAKT)?.takeIf { !it.isJsonNull }?.asInt
                        )
                    ),
                    progress = payload.get(PAYLOAD_PROGRESS)?.asFloat ?: 0f,
                    appVersion = BuildConfig.VERSION_NAME
                )

                else -> TraktScrobbleRequestDto(
                    show = TraktShowDto(
                        title = payload.get(PAYLOAD_SHOW_TITLE)?.asString,
                        year = payload.get(PAYLOAD_SHOW_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = TraktIdsDto(
                            imdb = payload.get(PAYLOAD_SHOW_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_SHOW_TMDB)?.takeIf { !it.isJsonNull }?.asInt,
                            trakt = payload.get(PAYLOAD_SHOW_TRAKT)?.takeIf { !it.isJsonNull }?.asInt
                        )
                    ),
                    episode = TraktEpisodeDto(
                        title = payload.get(PAYLOAD_EPISODE_TITLE)?.asString,
                        season = payload.get(PAYLOAD_SEASON)?.asInt,
                        number = payload.get(PAYLOAD_NUMBER)?.asInt
                    ),
                    progress = payload.get(PAYLOAD_PROGRESS)?.asFloat ?: 0f,
                    appVersion = BuildConfig.VERSION_NAME
                )
            }
        }

        private fun TraktMutationEnvelope.buildCheckinRequestBody(): TraktCheckinRequestDto {
            return when (payload.get(PAYLOAD_ITEM_TYPE)?.asString) {
                "movie" -> TraktCheckinRequestDto(
                    movie = TraktMovieDto(
                        title = payload.get(PAYLOAD_TITLE)?.asString,
                        year = payload.get(PAYLOAD_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = TraktIdsDto(
                            imdb = payload.get(PAYLOAD_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_TMDB)?.takeIf { !it.isJsonNull }?.asInt,
                            trakt = payload.get(PAYLOAD_TRAKT)?.takeIf { !it.isJsonNull }?.asInt
                        )
                    ),
                    appVersion = BuildConfig.VERSION_NAME,
                    message = payload.get(PAYLOAD_MESSAGE)?.asString
                )

                else -> TraktCheckinRequestDto(
                    show = TraktShowDto(
                        title = payload.get(PAYLOAD_SHOW_TITLE)?.asString,
                        year = payload.get(PAYLOAD_SHOW_YEAR)?.takeIf { !it.isJsonNull }?.asInt,
                        ids = TraktIdsDto(
                            imdb = payload.get(PAYLOAD_SHOW_IMDB)?.asString,
                            tmdb = payload.get(PAYLOAD_SHOW_TMDB)?.takeIf { !it.isJsonNull }?.asInt,
                            trakt = payload.get(PAYLOAD_SHOW_TRAKT)?.takeIf { !it.isJsonNull }?.asInt
                        )
                    ),
                    episode = TraktEpisodeDto(
                        title = payload.get(PAYLOAD_EPISODE_TITLE)?.asString,
                        season = payload.get(PAYLOAD_SEASON)?.asInt,
                        number = payload.get(PAYLOAD_NUMBER)?.asInt
                    ),
                    appVersion = BuildConfig.VERSION_NAME,
                    message = payload.get(PAYLOAD_MESSAGE)?.asString
                )
            }
        }
    }
}

@Module
@InstallIn(SingletonComponent::class)
abstract class TraktScrobbleMutationAdapterModule {

    @Binds
    @IntoSet
    abstract fun bindTraktScrobbleMutationAdapter(
        impl: TraktScrobbleMutationAdapter
    ): TraktMutationAdapter
}

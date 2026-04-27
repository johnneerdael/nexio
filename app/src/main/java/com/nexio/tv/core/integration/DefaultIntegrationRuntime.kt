package com.nexio.tv.core.integration

import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.withContext

@Singleton
class DefaultIntegrationRuntime @Inject constructor(
    private val cacheStore: IntegrationCacheStore,
    private val requestGate: ProviderRequestGate,
    private val backoffManager: IntegrationBackoffManager,
    private val singleFlight: IntegrationSingleFlight,
    private val playbackGate: IntegrationPlaybackGate,
    private val registry: IntegrationPolicyRegistry,
    private val auditSink: IntegrationAuditSink
) : IntegrationRuntime {

    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        val traceId = traceId(spec.operationKey)
        record(
            spec = spec,
            traceId = traceId,
            phase = IntegrationAuditPhase.REQUEST_RECEIVED
        )
        return when (val policy = spec.cachePolicy) {
            IntegrationCachePolicy.Disabled -> executeWithoutCache(spec, options, traceId)
            is IntegrationCachePolicy.ObserveOnly -> executeObserveOnly(spec, policy, options, traceId)
            is IntegrationCachePolicy.CacheFirst -> executeCacheFirst(spec, policy, options, traceId)
            IntegrationCachePolicy.Mutation -> executeMutation(spec, options, traceId)
        }
    }

    override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> {
        val traceId = traceId(spec.operationKey)
        record(
            spec = spec,
            traceId = traceId,
            phase = IntegrationAuditPhase.REQUEST_RECEIVED
        )
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) {
            record(spec, traceId, IntegrationAuditPhase.PLAYBACK_BLOCKED, IntegrationOutcome.MISSING)
            return IntegrationCallResult.Missing
        }
        if (backoffManager.isBlocked(spec.provider, spec.scope)) {
            record(spec, traceId, IntegrationAuditPhase.BACKOFF_BLOCKED, IntegrationOutcome.MISSING)
            return IntegrationCallResult.Missing
        }

        record(spec, traceId, IntegrationAuditPhase.LANE_QUEUED)
        return requestGate.withPermit(spec.provider) {
            record(spec, traceId, IntegrationAuditPhase.NETWORK_START, networkStarted = true, loaderInvoked = true)
            val result = try {
                withContext(networkPermit(spec, traceId)) {
                    spec.call()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                noteSyntheticNetworkFailure(
                    provider = spec.provider,
                    scope = spec.scope,
                    retryAfterMs = null,
                    reason = exception.message
                )
                record(spec, traceId, IntegrationAuditPhase.FAILED, IntegrationOutcome.NETWORK_ERROR, networkStarted = true, loaderInvoked = true)
                return@withPermit IntegrationCallResult.NetworkError(exception)
            }

            when (result) {
                is IntegrationCallResult.HttpError -> {
                    if (result.statusCode == 429 || result.statusCode >= 500) {
                        backoffManager.noteHttpFailure(
                            provider = spec.provider,
                            scope = spec.scope,
                            statusCode = result.statusCode,
                            retryAfterMs = result.retryAfterMs,
                            reason = result.reason
                        )
                    }
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.HTTP_ERROR, result.statusCode, result.retryAfterMs, networkStarted = true, loaderInvoked = true)
                    result
                }
                is IntegrationCallResult.NetworkError -> {
                    if (result.throwable is CancellationException) throw result.throwable
                    noteSyntheticNetworkFailure(
                        provider = spec.provider,
                        scope = spec.scope,
                        retryAfterMs = result.retryAfterMs,
                        reason = result.throwable.message
                    )
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.NETWORK_ERROR, retryAfterMs = result.retryAfterMs, networkStarted = true, loaderInvoked = true)
                    result
                }
                is IntegrationCallResult.Success -> {
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.SUCCESS, networkStarted = true, loaderInvoked = true)
                    result
                }
                IntegrationCallResult.Missing -> {
                    record(spec, traceId, IntegrationAuditPhase.MISSING, IntegrationOutcome.MISSING, networkStarted = true, loaderInvoked = true)
                    IntegrationCallResult.Missing
                }
            }
        }
    }

    override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? {
        val traceId = traceId(spec.operationKey)
        record(
            spec = spec,
            traceId = traceId,
            phase = IntegrationAuditPhase.REQUEST_RECEIVED
        )
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) {
            record(spec, traceId, IntegrationAuditPhase.PLAYBACK_BLOCKED, IntegrationOutcome.MISSING)
            return null
        }
        if (backoffManager.isBlocked(spec.provider, spec.scope)) {
            record(spec, traceId, IntegrationAuditPhase.BACKOFF_BLOCKED, IntegrationOutcome.MISSING)
            return null
        }

        record(spec, traceId, IntegrationAuditPhase.LANE_QUEUED)
        return requestGate.withPermit(spec.provider) {
            try {
                record(spec, traceId, IntegrationAuditPhase.NETWORK_START, networkStarted = true, loaderInvoked = true)
                spec.open().also {
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.SUCCESS, networkStarted = true, loaderInvoked = true)
                }
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                record(spec, traceId, IntegrationAuditPhase.FAILED, IntegrationOutcome.NETWORK_ERROR, networkStarted = true, loaderInvoked = true)
                null
            }
        }
    }

    private suspend fun <T> executeWithoutCache(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions,
        traceId: String
    ): IntegrationFetchResult<T> {
        if (options.cacheOnly) {
            record(spec, traceId, IntegrationAuditPhase.MISSING, IntegrationOutcome.MISSING)
            return IntegrationFetchResult.Missing
        }
        return executeProviderLoad(spec, traceId)
    }

    private suspend fun <T> executeObserveOnly(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.ObserveOnly,
        options: IntegrationFetchOptions,
        traceId: String
    ): IntegrationFetchResult<T> {
        return executeWithoutCache(spec, options, traceId)
    }

    private suspend fun <T> executeMutation(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions,
        traceId: String
    ): IntegrationFetchResult<T> {
        if (options.cacheOnly) {
            record(spec, traceId, IntegrationAuditPhase.MISSING, IntegrationOutcome.MISSING)
            return IntegrationFetchResult.Missing
        }
        return executeProviderLoad(spec, traceId)
    }

    private suspend fun <T> executeCacheFirst(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.CacheFirst,
        options: IntegrationFetchOptions,
        traceId: String
    ): IntegrationFetchResult<T> {
        cacheStore.readFresh(spec)?.let {
            record(spec, traceId, IntegrationAuditPhase.FRESH_CACHE_HIT, IntegrationOutcome.SUCCESS)
            return IntegrationFetchResult.Fresh(it)
        }

        val providerPolicy = registry.policyFor(spec.provider)
        if (options.cacheOnly || playbackGate.isBlocked(providerPolicy, spec.workClass)) {
            val stale = cacheStore.readStale(spec)
            return if (stale != null && providerPolicy.allowStaleWhilePaused && options.allowStaleOnFailure) {
                record(spec, traceId, IntegrationAuditPhase.STALE_CACHE_HIT, IntegrationOutcome.SUCCESS)
                IntegrationFetchResult.Stale(stale)
            } else {
                val phase = if (options.cacheOnly) IntegrationAuditPhase.MISSING else IntegrationAuditPhase.PLAYBACK_BLOCKED
                record(spec, traceId, phase, IntegrationOutcome.MISSING)
                IntegrationFetchResult.Missing
            }
        }

        record(spec, traceId, IntegrationAuditPhase.LANE_QUEUED)
        return singleFlight.run(spec.requiredCacheKey) {
            cacheStore.readFresh(spec)?.let {
                record(spec, traceId, IntegrationAuditPhase.FRESH_CACHE_HIT, IntegrationOutcome.SUCCESS)
                return@run IntegrationFetchResult.Fresh(it)
            }
            if (backoffManager.isBlocked(spec.provider, spec.scope)) {
                record(spec, traceId, IntegrationAuditPhase.BACKOFF_BLOCKED, IntegrationOutcome.MISSING)
                cacheStore.readStale(spec)?.let {
                    record(spec, traceId, IntegrationAuditPhase.STALE_CACHE_HIT, IntegrationOutcome.SUCCESS)
                    return@run IntegrationFetchResult.Stale(it)
                }
                return@run IntegrationFetchResult.Missing
            }

            when (val result = executeProviderLoad(spec, traceId)) {
                is IntegrationFetchResult.Updated -> {
                    cacheStore.write(spec, result.value)
                    record(spec, traceId, IntegrationAuditPhase.CACHE_WRITE, IntegrationOutcome.SUCCESS, networkStarted = true, loaderInvoked = true)
                    result
                }
                is IntegrationFetchResult.Fresh -> result
                is IntegrationFetchResult.Stale -> result
                IntegrationFetchResult.Missing -> {
                    cacheStore.readStale(spec)?.let {
                        record(spec, traceId, IntegrationAuditPhase.STALE_CACHE_HIT, IntegrationOutcome.SUCCESS)
                        IntegrationFetchResult.Stale(it)
                    } ?: run {
                        record(spec, traceId, IntegrationAuditPhase.MISSING, IntegrationOutcome.MISSING)
                        IntegrationFetchResult.Missing
                    }
                }
            }
        }
    }

    private suspend fun <T> executeProviderLoad(
        spec: IntegrationSpec<T>,
        traceId: String
    ): IntegrationFetchResult<T> {
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) {
            record(spec, traceId, IntegrationAuditPhase.PLAYBACK_BLOCKED, IntegrationOutcome.MISSING)
            return IntegrationFetchResult.Missing
        }
        if (backoffManager.isBlocked(spec.provider, spec.scope)) {
            record(spec, traceId, IntegrationAuditPhase.BACKOFF_BLOCKED, IntegrationOutcome.MISSING)
            return IntegrationFetchResult.Missing
        }

        record(spec, traceId, IntegrationAuditPhase.LANE_QUEUED)
        return requestGate.withPermit(spec.provider) {
            record(spec, traceId, IntegrationAuditPhase.NETWORK_START, networkStarted = true, loaderInvoked = true)
            val result = try {
                withContext(networkPermit(spec, traceId)) {
                    spec.load()
                }
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                noteSyntheticNetworkFailure(
                    provider = spec.provider,
                    scope = spec.scope,
                    retryAfterMs = null,
                    reason = exception.message
                )
                record(spec, traceId, IntegrationAuditPhase.FAILED, IntegrationOutcome.NETWORK_ERROR, networkStarted = true, loaderInvoked = true)
                return@withPermit IntegrationFetchResult.Missing
            }

            when (result) {
                is IntegrationLoadResult.Success -> {
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.SUCCESS, networkStarted = true, loaderInvoked = true)
                    IntegrationFetchResult.Updated(result.value)
                }
                is IntegrationLoadResult.HttpError -> {
                    if (result.statusCode == 429 || result.statusCode >= 500) {
                        backoffManager.noteHttpFailure(
                            provider = spec.provider,
                            scope = spec.scope,
                            statusCode = result.statusCode,
                            retryAfterMs = result.retryAfterMs,
                            reason = result.reason
                        )
                    }
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.HTTP_ERROR, result.statusCode, result.retryAfterMs, networkStarted = true, loaderInvoked = true)
                    IntegrationFetchResult.Missing
                }
                is IntegrationLoadResult.NetworkError -> {
                    if (result.throwable is CancellationException) throw result.throwable
                    noteSyntheticNetworkFailure(
                        provider = spec.provider,
                        scope = spec.scope,
                        retryAfterMs = result.retryAfterMs,
                        reason = result.throwable.message
                    )
                    record(spec, traceId, IntegrationAuditPhase.NETWORK_END, IntegrationOutcome.NETWORK_ERROR, retryAfterMs = result.retryAfterMs, networkStarted = true, loaderInvoked = true)
                    IntegrationFetchResult.Missing
                }
            }
        }
    }

    private suspend fun noteSyntheticNetworkFailure(
        provider: IntegrationProvider,
        scope: IntegrationScope,
        retryAfterMs: Long?,
        reason: String?
    ) {
        backoffManager.noteHttpFailure(
            provider = provider,
            scope = scope,
            statusCode = SYNTHETIC_NETWORK_FAILURE_STATUS,
            retryAfterMs = retryAfterMs,
            reason = reason
        )
    }

    private companion object {
        const val SYNTHETIC_NETWORK_FAILURE_STATUS = 503
    }

    private fun traceId(operationKey: String): String =
        "${operationKey}:${System.nanoTime()}"

    private fun <T> networkPermit(spec: IntegrationSpec<T>, traceId: String) =
        IntegrationNetworkPermitContext.contextElement(
            IntegrationNetworkPermit(
                traceId = traceId,
                provider = spec.provider,
                apiShapeId = spec.apiShapeId,
                operationKey = spec.operationKey,
                workClass = spec.workClass,
                cachePolicy = spec.cachePolicy::class.simpleName ?: spec.cachePolicy.toString()
            )
        )

    private fun <T> networkPermit(spec: IntegrationCallSpec<T>, traceId: String) =
        IntegrationNetworkPermitContext.contextElement(
            IntegrationNetworkPermit(
                traceId = traceId,
                provider = spec.provider,
                apiShapeId = spec.apiShapeId,
                operationKey = spec.operationKey,
                workClass = spec.workClass,
                cachePolicy = IntegrationCachePolicy.ObserveOnly::class.simpleName ?: "ObserveOnly"
            )
        )

    private fun <T> record(
        spec: IntegrationSpec<T>,
        traceId: String,
        phase: IntegrationAuditPhase,
        outcome: IntegrationOutcome? = null,
        httpStatus: Int? = null,
        retryAfterMs: Long? = null,
        networkStarted: Boolean = false,
        loaderInvoked: Boolean = false
    ) {
        auditSink.record(
            baseEvent(
                traceId = traceId,
                provider = spec.provider,
                apiShapeId = spec.apiShapeId,
                headerPolicyId = spec.headerPolicyId,
                adapterClass = spec.operationKey.substringBefore('.').ifBlank { spec.provider.name },
                cacheKey = spec.cacheKey,
                scope = spec.scope,
                workClass = spec.workClass,
                cachePolicy = spec.cachePolicy.auditName(),
                codec = spec.codec::class.simpleName ?: spec.codec.mimeType,
                laneConcurrency = registry.policyFor(spec.provider).maxConcurrentNetworkStarts,
                phase = phase,
                outcome = outcome,
                httpStatus = httpStatus,
                retryAfterMs = retryAfterMs,
                networkStarted = networkStarted,
                loaderInvoked = loaderInvoked
            )
        )
    }

    private fun <T> record(
        spec: IntegrationCallSpec<T>,
        traceId: String,
        phase: IntegrationAuditPhase,
        outcome: IntegrationOutcome? = null,
        httpStatus: Int? = null,
        retryAfterMs: Long? = null,
        networkStarted: Boolean = false,
        loaderInvoked: Boolean = false
    ) {
        auditSink.record(
            baseEvent(
                traceId = traceId,
                provider = spec.provider,
                apiShapeId = spec.apiShapeId,
                headerPolicyId = spec.headerPolicyId,
                adapterClass = spec.operationKey.substringBefore('.').ifBlank { spec.provider.name },
                cacheKey = null,
                scope = spec.scope,
                workClass = spec.workClass,
                cachePolicy = "Call",
                codec = null,
                laneConcurrency = registry.policyFor(spec.provider).maxConcurrentNetworkStarts,
                phase = phase,
                outcome = outcome,
                httpStatus = httpStatus,
                retryAfterMs = retryAfterMs,
                networkStarted = networkStarted,
                loaderInvoked = loaderInvoked
            )
        )
    }

    private fun <T> record(
        spec: IntegrationStreamSpec<T>,
        traceId: String,
        phase: IntegrationAuditPhase,
        outcome: IntegrationOutcome? = null,
        httpStatus: Int? = null,
        retryAfterMs: Long? = null,
        networkStarted: Boolean = false,
        loaderInvoked: Boolean = false
    ) {
        auditSink.record(
            baseEvent(
                traceId = traceId,
                provider = spec.provider,
                apiShapeId = spec.apiShapeId,
                headerPolicyId = spec.headerPolicyId,
                adapterClass = spec.operationKey.substringBefore('.').ifBlank { spec.provider.name },
                cacheKey = null,
                scope = spec.scope,
                workClass = spec.workClass,
                cachePolicy = "Stream",
                codec = null,
                laneConcurrency = registry.policyFor(spec.provider).maxConcurrentNetworkStarts,
                phase = phase,
                outcome = outcome,
                httpStatus = httpStatus,
                retryAfterMs = retryAfterMs,
                networkStarted = networkStarted,
                loaderInvoked = loaderInvoked
            )
        )
    }

    private fun baseEvent(
        traceId: String,
        provider: IntegrationProvider,
        apiShapeId: String?,
        headerPolicyId: String?,
        adapterClass: String,
        cacheKey: String?,
        scope: IntegrationScope,
        workClass: IntegrationWorkClass,
        cachePolicy: String,
        codec: String?,
        laneConcurrency: Int,
        phase: IntegrationAuditPhase,
        outcome: IntegrationOutcome?,
        httpStatus: Int?,
        retryAfterMs: Long?,
        networkStarted: Boolean,
        loaderInvoked: Boolean
    ): IntegrationAuditEvent =
        IntegrationAuditEvent(
            traceId = traceId,
            provider = provider,
            apiShapeId = apiShapeId,
            headerPolicyId = headerPolicyId,
            adapterClass = adapterClass,
            cacheKeyHash = cacheKey?.stableHash(),
            cacheKeyTemplate = cacheKey,
            scopeKeyHash = scope.storageKey.stableHash(),
            scopeName = scope.auditName,
            profileId = scope.profileIdOrNull(),
            accountProvider = (scope as? IntegrationScope.Account)?.provider?.name,
            credentialHash = (scope as? IntegrationScope.Account)?.credentialHash,
            language = (scope as? IntegrationScope.GlobalLocalizedContent)?.language,
            imageLanguage = if (scope == IntegrationScope.GlobalEnglishImage) "en" else null,
            workClass = workClass,
            cachePolicy = cachePolicy,
            codec = codec,
            laneConcurrency = laneConcurrency,
            playbackActive = playbackGate.isPlaybackActive(),
            phase = phase,
            outcome = outcome,
            httpStatus = httpStatus,
            retryAfterMs = retryAfterMs,
            freshUntilEpochMs = null,
            staleUntilEpochMs = null,
            networkStarted = networkStarted,
            loaderInvoked = loaderInvoked,
            durationMs = 0L,
            timestampEpochMs = System.currentTimeMillis()
        )

    private fun IntegrationCachePolicy.auditName(): String =
        when (this) {
            IntegrationCachePolicy.Disabled -> "Disabled"
            is IntegrationCachePolicy.ObserveOnly -> "ObserveOnly"
            is IntegrationCachePolicy.CacheFirst -> "CacheFirst"
            IntegrationCachePolicy.Mutation -> "Mutation"
        }

    private fun IntegrationScope.profileIdOrNull(): Int? =
        when (this) {
            is IntegrationScope.Profile -> profileId
            is IntegrationScope.ProfileLocal -> profileId
            is IntegrationScope.Account -> profileId
            else -> null
        }

    private fun String.stableHash(): String =
        hashCode().toUInt().toString(16)
}

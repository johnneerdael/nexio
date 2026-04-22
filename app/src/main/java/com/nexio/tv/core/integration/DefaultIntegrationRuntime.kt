package com.nexio.tv.core.integration

import kotlin.coroutines.cancellation.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class DefaultIntegrationRuntime @Inject constructor(
    private val cacheStore: IntegrationCacheStore,
    private val requestGate: ProviderRequestGate,
    private val backoffManager: IntegrationBackoffManager,
    private val singleFlight: IntegrationSingleFlight,
    private val playbackGate: IntegrationPlaybackGate,
    private val registry: IntegrationPolicyRegistry
) : IntegrationRuntime {

    override suspend fun <T> get(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        return when (val policy = spec.cachePolicy) {
            IntegrationCachePolicy.Disabled -> executeWithoutCache(spec, options)
            is IntegrationCachePolicy.ObserveOnly -> executeObserveOnly(spec, policy, options)
            is IntegrationCachePolicy.CacheFirst -> executeCacheFirst(spec, policy, options)
            IntegrationCachePolicy.Mutation -> executeMutation(spec, options)
        }
    }

    override suspend fun <T> call(spec: IntegrationCallSpec<T>): IntegrationCallResult<T> {
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) return IntegrationCallResult.Missing
        if (backoffManager.isBlocked(spec.provider, spec.scope)) return IntegrationCallResult.Missing

        return requestGate.withPermit(spec.provider) {
            val result = try {
                spec.call()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
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
                    result
                }
                is IntegrationCallResult.NetworkError -> result
                is IntegrationCallResult.Success -> result
                IntegrationCallResult.Missing -> IntegrationCallResult.Missing
            }
        }
    }

    override suspend fun <T> open(spec: IntegrationStreamSpec<T>): IntegrationStreamHandle<T>? {
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) return null
        if (backoffManager.isBlocked(spec.provider, spec.scope)) return null

        return requestGate.withPermit(spec.provider) {
            try {
                spec.open()
            } catch (exception: Exception) {
                if (exception is CancellationException) throw exception
                null
            }
        }
    }

    private suspend fun <T> executeWithoutCache(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        if (options.cacheOnly) return IntegrationFetchResult.Missing
        return executeProviderLoad(spec)
    }

    private suspend fun <T> executeObserveOnly(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.ObserveOnly,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        return executeWithoutCache(spec, options)
    }

    private suspend fun <T> executeMutation(
        spec: IntegrationSpec<T>,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        if (options.cacheOnly) return IntegrationFetchResult.Missing
        return executeProviderLoad(spec)
    }

    private suspend fun <T> executeCacheFirst(
        spec: IntegrationSpec<T>,
        policy: IntegrationCachePolicy.CacheFirst,
        options: IntegrationFetchOptions
    ): IntegrationFetchResult<T> {
        cacheStore.readFresh(spec)?.let { return IntegrationFetchResult.Fresh(it) }

        val providerPolicy = registry.policyFor(spec.provider)
        if (options.cacheOnly || playbackGate.isBlocked(providerPolicy, spec.workClass)) {
            val stale = cacheStore.readStale(spec)
            return if (stale != null && providerPolicy.allowStaleWhilePaused && options.allowStaleOnFailure) {
                IntegrationFetchResult.Stale(stale)
            } else {
                IntegrationFetchResult.Missing
            }
        }

        return singleFlight.run(spec.cacheKey) {
            cacheStore.readFresh(spec)?.let { return@run IntegrationFetchResult.Fresh(it) }
            if (backoffManager.isBlocked(spec.provider, spec.scope)) {
                cacheStore.readStale(spec)?.let { return@run IntegrationFetchResult.Stale(it) }
                return@run IntegrationFetchResult.Missing
            }

            when (val result = executeProviderLoad(spec)) {
                is IntegrationFetchResult.Updated -> {
                    cacheStore.write(spec, result.value)
                    result
                }
                is IntegrationFetchResult.Fresh -> result
                is IntegrationFetchResult.Stale -> result
                IntegrationFetchResult.Missing -> {
                    cacheStore.readStale(spec)?.let { IntegrationFetchResult.Stale(it) }
                        ?: IntegrationFetchResult.Missing
                }
            }
        }
    }

    private suspend fun <T> executeProviderLoad(spec: IntegrationSpec<T>): IntegrationFetchResult<T> {
        val policy = registry.policyFor(spec.provider)
        if (playbackGate.isBlocked(policy, spec.workClass)) return IntegrationFetchResult.Missing
        if (backoffManager.isBlocked(spec.provider, spec.scope)) return IntegrationFetchResult.Missing

        return requestGate.withPermit(spec.provider) {
            when (val result = spec.load()) {
                is IntegrationLoadResult.Success -> IntegrationFetchResult.Updated(result.value)
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
                    IntegrationFetchResult.Missing
                }
                is IntegrationLoadResult.NetworkError -> IntegrationFetchResult.Missing
            }
        }
    }
}

package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

private const val DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS = 6
private const val DEFAULT_SERVICE_WRAP_CHUNK_SIZE = 20
private const val DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS = 250L

@Singleton
class ServiceWrapSessionFactory @Inject constructor(
    private val extractor: WrapCandidateExtractor,
    private val resolver: ServiceWrapResolver,
    private val wrappedStreamBuilder: WrappedStreamBuilder
) {
    private var maxConcurrentResolutions: Int = DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS
    private var chunkSize: Int = DEFAULT_SERVICE_WRAP_CHUNK_SIZE
    private var chunkFlushDelayMs: Long = DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS

    internal constructor(
        extractor: WrapCandidateExtractor,
        resolver: ServiceWrapResolver,
        wrappedStreamBuilder: WrappedStreamBuilder,
        maxConcurrentResolutions: Int = DEFAULT_MAX_CONCURRENT_SERVICE_WRAP_RESOLUTIONS,
        chunkSize: Int = DEFAULT_SERVICE_WRAP_CHUNK_SIZE,
        chunkFlushDelayMs: Long = DEFAULT_SERVICE_WRAP_CHUNK_FLUSH_DELAY_MS
    ) : this(
        extractor = extractor,
        resolver = resolver,
        wrappedStreamBuilder = wrappedStreamBuilder
    ) {
        this.maxConcurrentResolutions = maxConcurrentResolutions.coerceAtLeast(1)
        this.chunkSize = chunkSize.coerceAtLeast(1)
        this.chunkFlushDelayMs = chunkFlushDelayMs.coerceAtLeast(0L)
    }

    fun createSession(
        requestContext: ServiceWrapRequestContext,
        scope: CoroutineScope,
        onResolved: suspend (ServiceWrapResolvedBatch) -> Unit
    ): ServiceWrapSession {
        return Session(
            requestContext = requestContext,
            scope = scope,
            extractor = extractor,
            resolver = resolver,
            wrappedStreamBuilder = wrappedStreamBuilder,
            maxConcurrentResolutions = maxConcurrentResolutions,
            chunkSize = chunkSize,
            chunkFlushDelayMs = chunkFlushDelayMs,
            onResolved = onResolved
        )
    }

    private class Session(
        private val requestContext: ServiceWrapRequestContext,
        private val scope: CoroutineScope,
        private val extractor: WrapCandidateExtractor,
        private val resolver: ServiceWrapResolver,
        private val wrappedStreamBuilder: WrappedStreamBuilder,
        maxConcurrentResolutions: Int,
        private val chunkSize: Int,
        private val chunkFlushDelayMs: Long,
        private val onResolved: suspend (ServiceWrapResolvedBatch) -> Unit
    ) : ServiceWrapSession {
        private val seenHashes = HashSet<String>()
        private val inFlight = AtomicInteger(0)
        private val resolutionPermits = Semaphore(maxConcurrentResolutions)
        private val pendingCandidates = ArrayList<WrapCandidate>()
        private var pendingFlushJob: Job? = null

        override fun processAddonStreams(
            addonName: String,
            addonLogo: String?,
            streams: List<Stream>
        ): ServiceWrapProcessResult {
            val visibleStreams = ArrayList<Stream>(streams.size)
            var launchedWrapCount = 0
            streams.forEach { stream ->
                val candidate = extractor.extractCandidate(
                    addonName = addonName,
                    addonLogo = addonLogo,
                    stream = stream
                )
                if (candidate == null) {
                    visibleStreams += stream
                    return@forEach
                }
                if (!seenHashes.add(candidate.normalizedInfoHash)) {
                    return@forEach
                }
                launchedWrapCount += 1
                inFlight.incrementAndGet()
                enqueueCandidate(candidate)
            }
            return ServiceWrapProcessResult(
                visibleStreams = visibleStreams,
                launchedWrapCount = launchedWrapCount
            )
        }

        override fun inFlightCount(): Int = inFlight.get()

        private fun enqueueCandidate(candidate: WrapCandidate) {
            pendingCandidates += candidate
            if (pendingCandidates.size >= chunkSize) {
                flushPendingCandidates()
                return
            }
            if (pendingFlushJob == null) {
                pendingFlushJob = scope.launch {
                    delay(chunkFlushDelayMs)
                    flushPendingCandidates()
                }
            }
        }

        private fun flushPendingCandidates() {
            if (pendingCandidates.isEmpty()) return
            pendingFlushJob?.cancel()
            pendingFlushJob = null

            val chunk = pendingCandidates.toList()
            pendingCandidates.clear()
            launchChunkResolution(chunk)
        }

        private fun launchChunkResolution(candidates: List<WrapCandidate>) {
            scope.launch {
                val terminalHashes = HashSet<String>()
                try {
                    resolutionPermits.withPermit {
                        resolver.resolveChunkProgressively(
                            candidates = candidates,
                            requestContext = requestContext
                        ).collect { resolution ->
                            candidates.forEach { candidate ->
                                if (!resolution.streamsByHash.containsKey(candidate.normalizedInfoHash)) {
                                    return@forEach
                                }
                                val resolved = resolution.streamsByHash[candidate.normalizedInfoHash].orEmpty()
                                val wrappedStreams = wrappedStreamBuilder.build(candidate, resolved)
                                val isTerminal = resolution.isTerminal
                                if (isTerminal) terminalHashes += candidate.normalizedInfoHash
                                if (wrappedStreams.isEmpty() && !isTerminal) return@forEach
                                onResolved(
                                    ServiceWrapResolvedBatch(
                                        addonName = candidate.sourceAddonName,
                                        addonLogo = candidate.sourceAddonLogo,
                                        wrappedStreams = wrappedStreams,
                                        isTerminal = isTerminal
                                    )
                                )
                            }
                        }
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    candidates.forEach { candidate ->
                        terminalHashes += candidate.normalizedInfoHash
                        onResolved(
                            ServiceWrapResolvedBatch(
                                addonName = candidate.sourceAddonName,
                                addonLogo = candidate.sourceAddonLogo,
                                wrappedStreams = emptyList(),
                                isTerminal = true
                            )
                        )
                    }
                } finally {
                    candidates.forEach { candidate ->
                        if (candidate.normalizedInfoHash !in terminalHashes) {
                            onResolved(
                                ServiceWrapResolvedBatch(
                                    addonName = candidate.sourceAddonName,
                                    addonLogo = candidate.sourceAddonLogo,
                                    wrappedStreams = emptyList(),
                                    isTerminal = true
                                )
                            )
                        }
                        inFlight.decrementAndGet()
                    }
                }
            }
        }
    }
}

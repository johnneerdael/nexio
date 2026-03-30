package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ServiceWrapSessionFactory @Inject constructor(
    private val extractor: WrapCandidateExtractor,
    private val resolver: ServiceWrapResolver,
    private val wrappedStreamBuilder: WrappedStreamBuilder
) {

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
            onResolved = onResolved
        )
    }

    private class Session(
        private val requestContext: ServiceWrapRequestContext,
        private val scope: CoroutineScope,
        private val extractor: WrapCandidateExtractor,
        private val resolver: ServiceWrapResolver,
        private val wrappedStreamBuilder: WrappedStreamBuilder,
        private val onResolved: suspend (ServiceWrapResolvedBatch) -> Unit
    ) : ServiceWrapSession {
        private val seenHashes = HashSet<String>()
        private val inFlight = AtomicInteger(0)

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
                scope.launch {
                    try {
                        val resolved = resolver.resolve(
                            candidate = candidate,
                            requestContext = requestContext
                        )
                        val wrappedStreams = wrappedStreamBuilder.build(candidate, resolved)
                        onResolved(
                            ServiceWrapResolvedBatch(
                                addonName = addonName,
                                addonLogo = addonLogo,
                                wrappedStreams = wrappedStreams
                            )
                        )
                    } finally {
                        inFlight.decrementAndGet()
                    }
                }
            }
            return ServiceWrapProcessResult(
                visibleStreams = visibleStreams,
                launchedWrapCount = launchedWrapCount
            )
        }

        override fun inFlightCount(): Int = inFlight.get()
    }
}

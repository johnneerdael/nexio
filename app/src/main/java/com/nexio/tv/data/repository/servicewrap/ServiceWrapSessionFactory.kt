package com.nexio.tv.data.repository.servicewrap

import com.nexio.tv.domain.model.Stream
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.LinkedHashMap
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
        private val lock = Any()
        private val hashEntries = HashMap<String, HashEntry>()
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

                val action = synchronized(lock) {
                    val entry = hashEntries.getOrPut(candidate.normalizedInfoHash) { HashEntry() }
                    val added = entry.candidatesByAddonName.putIfAbsent(candidate.sourceAddonName, candidate) == null
                    if (!added) {
                        null
                    } else {
                        launchedWrapCount += 1
                        when {
                            entry.completed -> PendingAction.EmitResolved(
                                candidate = candidate,
                                resolvedStreams = entry.resolvedStreams.orEmpty()
                            )

                            entry.resolving -> null
                            else -> {
                                entry.resolving = true
                                PendingAction.ResolveHash(
                                    normalizedInfoHash = candidate.normalizedInfoHash
                                )
                            }
                        }
                    }
                }

                when (action) {
                    is PendingAction.ResolveHash -> {
                        inFlight.incrementAndGet()
                        scope.launch {
                            try {
                                val seedCandidate = synchronized(lock) {
                                    hashEntries[action.normalizedInfoHash]
                                        ?.candidatesByAddonName
                                        ?.values
                                        ?.firstOrNull()
                                } ?: return@launch

                                val resolved = resolver.resolve(
                                    candidate = seedCandidate,
                                    requestContext = requestContext
                                )

                                val candidates = synchronized(lock) {
                                    hashEntries[action.normalizedInfoHash]?.let { entry ->
                                        entry.completed = true
                                        entry.resolving = false
                                        entry.resolvedStreams = resolved
                                        entry.candidatesByAddonName.values.toList()
                                    }
                                }.orEmpty()

                                candidates.forEach { sourceCandidate ->
                                    emitResolvedBatch(sourceCandidate, resolved)
                                }
                            } finally {
                                inFlight.decrementAndGet()
                            }
                        }
                    }

                    is PendingAction.EmitResolved -> {
                        scope.launch {
                            emitResolvedBatch(action.candidate, action.resolvedStreams)
                        }
                    }

                    null -> Unit
                }
            }

            return ServiceWrapProcessResult(
                visibleStreams = visibleStreams,
                launchedWrapCount = launchedWrapCount
            )
        }

        private suspend fun emitResolvedBatch(
            candidate: WrapCandidate,
            resolvedStreams: List<ResolvedServiceWrapStream>
        ) {
            onResolved(
                ServiceWrapResolvedBatch(
                    addonName = candidate.sourceAddonName,
                    addonLogo = candidate.sourceAddonLogo,
                    wrappedStreams = wrappedStreamBuilder.build(candidate, resolvedStreams)
                )
            )
        }

        override fun inFlightCount(): Int = inFlight.get()
    }
}

private sealed interface PendingAction {
    data class ResolveHash(val normalizedInfoHash: String) : PendingAction
    data class EmitResolved(
        val candidate: WrapCandidate,
        val resolvedStreams: List<ResolvedServiceWrapStream>
    ) : PendingAction
}

private class HashEntry {
    val candidatesByAddonName = LinkedHashMap<String, WrapCandidate>()
    var resolving: Boolean = false
    var completed: Boolean = false
    var resolvedStreams: List<ResolvedServiceWrapStream>? = null
}

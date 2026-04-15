package com.nexio.tv.data.repository

internal data class ContinueWatchingResumeRef(
    val contentId: String,
    val activityAtMs: Long,
    val suppressNextUp: Boolean
)

internal data class ContinueWatchingNextUpRef(
    val contentId: String,
    val activityAtMs: Long,
    val firstAiredMs: Long,
    val availabilityInstantMs: Long? = null
)

internal data class ContinueWatchingNextUpSelection<T>(
    val mainFeedItems: List<T>,
    val syntheticRailItems: List<T>
)

internal sealed interface ContinueWatchingTimelineRow<out R, out N> {
    data class Resume<R>(val value: R) : ContinueWatchingTimelineRow<R, Nothing>
    data class NextUp<N>(val value: N) : ContinueWatchingTimelineRow<Nothing, N>
}

internal fun <T> splitNextUpCandidatesForContinueWatching(
    resumes: List<ContinueWatchingResumeRef>,
    nextUpItems: List<T>,
    nextUpRef: (T) -> ContinueWatchingNextUpRef,
    nowMs: Long
): ContinueWatchingNextUpSelection<T> {
    val pausedShowIds = resumes
        .asSequence()
        .filter { it.suppressNextUp }
        .map { it.contentId.trim() }
        .filter { it.isNotBlank() }
        .toSet()

    val syntheticRailItems = nextUpItems

    val mainFeedItems = nextUpItems.filter { candidate ->
        val ref = nextUpRef(candidate)
        ref.contentId.trim() !in pausedShowIds &&
            AirDateGate.isAired(
                availabilityInstantMs = ref.availabilityInstantMs,
                firstAiredMs = ref.firstAiredMs,
                tmdbAirDate = null,
                nowMs = nowMs
            )
    }

    return ContinueWatchingNextUpSelection(
        mainFeedItems = mainFeedItems,
        syntheticRailItems = syntheticRailItems
    )
}

internal fun <R, N> buildMixedContinueWatchingTimeline(
    resumeItems: List<R>,
    nextUpItems: List<N>,
    resumeRef: (R) -> ContinueWatchingResumeRef,
    nextUpRef: (N) -> ContinueWatchingNextUpRef,
    nearEqualWindowMs: Long = 60_000L
): List<ContinueWatchingTimelineRow<R, N>> {
    val entries = buildList {
        resumeItems.forEach { item ->
            add(
                TimelineEntry.Resume(
                    value = item,
                    ref = resumeRef(item)
                )
            )
        }
        nextUpItems.forEach { item ->
            add(
                TimelineEntry.NextUp(
                    value = item,
                    ref = nextUpRef(item)
                )
            )
        }
    }.sortedWith(
        compareByDescending<TimelineEntry<R, N>> { it.activityAtMs }
            .thenBy { it.stableKey() }
    )

    if (entries.isEmpty()) return emptyList()

    val rows = mutableListOf<ContinueWatchingTimelineRow<R, N>>()
    var clusterStart = 0
    while (clusterStart < entries.size) {
        val clusterHeadActivity = entries[clusterStart].activityAtMs
        var clusterEnd = clusterStart + 1
        while (
            clusterEnd < entries.size &&
            clusterHeadActivity - entries[clusterEnd].activityAtMs <= nearEqualWindowMs
        ) {
            clusterEnd += 1
        }

        entries.subList(clusterStart, clusterEnd)
            .let { cluster ->
                var leadResume: TimelineEntry.Resume<R>? = null
                cluster.forEach { entry ->
                    if (entry is TimelineEntry.Resume<R>) {
                        val current = leadResume
                        if (
                            current == null ||
                            entry.activityAtMs > current.activityAtMs ||
                            (entry.activityAtMs == current.activityAtMs &&
                                entry.stableKey() < current.stableKey())
                        ) {
                            leadResume = entry
                        }
                    }
                }
                val remaining = if (leadResume == null) {
                    cluster
                } else {
                    buildList {
                        addAll(cluster)
                        remove(leadResume)
                    }
                }.sortedWith(
                    compareByDescending<TimelineEntry<R, N>> { it.activityAtMs }
                        .thenBy { it.kindPriority }
                        .thenBy { it.stableKey() }
                )
                buildList {
                    leadResume?.let(::add)
                    addAll(remaining)
                }
            }
            .forEach { entry ->
                rows += when (entry) {
                    is TimelineEntry.Resume -> ContinueWatchingTimelineRow.Resume(entry.value)
                    is TimelineEntry.NextUp -> ContinueWatchingTimelineRow.NextUp(entry.value)
                }
            }

        clusterStart = clusterEnd
    }

    return rows
}

private sealed interface TimelineEntry<out R, out N> {
    val contentId: String
    val activityAtMs: Long
    val kindPriority: Int

    data class Resume<R>(
        val value: R,
        val ref: ContinueWatchingResumeRef
    ) : TimelineEntry<R, Nothing> {
        override val contentId: String = ref.contentId
        override val activityAtMs: Long = ref.activityAtMs
        override val kindPriority: Int = 0
    }

    data class NextUp<N>(
        val value: N,
        val ref: ContinueWatchingNextUpRef
    ) : TimelineEntry<Nothing, N> {
        override val contentId: String = ref.contentId
        override val activityAtMs: Long = ref.activityAtMs
        override val kindPriority: Int = 1
    }
}

private fun <R, N> TimelineEntry<R, N>.stableKey(): String {
    return "${kindPriority}:${contentId.trim()}:${activityAtMs}"
}

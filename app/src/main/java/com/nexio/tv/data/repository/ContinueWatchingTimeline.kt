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
    nowMs: Long,
    nearEqualWindowMs: Long = 60_000L
): ContinueWatchingNextUpSelection<T> {
    val pausedShowActivityById = resumes
        .asSequence()
        .filter { it.suppressNextUp }
        .mapNotNull { ref ->
            val contentId = ref.contentId.trim()
            if (contentId.isBlank()) null else contentId to ref.activityAtMs
        }
        .groupingBy { it.first }
        .fold(0L) { latest, (_, activityAtMs) -> maxOf(latest, activityAtMs) }

    val syntheticRailItems = nextUpItems

    val mainFeedItems = nextUpItems.filter { candidate ->
        val ref = nextUpRef(candidate)
        val pausedActivityAtMs = pausedShowActivityById[ref.contentId.trim()]
        val suppressedByCurrentResume = pausedActivityAtMs != null &&
            pausedActivityAtMs >= ref.activityAtMs - nearEqualWindowMs
        !suppressedByCurrentResume &&
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
    }.dedupeByContentActivity(nearEqualWindowMs).sortedWith(
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

private fun <R, N> List<TimelineEntry<R, N>>.dedupeByContentActivity(
    nearEqualWindowMs: Long
): List<TimelineEntry<R, N>> {
    val selectedByContent = linkedMapOf<String, TimelineEntry<R, N>>()
    forEach { entry ->
        val key = entry.contentId.trim()
        if (key.isBlank()) return@forEach
        val existing = selectedByContent[key]
        if (existing == null || shouldPreferTimelineEntry(existing, entry, nearEqualWindowMs)) {
            selectedByContent[key] = entry
        }
    }
    return selectedByContent.values.toList()
}

private fun <R, N> shouldPreferTimelineEntry(
    existing: TimelineEntry<R, N>,
    candidate: TimelineEntry<R, N>,
    nearEqualWindowMs: Long
): Boolean {
    val activityDelta = candidate.activityAtMs - existing.activityAtMs
    if (activityDelta > nearEqualWindowMs) return true
    if (activityDelta < -nearEqualWindowMs) return false

    if (candidate.kindPriority != existing.kindPriority) {
        return candidate.kindPriority < existing.kindPriority
    }

    if (candidate.activityAtMs != existing.activityAtMs) {
        return candidate.activityAtMs > existing.activityAtMs
    }

    return candidate.stableKey() < existing.stableKey()
}

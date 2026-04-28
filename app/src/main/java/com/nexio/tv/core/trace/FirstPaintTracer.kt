package com.nexio.tv.core.trace

/**
 * Static install slot used by domain-model extension functions (e.g.
 * `MetaPreview.toHomeDisplayMetadata()`) to emit `metadata.first_paint`
 * without requiring DI plumbing into pure-domain types.
 *
 * Production wiring lives in [com.nexio.tv.core.di.RuntimeTraceModule], using
 * the same install-from-Hilt-singleton pattern as
 * [com.nexio.tv.core.integration.ProfileBoundaryEnforcer] and
 * [com.nexio.tv.data.repository.ContinueWatchingSnapshotService].
 */
object FirstPaintTracer {
    @Volatile
    private var events: TraceMetadataEvents = TraceMetadataEvents(NoopRuntimeTraceSink, sessionId = { null })

    @Volatile
    private var profileHashProvider: () -> String? = { null }

    @JvmStatic
    fun install(events: TraceMetadataEvents, profileHashProvider: () -> String?) {
        this.events = events
        this.profileHashProvider = profileHashProvider
    }

    fun recordHomePreview(
        contentId: String,
        itemType: String,
        source: String = "ADDON_META_PREVIEW",
        fieldsUsed: Set<String>
    ) {
        events.emitFirstPaint(
            contentId = contentId,
            itemType = itemType,
            surface = SourceSurface.HOME,
            source = source,
            fieldsUsed = fieldsUsed.toList(),
            routerExecuted = false,
            networkExecuted = false,
            profileHash = profileHashProvider()
        )
    }
}

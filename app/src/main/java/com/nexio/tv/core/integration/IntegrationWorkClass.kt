package com.nexio.tv.core.integration

enum class IntegrationWorkClass {
    USER_VISIBLE,
    PLAYBACK_CRITICAL,
    PLAYBACK_RESOLUTION,
    SCROBBLE,
    MUTATION_OUTBOX,
    BACKGROUND_HYDRATION,
    PREFETCH,
    MAINTENANCE
}

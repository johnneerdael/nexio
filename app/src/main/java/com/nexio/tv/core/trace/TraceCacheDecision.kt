package com.nexio.tv.core.trace

enum class TraceCacheDecision {
    HIT,
    MISS_THEN_NETWORK,
    STALE_HIT,
    EXPIRED_MISS,
    BYPASS_DISABLED,
    OBSERVE_ONLY,
    WRITE,
    INVALIDATED,
    EVICTED
}

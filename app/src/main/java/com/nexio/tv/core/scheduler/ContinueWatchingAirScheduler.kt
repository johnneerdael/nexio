package com.nexio.tv.core.scheduler

interface ContinueWatchingAirScheduler {
    fun scheduleSoonest(triggerAtMs: Long?)
    fun cancel()
}

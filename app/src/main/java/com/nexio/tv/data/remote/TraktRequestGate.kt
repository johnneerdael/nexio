package com.nexio.tv.data.remote

import android.util.Log
import com.nexio.tv.BuildConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Coroutine-based serial request gate for all Trakt API calls.
 *
 * Enforces a minimum of [MIN_INTERVAL_MS] between any two Trakt HTTP requests,
 * using [Mutex] + [delay] instead of Thread.sleep. This prevents thread-pool
 * starvation that the old OkHttp-interceptor approach caused, and eliminates
 * request bursts that trigger Trakt 429 rate limits.
 *
 * All Trakt API traffic flows through [TraktAuthService.executeAuthOwnerRequest],
 * which acquires this gate before dispatching each request.
 */
@Singleton
class TraktRequestGate @Inject constructor() {

    companion object {
        private const val TAG = "TraktRequestGate"
        /** Minimum milliseconds between request starts (matches NuvioMobile's 500ms queue). */
        const val MIN_INTERVAL_MS = 500L
    }

    private val mutex = Mutex()

    @Volatile
    private var lastRequestAtMs = 0L

    /**
     * Acquires the gate, waits for the minimum interval since the last request,
     * then executes [block]. The mutex is held for the full duration so requests
     * are strictly serial.
     */
    suspend fun <T> acquire(block: suspend () -> T): T {
        mutex.withLock {
            val now = System.currentTimeMillis()
            val elapsed = now - lastRequestAtMs
            if (lastRequestAtMs > 0L && elapsed < MIN_INTERVAL_MS) {
                val waitMs = MIN_INTERVAL_MS - elapsed
                if (BuildConfig.DEBUG) {
                    Log.d(TAG, "gate: waiting ${waitMs}ms before next request")
                }
                delay(waitMs)
            }
            lastRequestAtMs = System.currentTimeMillis()
            return block()
        }
    }
}

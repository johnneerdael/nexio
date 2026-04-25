package com.nexio.tv.core.player.auth

import java.util.concurrent.atomic.AtomicReference

/**
 * Process-wide accessor for the singleton [EgressIpFingerprint] without
 * requiring [AuthRecoveryInterceptor] to take a constructor dependency on
 * [EgressIpFingerprint] (which would create a Hilt cycle through the playback
 * `OkHttpClient`).
 *
 * NetworkModule sets the instance once at app start; the interceptor reads it
 * lazily on each auth-failure response.
 */
object PlaybackAuthFingerprintHolder {
    private val ref = AtomicReference<EgressIpFingerprint?>(null)

    fun setInstance(fingerprint: EgressIpFingerprint?) {
        ref.set(fingerprint)
    }

    fun current(): EgressIpFingerprint? = ref.get()
}

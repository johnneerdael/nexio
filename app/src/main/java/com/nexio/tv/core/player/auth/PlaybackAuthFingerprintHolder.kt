package com.nexio.tv.core.player.auth

import java.util.concurrent.atomic.AtomicReference

object PlaybackAuthFingerprintHolder {
    private val ref = AtomicReference<EgressIpFingerprint?>(null)

    fun setInstance(fingerprint: EgressIpFingerprint?) {
        ref.set(fingerprint)
    }

    fun current(): EgressIpFingerprint? = ref.get()
}

package com.nexio.tv.core.player.auth

import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.atomic.AtomicReference

/**
 * Captures the device's egress IP at session start and exposes a comparison
 * against the current IP. Used at auth-failure time to distinguish
 * "user's IP shifted" (CGNAT, VPN, network handoff — RD revokes signed links
 * bound to the original IP) from "RD revoked the link for other reasons".
 *
 * Probe endpoint is expected to return the caller's IP as plain text. Default
 * production endpoint is set in NetworkModule. Failures during probe yield
 * [State.Unknown] — never throw; this helper is observational only.
 */
class EgressIpFingerprint(
    private val client: OkHttpClient,
    private val probeUrl: String
) {
    sealed class State {
        data class Stable(val ip: String) : State()
        data class Changed(val baseline: String, val current: String) : State()
        data object Unknown : State()
    }

    private val baseline = AtomicReference<String?>(null)

    fun captureBaseline() {
        baseline.set(sampleNow())
    }

    fun sampleNow(): String? {
        return runCatching {
            val request = Request.Builder().url(probeUrl).get().build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@runCatching null
                response.body?.string()?.trim()?.takeIf { it.isNotBlank() }
            }
        }.getOrNull()
    }

    fun compareNow(): State {
        val base = baseline.get() ?: return State.Unknown
        val current = sampleNow() ?: return State.Unknown
        return if (current == base) State.Stable(base) else State.Changed(base, current)
    }
}

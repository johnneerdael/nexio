package com.nexio.tv.core.player.auth

/**
 * HTTP status codes that signal a transient upstream-CDN failure on what is
 * otherwise still a valid signed link. Recovery for these is two-phase:
 *
 *  1. Retry once against the same URL after a short backoff. Real-Debrid edge
 *     load balancers tend to route the second attempt to a different healthy
 *     edge; the signed URL itself is usually still live.
 *  2. If the retry also fails, escalate to the auth-style re-resolve path via
 *     [com.nexio.tv.core.player.CometProxyUrlResolver.recoverProxyBlocking].
 *
 * - 502 Bad Gateway — Real-Debrid CDN edges (`*.download.real-debrid.com`)
 *   return this mid-stream when the upstream backend hiccups.
 * - 503 Service Unavailable — emitted by Premiumize / EnergyCDN under load.
 * - 504 Gateway Timeout — sporadic; same recovery as 502.
 *
 * Distinct from [AuthFailureCodes] because the auth bucket signals
 * "the signed link is dead, don't retry it" whereas this bucket signals
 * "the link is probably still good, just hit a bad edge — try again".
 */
object TransientFailureCodes {
    const val BAD_GATEWAY = 502
    const val SERVICE_UNAVAILABLE = 503
    const val GATEWAY_TIMEOUT = 504

    val ALL: Set<Int> = setOf(BAD_GATEWAY, SERVICE_UNAVAILABLE, GATEWAY_TIMEOUT)

    fun matches(status: Int): Boolean = status in ALL
}

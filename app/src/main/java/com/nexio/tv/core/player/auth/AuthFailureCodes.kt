package com.nexio.tv.core.player.auth

/**
 * HTTP status codes treated as "the upstream signed link is no longer valid
 * for this request". Triggers re-resolution via [com.nexio.tv.core.player.CometProxyUrlResolver].
 *
 * - 401 Unauthorized — observed in production from real-debrid.com/d/<token>
 *   mirrors mid-stream when the token's IP/UA binding is no longer satisfied.
 * - 403 Forbidden — Premiumize and EnergyCDN paths emit this when the signed
 *   query parameter has expired.
 * - 410 Gone — some StremThru-fronted hosts emit this for definitively-revoked
 *   download links rather than 401.
 */
object AuthFailureCodes {
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val GONE = 410

    val ALL: Set<Int> = setOf(UNAUTHORIZED, FORBIDDEN, GONE)

    fun matches(status: Int): Boolean = status in ALL
}

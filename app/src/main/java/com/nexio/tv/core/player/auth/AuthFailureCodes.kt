package com.nexio.tv.core.player.auth

object AuthFailureCodes {
    const val UNAUTHORIZED = 401
    const val FORBIDDEN = 403
    const val GONE = 410

    val ALL: Set<Int> = setOf(UNAUTHORIZED, FORBIDDEN, GONE)

    fun matches(status: Int): Boolean = status in ALL
}

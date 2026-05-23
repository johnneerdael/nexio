package com.nexio.tv.data.trailer.potoken

import android.net.Uri

fun appendPoTokenToGoogleVideoUri(uri: Uri, token: String?): Uri {
    if (token.isNullOrBlank()) return uri
    val host = uri.host.orEmpty()
    if (!host.contains("googlevideo.com")) return uri
    if (uri.getQueryParameter("pot") == token) return uri
    return uri.buildUpon()
        .appendQueryParameter("pot", token)
        .build()
}

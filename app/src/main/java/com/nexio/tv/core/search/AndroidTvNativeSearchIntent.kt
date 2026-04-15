package com.nexio.tv.core.search

import android.net.Uri

object AndroidTvNativeSearchIntent {
    const val SCHEME = "nexio"
    const val HOST_DETAIL = "detail"
    const val PARAM_ITEM_ID = "itemId"
    const val PARAM_CONTENT_TYPE = "contentType"
    const val PARAM_ADDON_BASE_URL = "addonBaseUrl"

    fun buildDetailUri(suggestion: AndroidTvSearchSuggestion): Uri {
        val builder = Uri.parse("$SCHEME://$HOST_DETAIL").buildUpon()
            .appendQueryParameter(PARAM_ITEM_ID, suggestion.itemId)
            .appendQueryParameter(PARAM_CONTENT_TYPE, suggestion.contentType)

        suggestion.addonBaseUrl
            ?.takeIf { it.isNotBlank() }
            ?.let { builder.appendQueryParameter(PARAM_ADDON_BASE_URL, it) }

        return builder.build()
    }

    fun parseDetailUri(uri: Uri?): NativeSearchDetailTarget? {
        if (uri == null) return null
        if (!uri.scheme.equals(SCHEME, ignoreCase = true)) return null
        if (!uri.host.equals(HOST_DETAIL, ignoreCase = true)) return null

        val itemId = uri.getQueryParameter(PARAM_ITEM_ID)?.trim().orEmpty()
        val contentType = uri.getQueryParameter(PARAM_CONTENT_TYPE)?.trim().orEmpty()
        if (itemId.isEmpty() || contentType.isEmpty()) return null

        return NativeSearchDetailTarget(
            itemId = itemId,
            itemType = contentType,
            addonBaseUrl = uri.getQueryParameter(PARAM_ADDON_BASE_URL)
                ?.trim()
                ?.takeIf { it.isNotEmpty() }
        )
    }
}

data class NativeSearchDetailTarget(
    val itemId: String,
    val itemType: String,
    val addonBaseUrl: String?
)

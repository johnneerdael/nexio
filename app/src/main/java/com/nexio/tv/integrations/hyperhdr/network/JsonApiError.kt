package com.nexio.tv.integrations.hyperhdr.network

/** Error returned by HyperHdrJsonApiClient when the server reports failure or an HTTP error. */
class JsonApiError(message: String, val httpCode: Int = -1) : RuntimeException(message)

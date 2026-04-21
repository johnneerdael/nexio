package com.nexio.tv.core.integration

sealed interface IntegrationLoadResult<out T> {
    data class Success<T>(val value: T) : IntegrationLoadResult<T>

    data class HttpError(
        val statusCode: Int,
        val retryAfterMs: Long? = null,
        val reason: String? = null
    ) : IntegrationLoadResult<Nothing>

    data class NetworkError(
        val throwable: Throwable,
        val retryAfterMs: Long? = null
    ) : IntegrationLoadResult<Nothing>
}

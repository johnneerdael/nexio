package com.nexio.tv.core.integration

sealed interface IntegrationCallResult<out T> {
    data class Success<T>(val value: T) : IntegrationCallResult<T>

    data class HttpError(
        val statusCode: Int,
        val retryAfterMs: Long? = null,
        val reason: String? = null
    ) : IntegrationCallResult<Nothing>

    data class NetworkError(
        val throwable: Throwable,
        val retryAfterMs: Long? = null
    ) : IntegrationCallResult<Nothing>

    data object Missing : IntegrationCallResult<Nothing>
}

fun <T> IntegrationCallResult<T>.valueOrNull(): T? =
    when (this) {
        is IntegrationCallResult.Success -> value
        IntegrationCallResult.Missing -> null
        is IntegrationCallResult.HttpError -> null
        is IntegrationCallResult.NetworkError -> null
    }

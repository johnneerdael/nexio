package com.nexio.tv.core.integration

sealed interface IntegrationFetchResult<out T> {
    data class Fresh<T>(val value: T) : IntegrationFetchResult<T>
    data class Updated<T>(val value: T) : IntegrationFetchResult<T>
    data class Stale<T>(val value: T) : IntegrationFetchResult<T>
    data object Missing : IntegrationFetchResult<Nothing>
}

fun <T> IntegrationFetchResult<T>.valueOrNull(): T? =
    when (this) {
        is IntegrationFetchResult.Fresh -> value
        is IntegrationFetchResult.Updated -> value
        is IntegrationFetchResult.Stale -> value
        IntegrationFetchResult.Missing -> null
    }

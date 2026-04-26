package com.nexio.tv.core.integration

sealed interface IntegrationCacheOwnership {
    data object None : IntegrationCacheOwnership

    data class Media(val mediaKey: String) : IntegrationCacheOwnership
}

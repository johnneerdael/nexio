package com.nexio.tv.core.integration

interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
    suspend fun deleteOwnedMedia(mediaKey: String): Int
    suspend fun delete(spec: IntegrationSpec<*>): Boolean

    companion object {
        /**
         * No-op fallback used as a constructor default for legacy test sites that
         * predate the cacheStore parameter. Production goes through the Hilt-bound
         * LocalIntegrationCacheStore. New tests should use ByteArrayIntegrationCacheStore
         * (in the test source set) so cache behaviour is actually exercised.
         */
        @Deprecated(
            message = "For legacy test compatibility only. Production uses LocalIntegrationCacheStore via Hilt; " +
                "new tests should use ByteArrayIntegrationCacheStore.",
            level = DeprecationLevel.WARNING
        )
        val Noop: IntegrationCacheStore = object : IntegrationCacheStore {
            override suspend fun <T> readFresh(spec: IntegrationSpec<T>): T? = null
            override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? = null
            override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {}
            override suspend fun deleteOwnedMedia(mediaKey: String): Int = 0
            override suspend fun delete(spec: IntegrationSpec<*>): Boolean = false
        }
    }
}

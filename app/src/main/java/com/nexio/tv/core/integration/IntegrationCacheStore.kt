package com.nexio.tv.core.integration

interface IntegrationCacheStore {
    suspend fun <T> readFresh(spec: IntegrationSpec<T>): T?
    suspend fun <T> readStale(spec: IntegrationSpec<T>): T?
    suspend fun <T> write(spec: IntegrationSpec<T>, value: T)
    suspend fun deleteOwnedMedia(mediaKey: String): Int
    suspend fun delete(spec: IntegrationSpec<*>): Boolean

    companion object {
        val Noop: IntegrationCacheStore = object : IntegrationCacheStore {
            override suspend fun <T> readFresh(spec: IntegrationSpec<T>): T? = null
            override suspend fun <T> readStale(spec: IntegrationSpec<T>): T? = null
            override suspend fun <T> write(spec: IntegrationSpec<T>, value: T) {}
            override suspend fun deleteOwnedMedia(mediaKey: String): Int = 0
            override suspend fun delete(spec: IntegrationSpec<*>): Boolean = false
        }
    }
}

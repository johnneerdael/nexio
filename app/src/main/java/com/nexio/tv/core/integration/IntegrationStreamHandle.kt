package com.nexio.tv.core.integration

interface IntegrationStreamHandle<out T> {
    val value: T

    fun close()
}

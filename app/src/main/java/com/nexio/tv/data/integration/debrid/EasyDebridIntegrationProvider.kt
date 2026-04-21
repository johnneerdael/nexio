package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.EasyDebridApi
import com.nexio.tv.data.repository.EasyDebridService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class EasyDebridIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val easyDebridApi: EasyDebridApi,
    private val easyDebridService: EasyDebridService
)

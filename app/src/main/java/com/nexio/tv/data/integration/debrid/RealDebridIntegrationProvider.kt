package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.RealDebridApi
import com.nexio.tv.data.repository.RealDebridAuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RealDebridIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val realDebridApi: RealDebridApi,
    private val realDebridAuthService: RealDebridAuthService
)

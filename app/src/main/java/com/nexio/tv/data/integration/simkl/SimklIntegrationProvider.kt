package com.nexio.tv.data.integration.simkl

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.SimklApi
import com.nexio.tv.data.repository.SimklAuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SimklIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val simklApi: SimklApi,
    private val simklAuthService: SimklAuthService
)

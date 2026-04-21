package com.nexio.tv.data.integration.kitsu

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.KitsuApi
import com.nexio.tv.data.repository.KitsuAuthService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class KitsuIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val kitsuApi: KitsuApi,
    private val kitsuAuthService: KitsuAuthService
)

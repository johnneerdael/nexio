package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.TorBoxApi
import com.nexio.tv.data.repository.TorBoxService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TorBoxIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val torBoxApi: TorBoxApi,
    private val torBoxService: TorBoxService
)

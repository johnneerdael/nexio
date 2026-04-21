package com.nexio.tv.data.integration.skip

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.AniSkipApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AniSkipIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val aniSkipApi: AniSkipApi
)

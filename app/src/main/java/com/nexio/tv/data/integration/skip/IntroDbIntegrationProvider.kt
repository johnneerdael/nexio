package com.nexio.tv.data.integration.skip

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.IntroDbApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class IntroDbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val introDbApi: IntroDbApi
)

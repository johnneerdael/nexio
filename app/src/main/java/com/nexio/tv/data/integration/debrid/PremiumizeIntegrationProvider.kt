package com.nexio.tv.data.integration.debrid

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.data.remote.api.PremiumizeApi
import com.nexio.tv.data.repository.PremiumizeService
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PremiumizeIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val premiumizeApi: PremiumizeApi,
    private val premiumizeService: PremiumizeService
)

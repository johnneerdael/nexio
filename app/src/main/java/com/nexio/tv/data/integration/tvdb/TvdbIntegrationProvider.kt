package com.nexio.tv.data.integration.tvdb

import com.nexio.tv.core.integration.IntegrationRuntime
import com.nexio.tv.core.tvdb.TvdbAuthService
import com.nexio.tv.data.remote.api.TvdbApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TvdbIntegrationProvider @Inject constructor(
    private val runtime: IntegrationRuntime,
    private val tvdbApi: TvdbApi,
    private val tvdbAuthService: TvdbAuthService
)

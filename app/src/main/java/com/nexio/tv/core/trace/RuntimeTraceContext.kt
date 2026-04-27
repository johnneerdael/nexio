package com.nexio.tv.core.trace

import com.nexio.tv.core.integration.IntegrationProvider
import com.nexio.tv.core.integration.IntegrationScope
import com.nexio.tv.core.integration.IntegrationWorkClass

data class RuntimeTraceContext(
    val traceSessionId: String,
    val runtimeOperationId: String,
    val provider: IntegrationProvider,
    val apiShapeId: String,
    val operationKey: String,
    val cacheKey: String?,
    val workClass: IntegrationWorkClass,
    val scope: IntegrationScope,
    val profileHash: String?,
    val accountProvider: IntegrationProvider?,
    val credentialHash: String?
)

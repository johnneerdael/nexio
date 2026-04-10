package com.nexio.tv.instrumentation

/**
 * Thin allocation-free wrapper around the [TraceRecord.payloadBuffer]
 * `StringBuilder`. Each `put*` call appends a leading `,"k":...` JSON fragment
 * suitable for direct splicing into the common envelope written by
 * [SessionWriter].
 */
@JvmInline
value class PayloadBuilder(private val record: TraceRecord) {

    fun putString(key: String, value: String?) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":")
        if (value == null) {
            sb.append("null")
        } else {
            sb.append('"')
            appendEscaped(sb, value)
            sb.append('"')
        }
    }

    fun putInt(key: String, value: Int) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    fun putLong(key: String, value: Long) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    /**
     * Nullable variant of [putLong]. Emits the JSON `null` literal when
     * [value] is null instead of skipping the field, so downstream parsers
     * always see the key — matching the convention [putString] uses.
     */
    fun putLongOrNull(key: String, value: Long?) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":")
        if (value == null) sb.append("null") else sb.append(value)
    }

    fun putBool(key: String, value: Boolean) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(if (value) "true" else "false")
    }

    fun putDouble(key: String, value: Double) {
        val sb = record.payloadBuffer
        sb.append(",\"").append(key).append("\":").append(value)
    }

    private fun appendEscaped(sb: StringBuilder, value: String) {
        var i = 0
        val n = value.length
        while (i < n) {
            val c = value[i]
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c == '\n' -> sb.append("\\n")
                c == '\r' -> sb.append("\\r")
                c == '\t' -> sb.append("\\t")
                c.code < 0x20 -> appendUnicodeEscape(sb, c.code)
                else -> sb.append(c)
            }
            i++
        }
    }

    private fun appendUnicodeEscape(sb: StringBuilder, codePoint: Int) {
        sb.append("\\u")
        sb.append(HEX_DIGITS[(codePoint ushr 12) and 0xF])
        sb.append(HEX_DIGITS[(codePoint ushr 8) and 0xF])
        sb.append(HEX_DIGITS[(codePoint ushr 4) and 0xF])
        sb.append(HEX_DIGITS[codePoint and 0xF])
    }

    companion object {
        private val HEX_DIGITS = charArrayOf(
            '0', '1', '2', '3', '4', '5', '6', '7',
            '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
        )
    }
}

/**
 * Helper to splice the full session header into the start event payload.
 *
 * Lives next to [PayloadBuilder] (L3 cleanup — was previously a top-level
 * extension at the bottom of `PlaybackTracer.kt`). Uses the JSON key
 * constants from [SessionHeaderKeys] so writer + offline reader stay in
 * lockstep without duplicated string literals (L6 cleanup).
 */
internal fun PayloadBuilder.putHeader(h: SessionHeader) {
    putString(SessionHeaderKeys.SESSION_ID, h.sessionId)
    putLong(SessionHeaderKeys.STARTED_AT_NANOS, h.startedAtNanos)
    putString(SessionHeaderKeys.ASSET_KEY_HASH, h.assetKeyHash)
    putString(SessionHeaderKeys.SERVICE_KEY, h.serviceKey)
    putString(SessionHeaderKeys.PROVIDER, h.provider)
    putString(SessionHeaderKeys.BENCHMARK_RESULT_ID, h.benchmarkResultId)
    putString(SessionHeaderKeys.BENCHMARK_SOURCE, h.benchmarkSource)
    putBool(SessionHeaderKeys.ENVELOPE_PRESENT, h.envelopePresent)
    putBool(SessionHeaderKeys.RUNTIME_HINTS_PRESENT, h.runtimeHintsPresent)
    putString(SessionHeaderKeys.SPECIALIZATION_STATE, h.specializationState)
    putString(SessionHeaderKeys.HINT_SERVICE_KEY, h.hintServiceKey)
    putString(SessionHeaderKeys.HINT_HOST_SCOPE, h.hintHostScope)
    putString(SessionHeaderKeys.HINT_TRANSPORT_CLASS, h.hintTransportClass)
    putLongOrNull(SessionHeaderKeys.HINT_AGE_MS, h.hintAgeMs)
    putString(SessionHeaderKeys.HINT_FRESHNESS_BAND, h.hintFreshnessBand)
    putString(SessionHeaderKeys.SPECIALIZATION_MISMATCH_REASON, h.specializationMismatchReason)
    putString(SessionHeaderKeys.OBSERVED_HOST_SCOPE, h.observedHostScope)
    putString(SessionHeaderKeys.OBSERVED_TRANSPORT_CLASS, h.observedTransportClass)
    putString(SessionHeaderKeys.BRANCH, h.branch)
    putBool(SessionHeaderKeys.CACHE_ACTIVE, h.cacheActive)
    putString(SessionHeaderKeys.WARM_AHEAD_FACTORY, h.warmAheadFactory)
    // FactoryArgs
    putLong(SessionHeaderKeys.ACTIVE_CHUNK_BYTES, h.factoryArgs.activeChunkBytes)
    putInt(SessionHeaderKeys.PARALLEL_CONNECTIONS, h.factoryArgs.parallelConnections)
    putLong(SessionHeaderKeys.KEEP_BEHIND_BYTES, h.factoryArgs.keepBehindBytes)
    putLong(SessionHeaderKeys.BOOTSTRAP_BYTES, h.factoryArgs.bootstrapBytes)
    // PolicySnapshot (prefixed `policy_`)
    putInt(SessionHeaderKeys.POLICY_URGENT_WORKERS, h.initialPolicy.urgentWorkers)
    putInt(SessionHeaderKeys.POLICY_PREFETCH_WORKERS, h.initialPolicy.prefetchWorkers)
    putLong(SessionHeaderKeys.POLICY_URGENT_CHUNK_BYTES, h.initialPolicy.urgentChunkBytes)
    putLong(SessionHeaderKeys.POLICY_PREFETCH_CHUNK_BYTES, h.initialPolicy.prefetchChunkBytes)
    putString(SessionHeaderKeys.POLICY_SOURCE, h.initialPolicy.source)
    // ClientIdentitySnapshot
    putString(SessionHeaderKeys.PLAYBACK_CLIENT_HASH, h.clientIdentity.playbackClientHash)
    putInt(SessionHeaderKeys.DISPATCHER_MAX_REQUESTS, h.clientIdentity.dispatcherMaxRequests)
    putInt(SessionHeaderKeys.DISPATCHER_MAX_REQUESTS_PER_HOST, h.clientIdentity.dispatcherMaxRequestsPerHost)
    putInt(SessionHeaderKeys.DISPATCHER_QUEUED_CALLS, h.clientIdentity.dispatcherQueuedCalls)
    putInt(SessionHeaderKeys.DISPATCHER_RUNNING_CALLS, h.clientIdentity.dispatcherRunningCalls)
    putInt(SessionHeaderKeys.CONNECTION_POOL_IDLE_COUNT, h.clientIdentity.connectionPoolIdleCount)
    putInt(SessionHeaderKeys.CONNECTION_POOL_TOTAL_COUNT, h.clientIdentity.connectionPoolTotalCount)
    putLong(SessionHeaderKeys.CALL_TIMEOUT_MS, h.clientIdentity.callTimeoutMs)
    putLong(SessionHeaderKeys.READ_TIMEOUT_MS, h.clientIdentity.readTimeoutMs)
    putLong(SessionHeaderKeys.WRITE_TIMEOUT_MS, h.clientIdentity.writeTimeoutMs)
    putLong(SessionHeaderKeys.CONNECT_TIMEOUT_MS, h.clientIdentity.connectTimeoutMs)
    // DeviceProvenance
    putString(SessionHeaderKeys.DEVICE_MODEL, h.device.deviceModel)
    putString(SessionHeaderKeys.DEVICE_MANUFACTURER, h.device.deviceManufacturer)
    putString(SessionHeaderKeys.ANDROID_RELEASE, h.device.androidRelease)
    putInt(SessionHeaderKeys.ANDROID_SDK_INT, h.device.androidSdkInt)
    putString(SessionHeaderKeys.APP_VERSION_NAME, h.device.appVersionName)
    putLong(SessionHeaderKeys.APP_VERSION_CODE, h.device.appVersionCode)
    putString(SessionHeaderKeys.GIT_SHA, h.device.gitSha)
    putInt(SessionHeaderKeys.MEMORY_CLASS, h.device.memoryClass)
    putInt(SessionHeaderKeys.LARGE_MEMORY_CLASS, h.device.largeMemoryClass)
    putBool(SessionHeaderKeys.IS_LOW_RAM_DEVICE, h.device.isLowRamDevice)
    putString(SessionHeaderKeys.NETWORK_TYPE, h.device.networkType)
    putString(SessionHeaderKeys.NETWORK_TRANSPORT_HASH, h.device.networkTransportHash)
}

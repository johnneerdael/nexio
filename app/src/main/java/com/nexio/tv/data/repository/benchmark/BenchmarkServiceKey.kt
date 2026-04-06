package com.nexio.tv.data.repository.benchmark

import java.util.Locale

internal fun normalizedBenchmarkServiceKey(
    primaryServiceKey: String?,
    fallbackServiceKey: String? = null
): String? {
    return (primaryServiceKey ?: fallbackServiceKey)
        ?.trim()
        ?.takeIf { it.isNotBlank() }
        ?.uppercase(Locale.US)
}

internal fun benchmarkProviderForServiceKey(serviceKey: String?): DebridBenchmarkProvider? {
    return when (normalizedBenchmarkServiceKey(serviceKey)) {
        "RD" -> DebridBenchmarkProvider.REAL_DEBRID
        "PM" -> DebridBenchmarkProvider.PREMIUMIZE
        "TB" -> DebridBenchmarkProvider.TORBOX
        "ED" -> DebridBenchmarkProvider.EASY_DEBRID
        else -> null
    }
}

internal fun serviceKeyForBenchmarkProvider(provider: DebridBenchmarkProvider): String {
    return when (provider) {
        DebridBenchmarkProvider.REAL_DEBRID -> "RD"
        DebridBenchmarkProvider.PREMIUMIZE -> "PM"
        DebridBenchmarkProvider.TORBOX -> "TB"
        DebridBenchmarkProvider.EASY_DEBRID -> "ED"
    }
}

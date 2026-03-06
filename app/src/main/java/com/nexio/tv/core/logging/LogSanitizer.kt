package com.nexio.tv.core.logging

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val REDACTED_SEGMENT = "<redacted>"

fun sanitizeUrlForLogs(rawUrl: String): String {
    val trimmed = rawUrl.trim()
    if (trimmed.isBlank()) return trimmed

    return runCatching {
        val uri = URI(trimmed)
        val host = uri.host ?: uri.authority ?: return@runCatching sanitizeOpaqueUrl(trimmed)
        val path = uri.rawPath
            ?.split('/')
            ?.filter { it.isNotEmpty() }
            ?.joinToString(separator = "/", prefix = "/") { sanitizePathSegment(it) }
            .orEmpty()
        buildString {
            append(host)
            append(path)
        }
    }.getOrElse {
        sanitizeOpaqueUrl(trimmed)
    }
}

fun sanitizeRequestTargetForLogs(encodedPath: String, encodedQuery: String?): String {
    val sanitizedPath = encodedPath
        .split('/')
        .filter { it.isNotEmpty() }
        .joinToString(separator = "/", prefix = "/") { sanitizePathSegment(it) }
    return sanitizedPath.ifBlank { "/" }
}

private fun sanitizeOpaqueUrl(rawUrl: String): String {
    val withoutQuery = rawUrl.substringBefore('?')
    val segments = withoutQuery.split('/').toMutableList()
    if (segments.size >= 3) {
        for (index in 3 until segments.size) {
            segments[index] = sanitizePathSegment(segments[index])
        }
    }
    return segments.joinToString("/")
}

private fun sanitizePathSegment(segment: String): String {
    if (segment.isBlank()) return segment
    val decoded = runCatching { URLDecoder.decode(segment, StandardCharsets.UTF_8.name()) }
        .getOrDefault(segment)

    return when {
        looksSensitive(decoded) -> REDACTED_SEGMENT
        segment.length > 96 -> REDACTED_SEGMENT
        else -> segment
    }
}

private fun looksSensitive(value: String): Boolean {
    val lowered = value.lowercase()
    if (lowered.contains("access_token") || lowered.contains("refresh_token") || lowered.contains("apikey")) {
        return true
    }
    if (lowered.contains("\"apikey\"") || lowered.contains("\"token\"") || lowered.contains("\"authorization\"")) {
        return true
    }
    if (lowered.startsWith("eyj") && value.length > 24) {
        return true
    }
    if (lowered.contains('{') || lowered.contains('}') || lowered.contains(':')) {
        return true
    }
    return value.length > 48 && value.any { it.isDigit() } && value.any { it.isLetter() }
}

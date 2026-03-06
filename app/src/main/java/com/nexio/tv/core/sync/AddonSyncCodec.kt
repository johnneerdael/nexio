package com.nexio.tv.core.sync

import com.nexio.tv.data.remote.supabase.AccountAddonSecretPayload
import java.net.URL
import java.net.URLEncoder

private val sensitiveQueryKeys = setOf(
    "access_token",
    "api_key",
    "apikey",
    "auth",
    "authorization",
    "debrid_api_key",
    "key",
    "password",
    "premiumize",
    "rd",
    "rd_key",
    "refresh_token",
    "token",
    "user",
    "username"
)

data class ParsedAddonSyncEntry(
    val publicBaseUrl: String,
    val manifestUrl: String,
    val publicQueryParams: Map<String, String>,
    val installKind: String,
    val secretRef: String?,
    val secretPayload: AccountAddonSecretPayload?
)

fun normalizePublicAddonBaseUrl(rawUrl: String): String {
    val parsed = parseAddonInstallUrl(rawUrl)
    return parsed.publicBaseUrl
}

fun addonCatalogDisableKey(addonBaseUrl: String, type: String, catalogId: String, catalogName: String): String {
    return "${normalizePublicAddonBaseUrl(addonBaseUrl)}_${type}_${catalogId}_${catalogName}"
}

fun parseAddonInstallUrl(rawUrl: String): ParsedAddonSyncEntry {
    val candidate = rawUrl.trim()
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
    val pathSegments = parsed.path.split('/').filter { it.isNotBlank() }
    val hasManifestPath = pathSegments.lastOrNull()?.equals("manifest.json", ignoreCase = true) == true
    val pathSecretSegment = if (hasManifestPath) pathSegments.getOrNull(pathSegments.lastIndex - 1) else null
    val hasPathSecret = pathSecretSegment?.let(::looksSensitivePathSegment) == true
    val publicPathSegments = if (hasPathSecret) {
        pathSegments.dropLast(2) + "manifest.json"
    } else {
        pathSegments
    }
    val publicPath = if (publicPathSegments.isEmpty()) "/manifest.json" else "/${publicPathSegments.joinToString("/")}"
    val publicBaseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}/${publicPath.removePrefix("/").removeSuffix("/manifest.json")}".trimEnd('/')

    val publicQueryParams = linkedMapOf<String, String>()
    val secretParams = linkedMapOf<String, String>()
    parsed.query
        ?.split('&')
        ?.mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val pieces = part.split('=', limit = 2)
            val key = pieces[0]
            val value = pieces.getOrElse(1) { "" }
            key to value
        }
        ?.forEach { (key, value) ->
            if (key.trim().lowercase() in sensitiveQueryKeys) {
                secretParams[key] = value
            } else {
                publicQueryParams[key] = value
            }
        }

    val secretRef = if (hasPathSecret || secretParams.isNotEmpty()) addonSecretRef(publicBaseUrl) else null
    val secretPayload = if (secretRef != null) {
        AccountAddonSecretPayload(
            kind = when {
                hasPathSecret && secretParams.isNotEmpty() -> "composite"
                hasPathSecret -> "path_segment"
                else -> "query_params"
            },
            params = secretParams.ifEmpty { emptyMap() },
            pathSegment = pathSecretSegment?.takeIf { hasPathSecret }
        )
    } else {
        null
    }

    return ParsedAddonSyncEntry(
        publicBaseUrl = publicBaseUrl,
        manifestUrl = "$publicBaseUrl/manifest.json",
        publicQueryParams = publicQueryParams,
        installKind = if (secretRef == null) "manifest" else "configured",
        secretRef = secretRef,
        secretPayload = secretPayload
    )
}

fun buildResolvedAddonUrl(
    baseUrl: String,
    manifestUrl: String?,
    publicQueryParams: Map<String, String>,
    secretPayload: AccountAddonSecretPayload?
): String {
    var resolved = manifestUrl?.trim().orEmpty().ifBlank { "${baseUrl.trimEnd('/')}/manifest.json" }
    val pathSegment = secretPayload?.pathSegment?.trim().orEmpty()
    if (pathSegment.isNotBlank() && resolved.endsWith("/manifest.json", ignoreCase = true)) {
        resolved = resolved.removeSuffix("/manifest.json").trimEnd('/') + "/$pathSegment/manifest.json"
    }

    val params = linkedMapOf<String, String>()
    publicQueryParams.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) params[key] = value
    }
    secretPayload?.params?.forEach { (key, value) ->
        if (key.isNotBlank() && value.isNotBlank()) params[key] = value
    }
    if (params.isEmpty()) {
        return resolved
    }

    val query = params.entries.joinToString("&") { (key, value) -> "${key.encodeUrlComponent()}=${value.encodeUrlComponent()}" }
    return "$resolved?$query"
}

private fun addonSecretRef(publicBaseUrl: String): String {
    return "addon:" + publicBaseUrl
        .lowercase()
        .removePrefix("https://")
        .removePrefix("http://")
        .replace(Regex("[^a-z0-9]+"), "_")
        .trim('_')
}

private fun looksSensitivePathSegment(segment: String): Boolean {
    val value = segment.trim()
    if (value.length < 24) return false
    return value.all { it.isLetterOrDigit() || it in "._~+=-" }
}

private fun portSuffix(url: URL): String {
    return when (val port = url.port) {
        -1 -> ""
        80 -> ""
        443 -> ""
        else -> ":$port"
    }
}

private fun String.encodeUrlComponent(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

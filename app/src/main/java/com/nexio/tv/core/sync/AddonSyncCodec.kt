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
    val secretPayload: AccountAddonSecretPayload?,
    val transportBaseUrl: String,
    val transportSecretRef: String,
    val transportSecretPayload: AccountAddonSecretPayload
)

fun normalizePublicAddonBaseUrl(rawUrl: String): String {
    // Lightweight extraction: only the public origin (protocol + host + port).
    // Crucially this MUST tolerate URLs that have already been canonicalized
    // (no `/manifest.json` suffix), since `Addon.baseUrl` flows through
    // `AddonRepositoryImpl.canonicalizeUrl` → `normalizeAddonInstallUrl`,
    // which strips the manifest segment. Routing through
    // `parseAddonInstallUrl` here would re-impose `splitAddonTransportUrl`'s
    // strict `/manifest.json` invariant on those canonicalized URLs and
    // throw IllegalArgumentException — which is exactly the install-time
    // contract, not the lookup-time contract this helper serves.
    val candidate = rawUrl.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    require(candidate.isNotBlank()) { "Addon URL is required." }
    val parsed = URL(candidate)
    return "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}"
}

fun normalizeAddonInstallUrl(rawUrl: String): String {
    val candidate = rawUrl.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
    val pathSegments = parsed.path.split('/').filter { it.isNotBlank() }
    val normalizedSegments = if (pathSegments.lastOrNull()?.equals("manifest.json", ignoreCase = true) == true) {
        pathSegments.dropLast(1)
    } else {
        pathSegments
    }
    val normalizedPath = if (normalizedSegments.isEmpty()) {
        ""
    } else {
        "/" + normalizedSegments.joinToString("/")
    }
    val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}$normalizedPath$querySuffix"
}

fun buildAddonRequestUrl(baseUrl: String, relativePath: String): String {
    val normalizedBaseUrl = normalizeAddonInstallUrl(baseUrl)
    val parsed = URL(normalizedBaseUrl)
    val normalizedRelativePath = relativePath.trim().removePrefix("/")
    val basePath = parsed.path.trimEnd('/')
    val resolvedPath = when {
        normalizedRelativePath.isBlank() && basePath.isBlank() -> "/"
        normalizedRelativePath.isBlank() -> basePath
        basePath.isBlank() -> "/$normalizedRelativePath"
        else -> "$basePath/$normalizedRelativePath"
    }
    val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}$resolvedPath$querySuffix"
}

fun addonCatalogDisableKey(addonBaseUrl: String, type: String, catalogId: String, catalogName: String): String {
    return "${normalizePublicAddonBaseUrl(addonBaseUrl)}_${type}_${catalogId}_${catalogName}"
}

fun addonCatalogKey(addonId: String, type: String, catalogId: String): String {
    return "${addonId}_${type}_${catalogId}"
}

fun isAddonCatalogDisabled(
    disabledKeys: Set<String>,
    addonBaseUrl: String,
    addonId: String,
    type: String,
    catalogId: String,
    catalogName: String
): Boolean {
    if (addonCatalogDisableKey(addonBaseUrl, type, catalogId, catalogName) in disabledKeys) {
        return true
    }
    if (addonCatalogKey(addonId, type, catalogId) in disabledKeys) {
        return true
    }
    val disableKeyPrefix = "${normalizePublicAddonBaseUrl(addonBaseUrl)}_${type}_${catalogId}_"
    return disabledKeys.any { key -> key.startsWith(disableKeyPrefix) }
}

fun parseAddonInstallUrl(rawUrl: String): ParsedAddonSyncEntry {
    val candidate = rawUrl.trim()
        .replaceFirst(Regex("^stremio://", RegexOption.IGNORE_CASE), "https://")
    require(candidate.isNotBlank()) { "Addon URL is required." }

    val parsed = URL(candidate)
    val transport = splitAddonTransportUrl(candidate)
    val publicBaseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}"

    // Public (non-sensitive) query params still travel on the addon record so
    // the UI can replay them when constructing display URLs. Sensitive params
    // are no longer extracted into a legacy secret_payload — for v2 installs
    // the entire path+query suffix lives in transport_secret_payload.suffix.
    val publicQueryParams = linkedMapOf<String, String>()
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
            if (key.trim().lowercase() !in sensitiveQueryKeys) {
                publicQueryParams[key] = value
            }
        }

    val transportSecretRef = addonTransportSecretRef(transport.baseUrl, transport.suffix)
    val transportSecretPayload = AccountAddonSecretPayload(
        kind = "manifest_suffix_v1",
        suffix = transport.suffix
    )
    val installKind = if (transport.suffix == "/manifest.json") "manifest" else "configured"

    return ParsedAddonSyncEntry(
        publicBaseUrl = publicBaseUrl,
        manifestUrl = "$publicBaseUrl/manifest.json",
        publicQueryParams = publicQueryParams,
        installKind = installKind,
        secretRef = null,
        secretPayload = null,
        transportBaseUrl = transport.baseUrl,
        transportSecretRef = transportSecretRef,
        transportSecretPayload = transportSecretPayload
    )
}

fun buildResolvedAddonUrl(
    baseUrl: String,
    manifestUrl: String?,
    publicQueryParams: Map<String, String>,
    secretPayload: AccountAddonSecretPayload?
): String {
    if (secretPayload?.kind == "manifest_suffix_v1") {
        val suffix = secretPayload.suffix?.trim().orEmpty()
        if (suffix.isNotBlank()) {
            return baseUrl.trimEnd('/') + if (suffix.startsWith("/")) suffix else "/$suffix"
        }
    }

    var resolved = manifestUrl
        ?.trim()
        .orEmpty()
        .takeIf(::isUsableManifestUrl)
        .orEmpty()
        .ifBlank { "${baseUrl.trimEnd('/')}/manifest.json" }
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

private fun addonTransportSecretRef(baseUrl: String, suffix: String): String {
    return "${addonSecretRef(baseUrl)}:transport:${shortStableHash(suffix)}"
}

private fun shortStableHash(value: String): String {
    var hash = 0x811c9dc5.toInt()
    value.forEach { char ->
        hash = hash xor char.code
        hash *= 16777619
    }
    return java.lang.Integer.toUnsignedString(hash, 36)
}

private fun splitAddonTransportUrl(rawUrl: String): TransportParts {
    val parsed = URL(rawUrl.trim())
    // Strict invariant (mirrors web post-f9b6edf): every Stremio addon install
    // URL ends with /manifest.json. Auto-appending or auto-defaulting masked
    // invalid install URLs and produced stale transport_secret_payload.suffix
    // values that no longer matched what the user actually pasted.
    val path = parsed.path?.takeIf { it.isNotBlank() && it != "/" }.orEmpty()
    require(path.endsWith("/manifest.json", ignoreCase = true)) {
        "Addon URL must include /manifest.json — paste the install URL exactly as your addon provided it."
    }
    val querySuffix = parsed.query?.takeIf { it.isNotBlank() }?.let { "?$it" }.orEmpty()
    return TransportParts(
        baseUrl = "${parsed.protocol}://${parsed.host}${portSuffix(parsed)}",
        suffix = path + querySuffix
    )
}

private data class TransportParts(
    val baseUrl: String,
    val suffix: String
)

private fun portSuffix(url: URL): String {
    return when (val port = url.port) {
        -1 -> ""
        80 -> ""
        443 -> ""
        else -> ":$port"
    }
}

private fun isUsableManifestUrl(url: String): Boolean {
    val trimmed = url.trim()
    if (trimmed.isBlank()) return false
    if (trimmed.startsWith("http://", ignoreCase = true).not() &&
        trimmed.startsWith("https://", ignoreCase = true).not()
    ) {
        return false
    }

    return runCatching { URL(trimmed) }
        .map { parsed -> !parsed.host.equals("placeholder.nexio.tv", ignoreCase = true) }
        .getOrDefault(false)
}

private fun String.encodeUrlComponent(): String {
    return URLEncoder.encode(this, "UTF-8").replace("+", "%20")
}

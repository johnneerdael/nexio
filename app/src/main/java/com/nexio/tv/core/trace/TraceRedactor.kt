package com.nexio.tv.core.trace

class TraceRedactor {
    private val redactedUrlKeys = setOf(
        "api_key", "apikey", "token", "access_token", "refresh_token",
        "client_secret", "device_code", "user_code", "pin"
    )

    private val redactedHeaders = setOf(
        "authorization", "cookie", "set-cookie",
        "x-api-key", "x-auth-token", "x-mdblist-apikey",
        // F-I-01: provider-specific auth headers
        "simkl-api-key", "trakt-api-key", "simkl-client-id", "x-tvdb-apikey"
    )

    private val redactedJsonKeys = setOf(
        "access_token", "refresh_token", "token", "authorization",
        "apikey", "api_key", "client_secret", "password", "pin",
        "user_code", "email", "username",
        // F-I-01: OAuth POST body keys
        "code", "client_id"
    )

    private val providerCredentialPathHosts = setOf(
        "api.ratingposterdb.com",
        "api.top-posters.com"
    )

    fun redactUrl(url: String): String {
        val pathRedactedUrl = redactProviderCredentialPath(url)
        val q = pathRedactedUrl.indexOf('?')
        if (q < 0) return pathRedactedUrl
        val base = pathRedactedUrl.substring(0, q)
        val query = pathRedactedUrl.substring(q + 1)
        val redactedQuery = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val key = pair.substring(0, eq)
            if (key.lowercase() in redactedUrlKeys) "$key=<redacted>" else pair
        }
        return "$base?$redactedQuery"
    }

    private fun redactProviderCredentialPath(url: String): String {
        val parsed = runCatching { java.net.URI(url) }.getOrNull() ?: return url
        val host = parsed.host?.lowercase() ?: return url
        if (host !in providerCredentialPathHosts) return url

        val rawPath = parsed.rawPath ?: return url
        val segments = rawPath.split("/")
        if (segments.size < 2 || segments[1].isBlank()) return url

        val redactedPath = segments.toMutableList()
            .also { it[1] = "<redacted>" }
            .joinToString("/")
        val scheme = parsed.scheme ?: return url
        val authority = parsed.rawAuthority ?: return url
        val query = parsed.rawQuery?.let { "?$it" }.orEmpty()
        val fragment = parsed.rawFragment?.let { "#$it" }.orEmpty()
        return "$scheme://$authority$redactedPath$query$fragment"
    }

    fun redactHeaders(headers: Map<String, String>): Map<String, String> =
        headers.mapValues { (k, v) ->
            if (k.lowercase() in redactedHeaders) "<redacted>" else v
        }

    fun redactJsonBody(body: String): String {
        var out = body
        redactedJsonKeys.forEach { key ->
            val regex = Regex(""""${Regex.escape(key)}"\s*:\s*"[^"]*"""", RegexOption.IGNORE_CASE)
            out = regex.replace(out, """"$key":"<redacted>"""")
        }
        return out
    }

    /** Exposes the set of URL query-parameter keys that are redacted. */
    fun urlQueryKeys(): Set<String> = redactedUrlKeys

    /** Exposes the set of HTTP header names (lowercase) that are redacted. */
    fun redactedHeaderNames(): Set<String> = redactedHeaders

    /** Exposes the set of JSON body keys that are redacted. */
    fun jsonBodyKeys(): Set<String> = redactedJsonKeys
}

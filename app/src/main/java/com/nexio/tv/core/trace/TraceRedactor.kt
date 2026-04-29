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

    fun redactUrl(url: String): String {
        val q = url.indexOf('?')
        if (q < 0) return url
        val base = url.substring(0, q)
        val query = url.substring(q + 1)
        val redactedQuery = query.split('&').joinToString("&") { pair ->
            val eq = pair.indexOf('=')
            if (eq < 0) return@joinToString pair
            val key = pair.substring(0, eq)
            if (key.lowercase() in redactedUrlKeys) "$key=<redacted>" else pair
        }
        return "$base?$redactedQuery"
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
}

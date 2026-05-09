package com.nexio.tv.core.integration

import com.nexio.tv.core.util.toHexLowercase
import java.security.MessageDigest
import java.nio.charset.StandardCharsets

fun credentialHash(provider: IntegrationProvider, credential: String): String {
    val normalized = "${provider.name.lowercase()}:${credential.trim()}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(StandardCharsets.UTF_8))
    return digest.toHexLowercase().take(16)
}

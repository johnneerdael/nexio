package com.nexio.tv.core.integration

import java.security.MessageDigest
import java.nio.charset.StandardCharsets

fun credentialHash(provider: IntegrationProvider, credential: String): String {
    val normalized = "${provider.name.lowercase()}:${credential.trim()}"
    val digest = MessageDigest.getInstance("SHA-256")
        .digest(normalized.toByteArray(StandardCharsets.UTF_8))
    return digest.joinToString("") { "%02x".format(it) }.take(16)
}

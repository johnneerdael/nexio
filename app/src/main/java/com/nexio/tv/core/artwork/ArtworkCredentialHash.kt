package com.nexio.tv.core.artwork

import com.nexio.tv.core.util.toHexLowercase

object ArtworkCredentialHash {
    fun hashCredential(value: String): String? {
        val trimmed = value.trim()
        if (trimmed.isBlank()) return null
        val bytes = java.security.MessageDigest.getInstance("SHA-256")
            .digest(trimmed.toByteArray(Charsets.UTF_8))
        return bytes.toHexLowercase()
    }
}

package com.nexio.tv.core.integration

object IntegrationKeyFactory {
    fun build(vararg segments: String): String =
        segments.asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .joinToString(":")
}

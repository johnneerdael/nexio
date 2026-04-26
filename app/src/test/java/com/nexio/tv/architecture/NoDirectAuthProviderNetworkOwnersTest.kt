package com.nexio.tv.architecture

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Test

class NoDirectAuthProviderNetworkOwnersTest {
    @Test
    fun `repository auth services must not own raw provider network calls`() {
        val files = listOf(
            File("app/src/main/java/com/nexio/tv/data/repository/RealDebridAuthService.kt"),
            File("app/src/main/java/com/nexio/tv/data/repository/SimklAuthService.kt")
        )

        val offenders = files.flatMap { file ->
            file.readLines().mapIndexedNotNull { index, line ->
                val hasRawProviderCall = line.contains("realDebridApi.") || line.contains("simklApi.")
                if (hasRawProviderCall) "${file.path}:${index + 1}:${line.trim()}" else null
            }
        }

        assertEquals(emptyList<String>(), offenders)
    }
}

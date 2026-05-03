package com.nexio.tv.ui.screens.settings

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WyzieSettingsUxModelTest {

    @Test
    fun `wyzie settings uses shared integration detail rows`() {
        val source = projectRoot()
            .resolve("app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsScreen.kt")
            .readText()

        assertTrue(
            "Wyzie settings must use the same detail header as other integration settings.",
            source.contains("SettingsDetailHeader(")
        )
        assertTrue(
            "Wyzie settings must render rows inside the shared settings group card.",
            source.contains("SettingsGroupCard(")
        )
        assertTrue(
            "Wyzie enabled state must use SettingsToggleRow like OMDB and Subtitle Translation.",
            source.contains("SettingsToggleRow(")
        )
        assertTrue(
            "Wyzie API key entry must use SettingsActionRow like other API-key integrations.",
            source.contains("SettingsActionRow(")
        )
    }

    @Test
    fun `wyzie settings does not use bespoke qr or raw switch menu`() {
        val source = projectRoot()
            .resolve("app/src/main/java/com/nexio/tv/ui/screens/settings/WyzieSubtitleSettingsScreen.kt")
            .readText()

        assertFalse(
            "Wyzie integration settings should not use a bespoke raw Switch.",
            source.contains("Switch(")
        )
        assertFalse(
            "Wyzie integration settings should not expose the old QR-code menu model.",
            source.contains("QrCodeGenerator") || source.contains("WyzieQrCode(")
        )
    }

    private fun projectRoot(): File {
        val cwd = File(System.getProperty("user.dir") ?: ".")
        return listOf(cwd, cwd.parentFile, cwd.resolve("..").canonicalFile)
            .firstOrNull { candidate ->
                candidate.resolve("app/build.gradle.kts").exists() ||
                    candidate.resolve("build.gradle.kts").exists()
            }
            ?: cwd
    }
}

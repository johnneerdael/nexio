package com.nexio.tv.ui

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileSelectionContractTest {
    private val mainActivity = File("app/src/main/java/com/nexio/tv/MainActivity.kt")

    @Test
    fun `startup profile selection waits for active profile write before exiting gate`() {
        val source = mainActivity.readText()
        val callback = source
            .substringAfter("ProfileSelectionScreen(")
            .substringBefore(")\n                        return@Surface")

        assertTrue(callback.contains("profileSelectionScope.launch"))
        assertTrue(callback.indexOf("profileManager.setActiveProfile(profileId)") >= 0)
        assertTrue(callback.indexOf("processProfileSelectionGatePassed = true") >= 0)
        assertTrue(callback.indexOf("hasPassedProfileSelectionGate = true") >= 0)
        assertTrue(
            "The profile gate must exit only after setActiveProfile completes, otherwise the first click/PIN submit renders content under the previous active profile.",
            callback.indexOf("profileManager.setActiveProfile(profileId)") <
                callback.indexOf("hasPassedProfileSelectionGate = true")
        )
    }

    @Test
    fun `sidebar profile switch does not reopen startup profile selection gate`() {
        val source = mainActivity.readText()

        assertTrue(
            "Direct profile switching must not reset the startup gate; otherwise normal sidebar clicks can send users back to profile selection.",
            !source.contains("hasSelectedProfileThisSession = false")
        )
        assertTrue(
            "Direct profile switching should preserve the process-level gate before changing profiles.",
            source.contains("processProfileSelectionGatePassed = true")
        )
        assertTrue(
            "Direct profile switching should preserve the composable gate before changing profiles.",
            source.contains("hasPassedProfileSelectionGate = true")
        )
    }
}

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
        assertTrue(callback.indexOf("hasSelectedProfileThisSession = true") >= 0)
        assertTrue(
            "The profile gate must exit only after setActiveProfile completes, otherwise the first click/PIN submit renders content under the previous active profile.",
            callback.indexOf("profileManager.setActiveProfile(profileId)") <
                callback.indexOf("hasSelectedProfileThisSession = true")
        )
    }
}

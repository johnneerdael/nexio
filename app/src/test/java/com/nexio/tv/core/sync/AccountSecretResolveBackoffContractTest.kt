package com.nexio.tv.core.sync

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AccountSecretResolveBackoffContractTest {
    @Test
    fun `account secret resolve uses same boot missing secret backoff`() {
        val source = File("app/src/main/java/com/nexio/tv/core/sync/AccountSettingsSyncService.kt").readText()

        assertTrue(source.contains("sameBootMissingSecretResolveKeys"))
        assertTrue(source.contains("resolveAccountSecretPayloadOrNull"))
        assertTrue(source.contains("if (sameBootMissingSecretResolveKeys.contains(resolveKey)) return null"))
        assertTrue(source.contains("isSameBootMissingSecretResolveFailure"))
    }
}

package com.nexio.tv.core.auth

import com.nexio.tv.data.local.DurableDeviceCredentialSnapshot
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DurableDeviceAuthRecoveryPolicyTest {
    @Test
    fun `durable recovery only runs for returning users with no live refresh token and complete credential`() {
        assertTrue(
            shouldAttemptDurableSessionRecovery(
                isReturningUser = true,
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `durable recovery stays disabled for fresh installs`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                isReturningUser = false,
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `durable recovery stays disabled when refresh token already exists`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                isReturningUser = true,
                hasRefreshToken = true,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = "device-secret"
                )
            )
        )
    }

    @Test
    fun `durable recovery stays disabled when credential is incomplete`() {
        assertFalse(
            shouldAttemptDurableSessionRecovery(
                isReturningUser = true,
                hasRefreshToken = false,
                credential = DurableDeviceCredentialSnapshot(
                    devicePublicId = "device-public-id",
                    deviceSecret = ""
                )
            )
        )
    }
}

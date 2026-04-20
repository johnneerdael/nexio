package com.nexio.tv.data.repository

import com.nexio.tv.data.local.KitsuAuthStore
import com.nexio.tv.data.remote.api.KitsuAuthApi
import com.nexio.tv.data.remote.api.KitsuTokenRequest
import com.nexio.tv.data.remote.api.KitsuTokenResponse
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import retrofit2.Response

class KitsuAuthServiceTest {
    @Test
    fun `login exchanges username and password for tokens without persisting password`() = runTest {
        val api = mockk<KitsuAuthApi>()
        val store = FakeKitsuAuthStore()
        val service = KitsuAuthService(api = api, authStore = store, nowEpochSeconds = { 1000L })

        coEvery {
            api.token(
                KitsuTokenRequest(
                    grantType = "password",
                    username = "user@example.com",
                    password = "p@ss word",
                    refreshToken = null
                )
            )
        } returns Response.success(
            KitsuTokenResponse(
                accessToken = "access",
                refreshToken = "refresh",
                expiresIn = 2_591_963,
                createdAt = 1000,
                tokenType = "bearer",
                scope = "public"
            )
        )

        val result = service.login(username = "user@example.com", password = "p@ss word")

        assertEquals(true, result)
        assertEquals("access", store.snapshot.accessToken)
        assertEquals("refresh", store.snapshot.refreshToken)
        assertEquals("user@example.com", store.snapshot.username)
        assertEquals(true, store.snapshot.enabled)
        assertNull(store.snapshot.password)
    }

    @Test
    fun `refresh uses refresh token and updates access token`() = runTest {
        val api = mockk<KitsuAuthApi>()
        val store = FakeKitsuAuthStore(
            KitsuAuthSnapshot(
                username = "user",
                accessToken = "old-access",
                refreshToken = "old-refresh",
                expiresAtEpochSeconds = 999L
            )
        )
        val service = KitsuAuthService(api = api, authStore = store, nowEpochSeconds = { 1000L })

        coEvery {
            api.token(
                KitsuTokenRequest(
                    grantType = "refresh_token",
                    username = null,
                    password = null,
                    refreshToken = "old-refresh"
                )
            )
        } returns Response.success(
            KitsuTokenResponse(accessToken = "new-access", refreshToken = "new-refresh", expiresIn = 3600, createdAt = 1000)
        )

        assertEquals("new-access", service.validAccessToken())
        assertEquals("new-refresh", store.snapshot.refreshToken)
    }

    @Test
    fun `disconnect clears tokens`() = runTest {
        val store = FakeKitsuAuthStore(KitsuAuthSnapshot(username = "user", accessToken = "access", refreshToken = "refresh"))
        val service = KitsuAuthService(api = mockk(relaxed = true), authStore = store)

        service.disconnect()

        assertNull(store.snapshot.accessToken)
        assertNull(store.snapshot.refreshToken)
    }
}

private class FakeKitsuAuthStore(
    initial: KitsuAuthSnapshot = KitsuAuthSnapshot()
) : KitsuAuthStore {
    private val stateFlow = MutableStateFlow(initial)
    var snapshot: KitsuAuthSnapshot = initial
        private set

    override val state: Flow<KitsuAuthSnapshot> = stateFlow

    override suspend fun save(snapshot: KitsuAuthSnapshot) {
        this.snapshot = snapshot
        stateFlow.value = snapshot
    }

    override suspend fun clear() {
        save(KitsuAuthSnapshot(username = snapshot.username))
    }
}

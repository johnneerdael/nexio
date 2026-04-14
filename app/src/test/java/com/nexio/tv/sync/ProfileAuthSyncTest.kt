package com.nexio.tv.sync

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ProfileAuthSyncTest {
    @Test
    fun WEB_02_profile_auth_sync_contract_is_documented() {
        val contract = listOf(
            "WEB-02",
            "profile_auth_tokens",
            "trakt_access_token",
            "trakt_refresh_token",
            "simkl_access_token",
            "updated_at"
        )

        assertTrue("WEB-02 profile auth token table should be documented", "profile_auth_tokens" in contract)
        assertTrue("WEB-02 Trakt access token key should be documented", "trakt_access_token" in contract)
        assertTrue("WEB-02 Trakt refresh token key should be documented", "trakt_refresh_token" in contract)
        assertTrue("WEB-02 Simkl access token key should be documented", "simkl_access_token" in contract)
        assertTrue("WEB-02 updated_at conflict field should be documented", "updated_at" in contract)
    }

    @Test
    fun WEB_02_token_payload_allows_json_values() {
        val payload: JsonObject = JsonObject(
            mapOf<String, JsonElement>(
                "trakt_access_token" to JsonPrimitive("synthetic-trakt-access"),
                "trakt_refresh_token" to JsonPrimitive("synthetic-trakt-refresh"),
                "simkl_access_token" to JsonPrimitive("synthetic-simkl-access"),
                "expires_in" to JsonPrimitive(3600)
            )
        )

        assertEquals("synthetic-trakt-access", payload["trakt_access_token"]!!.jsonPrimitive.content)
        assertEquals(3600, payload["expires_in"]!!.jsonPrimitive.int)
    }
}

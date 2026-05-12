package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Round-trip parsing of the JSON envelopes the v10 server RPCs produce.
 * Anchors the on-the-wire shape against the SQL definitions in
 * supabase/migrations/2026051200* — if a field rename happens server-side
 * and the migration is not bumped here, this test fails.
 */
class V10ContractModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `account snapshot envelope decodes from server-shape JSON`() {
        val raw = """
            {
              "contract_version": 10,
              "settings": {
                "payload": { "integrations": { "tmdb": { "enabled": true } } },
                "sync_revision": 17,
                "updated_at_ms": 1747000000000
              },
              "addons": {
                "items": [
                  {
                    "url": "https://v3-cinemeta.strem.io",
                    "manifest_url": "https://v3-cinemeta.strem.io/manifest.json",
                    "parser_preset": "GENERIC",
                    "is_anime": false,
                    "enabled": true,
                    "sort_order": 0
                  }
                ],
                "updated_at_ms": 1746999000000
              },
              "secrets": {
                "items": [
                  {
                    "secret_type": "tmdb_api_key",
                    "secret_ref": "integration:tmdb",
                    "masked_preview": "Stored ****abcd",
                    "status": "configured",
                    "updated_at_ms": 1746998000000
                  }
                ],
                "updated_at_ms": 1746998000000
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(V10AccountSnapshotEnvelope.serializer(), raw)

        assertEquals(10, envelope.contractVersion)
        assertEquals(17L, envelope.settings.syncRevision)
        assertEquals(1747000000000L, envelope.settings.updatedAtMs)
        assertEquals(1746999000000L, envelope.addons.updatedAtMs)
        assertEquals(1, envelope.addons.items.size)
        assertEquals("https://v3-cinemeta.strem.io", envelope.addons.items[0].url)
        assertEquals(1746998000000L, envelope.secrets.updatedAtMs)
        assertEquals(1, envelope.secrets.items.size)
        assertEquals("tmdb_api_key", envelope.secrets.items[0].secretType)
    }

    @Test
    fun `account settings payload preserves subtitle translation model when web sends newer fields`() {
        val raw = """
            {
              "contract_version": 10,
              "settings": {
                "payload": {
                  "schemaVersion": 10,
                  "integrations": {
                    "subtitleTranslation": {
                      "enabled": true,
                      "provider": "OPENAI",
                      "model": "openai/gpt-5.5",
                      "baseUrl": "https://openrouter.ai/api/v1"
                    },
                    "traktAuth": {
                      "connected": true,
                      "connectedAt": "2026-05-12T10:00:00Z"
                    }
                  },
                  "playback": {
                    "streamSelection": {
                      "trackingProvider": "SIMKL"
                    },
                    "general": {
                      "loadingOverlayEnabled": true
                    }
                  },
                  "localOnlyWebPanel": {
                    "expanded": true
                  }
                },
                "sync_revision": 18,
                "updated_at_ms": 1747000001000
              },
              "addons": {
                "items": [],
                "updated_at_ms": 1746999000000
              },
              "secrets": {
                "items": [],
                "updated_at_ms": 1746998000000
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(V10AccountSnapshotEnvelope.serializer(), raw)
        val payload = AccountConfigSyncPayloadJson.decodeFromJsonElement(
            AccountConfigSyncPayload.serializer(),
            envelope.settings.payload
        )

        assertEquals("openai/gpt-5.5", payload.integrations.subtitleTranslation.model)
        assertEquals("SIMKL", payload.playback.streamSelection.trackingProvider)
    }

    @Test
    fun `profile settings envelope decodes from server-shape JSON`() {
        val raw = """
            {
              "contract_version": 10,
              "settings_json": { "trakt": { "enabled": true } },
              "sync_revision": 3,
              "updated_at_ms": 1747100000000
            }
        """.trimIndent()

        val envelope = json.decodeFromString(V10ProfileSettingsEnvelope.serializer(), raw)

        assertEquals(10, envelope.contractVersion)
        assertEquals(3L, envelope.syncRevision)
        assertEquals(1747100000000L, envelope.updatedAtMs)
    }

    @Test
    fun `push result with stale_base decodes correctly`() {
        val raw = """{"applied":false,"reason":"stale_base","current_updated_at_ms":1747000000000}"""
        val result = json.decodeFromString(V10PushResult.serializer(), raw)
        assertEquals(false, result.applied)
        assertEquals("stale_base", result.reason)
        assertEquals(1747000000000L, result.currentUpdatedAtMs)
    }

    @Test
    fun `push result with field_conflict decodes correctly`() {
        val raw = """
            {"applied":false,"reason":"field_conflict","conflict_paths":["integrations.tmdb"],"sync_revision":99,"current_updated_at_ms":1747000000000}
        """.trimIndent()
        val result = json.decodeFromString(V10PushResult.serializer(), raw)
        assertEquals(false, result.applied)
        assertEquals("field_conflict", result.reason)
        assertEquals(listOf("integrations.tmdb"), result.conflictPaths)
        assertEquals(99L, result.syncRevision)
    }

    @Test
    fun `push result applied with revision decodes correctly`() {
        val raw = """{"applied":true,"current_updated_at_ms":1747200000000,"sync_revision":42}"""
        val result = json.decodeFromString(V10PushResult.serializer(), raw)
        assertEquals(true, result.applied)
        assertEquals(1747200000000L, result.currentUpdatedAtMs)
        assertEquals(42L, result.syncRevision)
    }
}

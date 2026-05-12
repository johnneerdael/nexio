package com.nexio.tv.data.remote.supabase

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

class V13ContractModelsTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `account snapshot envelope decodes sectioned settings and reuses addon and secret sections`() {
        val raw = """
            {
              "contract_version": 13,
              "settings": {
                "sections": [
                  {
                    "section_key": "integrations.tmdb",
                    "payload": { "enabled": true, "region": "US" },
                    "schema_version": 1,
                    "sync_revision": 17,
                    "updated_at_ms": 1747000000000
                  },
                  {
                    "section_key": "playback.streamSelection",
                    "payload": { "trackingProvider": "SIMKL" },
                    "schema_version": 1,
                    "sync_revision": 18,
                    "updated_at_ms": 1747000001000
                  },
                  {
                    "section_key": "integrations.futureProvider",
                    "payload": { "enabled": true, "futureField": "kept" },
                    "schema_version": 2,
                    "sync_revision": 19,
                    "updated_at_ms": 1747000002000
                  }
                ],
                "updated_at_ms": 1747000002000
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
                    "secret_type": "subtitle_translation_api_key",
                    "secret_ref": "integration:subtitleTranslation",
                    "masked_preview": "Stored ****abcd",
                    "status": "configured",
                    "updated_at_ms": 1746998000000
                  }
                ],
                "updated_at_ms": 1746998000000
              }
            }
        """.trimIndent()

        val envelope = json.decodeFromString(V13AccountSnapshotEnvelope.serializer(), raw)

        assertEquals(13, envelope.contractVersion)
        assertEquals(1747000002000L, envelope.settings.updatedAtMs)
        assertEquals(3, envelope.settings.sections.size)
        assertEquals("integrations.tmdb", envelope.settings.sections[0].sectionKey)
        assertEquals(17L, envelope.settings.sections[0].syncRevision)
        assertEquals(1, envelope.settings.sections[0].schemaVersion)
        assertEquals(setOf("enabled", "region"), envelope.settings.sections[0].payload.jsonObject.keys)
        assertEquals("integrations.futureProvider", envelope.settings.sections[2].sectionKey)
        assertEquals(setOf("enabled", "futureField"), envelope.settings.sections[2].payload.jsonObject.keys)
        assertEquals(1746999000000L, envelope.addons.updatedAtMs)
        assertEquals("https://v3-cinemeta.strem.io", envelope.addons.items[0].url)
        assertEquals(1746998000000L, envelope.secrets.updatedAtMs)
        assertEquals("subtitle_translation_api_key", envelope.secrets.items[0].secretType)
    }

    @Test
    fun `unknown setting sections decode without being dropped`() {
        val raw = """
            {
              "sections": [
                {
                  "section_key": "integrations.futureProvider",
                  "payload": { "enabled": true },
                  "schema_version": 2,
                  "sync_revision": 99,
                  "updated_at_ms": 1747000002000
                }
              ],
              "updated_at_ms": 1747000002000
            }
        """.trimIndent()

        val settings = json.decodeFromString(V13AccountSettingsSections.serializer(), raw)

        assertEquals(1, settings.sections.size)
        assertEquals("integrations.futureProvider", settings.sections[0].sectionKey)
        assertEquals(2, settings.sections[0].schemaVersion)
    }

    @Test
    fun `section push result decodes stale base result`() {
        val raw = """
            {
              "applied": false,
              "section_key": "integrations.tmdb",
              "reason": "stale_base",
              "current_updated_at_ms": 1747000000000
            }
        """.trimIndent()

        val result = json.decodeFromString(V13SectionPushResult.serializer(), raw)

        assertEquals(false, result.applied)
        assertEquals("integrations.tmdb", result.sectionKey)
        assertEquals("stale_base", result.reason)
        assertEquals(1747000000000L, result.currentUpdatedAtMs)
    }

    @Test
    fun `batch push result decodes per section outcomes`() {
        val raw = """
            {
              "applied": false,
              "sections": [
                {
                  "applied": true,
                  "section_key": "catalogs.home",
                  "sync_revision": 21,
                  "current_updated_at_ms": 1747000003000
                },
                {
                  "applied": false,
                  "section_key": "integrations.tvdb",
                  "reason": "unsupported_section"
                }
              ]
            }
        """.trimIndent()

        val result = json.decodeFromString(V13BatchPushResult.serializer(), raw)

        assertEquals(false, result.applied)
        assertEquals(2, result.sections.size)
        assertEquals(true, result.sections[0].applied)
        assertEquals(21L, result.sections[0].syncRevision)
        assertEquals("unsupported_section", result.sections[1].reason)
    }
}

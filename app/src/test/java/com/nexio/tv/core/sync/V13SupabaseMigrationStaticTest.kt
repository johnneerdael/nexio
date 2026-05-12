package com.nexio.tv.core.sync

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class V13SupabaseMigrationStaticTest {
    private val migration = File("supabase/migrations/20260512070000_contract_v13_sectioned_account_settings.sql")
    private val syncSql = File("supabase/account_settings_sync.sql")
    private val v13Marker = "-- Contract v13: sectioned account settings."
    private val expectedSections = setOf(
        "integrations.subtitleTranslation",
        "integrations.imdb",
        "integrations.gemini",
        "integrations.tmdb",
        "integrations.omdb",
        "integrations.posterRatings",
        "integrations.animeSkip",
        "integrations.mdblist",
        "integrations.kitsu",
        "integrations.traktAuth",
        "integrations.simklAuth",
        "integrations.kitsuAuth",
        "integrations.debrid.premiumize",
        "integrations.debrid.realDebrid",
        "integrations.debrid.torBox",
        "integrations.debrid.easyDebrid",
        "catalogs.mdblist",
        "catalogs.trakt",
        "catalogs.simkl",
        "catalogs.tmdb",
        "catalogs.kitsu",
        "catalogs.home",
        "playback.streamSelection",
        "formatter",
    )

    @Test
    fun `v13 migration creates section table helpers and backfill without removed surfaces`() {
        assertTrue("v13 migration must exist", migration.exists())
        assertTrue("account settings sync sql must exist", syncSql.exists())
        val sql = migration.readText()
        val sync = syncSql.readText()

        assertTrue(sql.contains("create table if not exists public.account_settings_sections"))
        assertTrue(sql.contains("create or replace function public.account_settings_section_key_allowed"))
        assertTrue(sql.contains("create or replace function public.account_settings_section_payload"))
        assertTrue(sql.contains("create or replace function public.account_settings_sections_to_payload"))
        assertTrue(sql.contains("insert into public.account_settings_sections"))
        assertTrue(sql.contains("on conflict (user_id, section_key) do update"))
        assertTrue(sql.contains("""v_payload jsonb := '{"integrations":{},"catalogs":{},"playback":{}}'::jsonb;"""))
        assertTrue(sql.contains("jsonb_set(v_payload, '{integrations,debrid}'"))
        assertTrue(sync.contains("""v_payload jsonb := '{"integrations":{},"catalogs":{},"playback":{}}'::jsonb;"""))
        assertTrue(sync.contains("jsonb_set(v_payload, '{integrations,debrid}'"))

        assertEquals(expectedSections, sectionKeysIn(sql))
        assertEquals(expectedSections, sectionKeysIn(allowlistBlock(sql)))
        assertEquals(expectedSections, sectionKeysIn(backfillBlock(sql)))
        expectedSections.forEach { section ->
            assertTrue("$section must be extracted from full settings payload", sql.contains("when '$section' then p_settings"))
            assertTrue("$section must be merged back into full settings payload", sql.contains("when '$section' then jsonb_set"))
        }
        assertTrue("padded section keys must not pass the table check", sql.contains("coalesce(p_section_key = trim(p_section_key), false)"))

        assertEquals(
            "canonical account_settings_sync.sql v13 block must match migration",
            sql.trimEnd(),
            sync.substring(sync.indexOf(v13Marker)).trimEnd(),
        )

        assertFalse(sql.contains("'integrations.wyzie'"))
        assertFalse(sql.contains("'integrations.theIntroDb'"))
        assertFalse(sql.contains("'integrations.tvdb'"))
        assertFalse(sql.contains("wyzie_api_key"))
        assertFalse(sql.contains("tmdb_api_key"))
        assertFalse(sql.contains("tvdb_api_key"))
    }

    @Test
    fun `v13 migration has section scoped stale base and batch outcomes`() {
        val sql = migration.readText()
        val singlePush = functionBlock(sql, "sync_push_account_settings_section_v13")
        val batchPush = functionBlock(sql, "sync_push_account_settings_sections_v13")
        val snapshot = functionBlock(sql, "sync_pull_account_snapshot_v13")

        assertTrue(sql.contains("p_base_updated_at_ms"))
        assertTrue(sql.contains("current section updated_at"))
        assertTrue(sql.contains("'stale_base'"))
        assertTrue(sql.contains("'sections'"))
        assertTrue(sql.contains("jsonb_agg"))
        assertTrue("v13 snapshot must preserve v12 addon url envelope", snapshot.contains("'url', a.base_url"))
        assertFalse("v13 snapshot must not expose raw addon rows", snapshot.contains("row_to_json(a)"))
        assertTrue("direct section push must validate exact raw section key", singlePush.contains("v_key text := coalesce(p_section_key, '')"))
        assertFalse("direct section push must not trim before validation", singlePush.contains("v_key text := trim(coalesce(p_section_key, ''))"))
        assertTrue(
            "direct section push must serialize stale-base check and write per user/section",
            singlePush.contains("pg_advisory_xact_lock(hashtextextended(v_user_id::text || ':' || v_key, 0))"),
        )
        assertTrue(
            "direct section push must lock before reading the current section timestamp",
            singlePush.indexOf("pg_advisory_xact_lock") in 0 until singlePush.indexOf("current section updated_at"),
        )
        assertTrue(
            "accepted section pushes must advance the visible millisecond watermark",
            singlePush.contains("v_updated_at := greatest(now(), to_timestamp((v_current_ms + 1)::double precision / 1000.0))"),
        )
        assertTrue(
            "direct section push must compute the monotonic timestamp after stale-base acceptance",
            singlePush.indexOf("v_updated_at :=") in singlePush.indexOf("v_revision := public.next_sync_revision()") until singlePush.indexOf("insert into public.account_settings_sections"),
        )
        assertFalse("direct section push must not store raw now() for accepted writes", singlePush.contains("v_updated_at := now()"))
        assertTrue("batch push must report unsupported sections per entry", batchPush.contains("'unsupported_section'"))
        assertTrue("batch push must report invalid payloads per entry", batchPush.contains("'invalid_payload'"))
        assertTrue("batch push must reject missing payloads", batchPush.contains("not (v_item ? 'payload')"))
        assertTrue("batch push must reject JSON null payloads", batchPush.contains("v_item->'payload' = 'null'::jsonb"))
        assertTrue("batch push must report invalid base timestamps per entry", batchPush.contains("'invalid_base_updated_at_ms'"))
        assertTrue("batch push must validate base timestamps before casting", batchPush.contains("v_base_updated_at_ms_text ~ '^[0-9]+$'"))
        assertTrue("batch push must reject base timestamps that overflow bigint", batchPush.contains("v_base_updated_at_ms_text > '9223372036854775807'"))
        assertTrue("batch push must validate entries before direct RPC call", batchPush.indexOf("account_settings_section_key_allowed") in 0 until batchPush.indexOf("sync_push_account_settings_section_v13"))
        assertTrue("batch push must validate payload before direct RPC call", batchPush.indexOf("'invalid_payload'") in 0 until batchPush.indexOf("sync_push_account_settings_section_v13"))
        assertTrue("batch push must validate base timestamp before direct RPC call", batchPush.indexOf("'invalid_base_updated_at_ms'") in 0 until batchPush.indexOf("sync_push_account_settings_section_v13"))
    }

    @Test
    fun `legacy account settings RPCs are adapters over sections`() {
        val sql = migration.readText()
        val pull = functionBlock(sql, "sync_pull_account_snapshot_v10")
        val push = functionBlock(sql, "sync_push_account_settings_v10")
        val pushSections = legacyPushSectionMappingBlock(push)

        assertTrue("legacy pull must rebuild settings from sections", pull.contains("account_settings_sections_to_payload(v_user_id)"))
        assertTrue("legacy pull must count section rows before falling back", pull.contains("v_settings_section_count"))
        assertTrue("legacy pull must count section rows before falling back", pull.contains("count(*)"))
        assertTrue(
            "legacy pull fallback must distinguish no section rows from an empty object payload",
            pull.contains("coalesce(v_settings_section_count, 0) = 0"),
        )
        assertTrue("legacy pull must fallback to public full-payload settings", pull.contains("from public.account_settings_public"))
        assertTrue("legacy pull must report current legacy envelope contract", pull.contains("'contract_version', 12"))
        assertTrue("legacy pull must preserve addon base URL envelope marker", pull.contains("'url', a.base_url"))
        assertTrue("legacy pull must preserve addon manifest URL envelope marker", pull.contains("'manifest_url'"))
        assertTrue("legacy pull must preserve addon transport schema envelope marker", pull.contains("'transport_schema_version'"))
        assertFalse("legacy pull must not expose raw addon rows", pull.contains("row_to_json(a)"))

        assertTrue(
            "legacy push must preserve the v10 signature",
            push.contains(
                """
                create or replace function public.sync_push_account_settings_v10(
                  p_base_updated_at_ms bigint,
                  p_settings_payload jsonb,
                  p_base_revision bigint,
                  p_changed_paths text[],
                  p_source text default 'app'
                )
                """.trimIndent(),
            ),
        )
        assertTrue("legacy pull must use canonical sync owner identity", pull.contains("v_user_id uuid := public.sync_owner_id()"))
        assertTrue("legacy push must use canonical sync owner identity", push.contains("v_user_id uuid := public.sync_owner_id()"))
        assertFalse("legacy push must not delegate to non-atomic v13 section batch push", push.contains("sync_push_account_settings_sections_v13"))
        assertEquals(expectedSections, sectionKeysIn(pushSections))
        assertTrue("legacy push must map current IMDb integration section", pushSections.contains("'integrations.imdb'"))
        assertTrue("legacy push must map current Gemini integration section", pushSections.contains("'integrations.gemini'"))
        assertFalse("legacy push mapping must not include removed Wyzie integration", pushSections.contains("'integrations.wyzie'"))
        assertFalse("legacy push mapping must not include removed TheIntroDb integration", pushSections.contains("'integrations.theIntroDb'"))
        assertFalse("legacy push mapping must not include removed TVDB integration", pushSections.contains("'integrations.tvdb'"))
        assertFalse("legacy push mapping must not include removed Wyzie API key surface", pushSections.contains("wyzie_api_key"))
        assertFalse("legacy push mapping must not include removed TMDB API key surface", pushSections.contains("tmdb_api_key"))
        assertFalse("legacy push mapping must not include removed TVDB API key surface", pushSections.contains("tvdb_api_key"))
        assertTrue("legacy push must preflight aggregate stale-base timestamps", push.contains("legacy aggregate stale-base guard"))
        assertTrue(
            "legacy push must reject stale aggregate timestamps before writes",
            push.indexOf("legacy aggregate stale-base guard") in 0 until push.indexOf("insert into public.account_settings_sections"),
        )
        assertTrue(
            "legacy push must reject stale aggregate revisions before writes",
            push.contains("coalesce(p_base_revision, 0) < v_current_revision"),
        )
        assertTrue(
            "legacy push must validate selected section payloads before writes",
            push.indexOf("legacy section payload preflight") in 0 until push.indexOf("insert into public.account_settings_sections"),
        )
        assertTrue(
            "legacy push must write sections directly after preflight for atomic legacy behavior",
            push.contains("insert into public.account_settings_sections"),
        )
        assertTrue("legacy push must preserve stale-base compatibility responses", push.contains("'reason', 'stale_base'"))
        assertTrue("legacy push must normalize section validation failures as field conflicts", push.contains("'reason', 'field_conflict'"))
        assertTrue("legacy push must preserve raw section failure detail separately", push.contains("'section_failure_reason', 'invalid_payload'"))
        assertFalse("legacy push must not expose raw section-only failure reasons", push.contains("coalesce(v_failure_reason, 'section_push_failed')"))
        assertTrue("legacy push must no-op successfully when no sections are affected", push.contains("if v_sections = '[]'::jsonb then"))
        assertTrue("legacy push no-op must report applied success", push.contains("'applied', true"))
    }

    @Test
    fun `legacy push mapping extraction fails when marker is missing`() {
        try {
            legacyPushSectionMappingBlock("create or replace function public.sync_push_account_settings_v10()")
        } catch (expected: AssertionError) {
            assertTrue(expected.message.orEmpty().contains("legacy section mapping start marker must exist"))
            return
        }

        throw AssertionError("missing legacy section mapping marker must fail explicitly")
    }

    private fun sectionKeysIn(sql: String): Set<String> =
        Regex("'((?:integrations|catalogs|playback)\\.[A-Za-z0-9.]+|formatter)'")
            .findAll(sql)
            .map { it.groupValues[1] }
            .toSet()

    private fun allowlistBlock(sql: String): String =
        sql.substringAfter("and p_section_key = any (array[")
            .substringBefore("]);")

    private fun backfillBlock(sql: String): String =
        sql.substringAfter("with section_keys(section_key) as (")
            .substringBefore(")\ninsert into public.account_settings_sections")

    private fun legacyPushSectionMappingBlock(sql: String): String {
        val startMarker = "section_keys(section_key) as ("
        val endMarker = "  )\n  select coalesce(jsonb_agg"
        val start = sql.indexOf(startMarker)
        assertTrue("legacy section mapping start marker must exist", start >= 0)
        val end = sql.indexOf(endMarker, start + startMarker.length)
        assertTrue("legacy section mapping end marker must exist", end > start)
        return sql.substring(start + startMarker.length, end)
    }

    private fun functionBlock(sql: String, functionName: String): String {
        val startMarker = "create or replace function public.$functionName"
        val start = sql.indexOf(startMarker)
        assertTrue("$functionName start marker must exist", start >= 0)

        val end = sql.indexOf("\n$$;", start)
        assertTrue("$functionName end marker must exist", end > start)

        val nextFunction = sql.indexOf("create or replace function public.", start + startMarker.length)
        assertTrue("$functionName block must end before the next function", nextFunction == -1 || end < nextFunction)

        return sql.substring(start, end)
    }
}

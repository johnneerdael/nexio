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
}

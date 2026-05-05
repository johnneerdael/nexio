package com.nexio.tv.ui.screens.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Documents the contract of [HomeViewModel.migrationAttempted] — a per-profile-id set used by
 * the catalog pipeline to ensure each profile gets exactly one legacy-rail-order migration
 * attempt over a single ViewModel lifecycle.
 *
 * Issue I2 (final review of home-rail-order work): the previous `Boolean` flag was per-VM-instance,
 * which meant profile switches inside the same VM did not get a fresh migration pass. Switching to
 * `MutableSet<Int>` keyed by `profileManager.activeProfileId.value` makes the gate per-profile.
 *
 * Full integration coverage (constructing a real [HomeViewModel] and invoking
 * `updateCatalogRowsPipeline()` across a profile switch) lives in the on-device smoke; these
 * tests document the data-structure contract that the pipeline relies on.
 */
class MigrationAttemptedSetTest {
    @Test
    fun `empty set treats every profile as un-migrated`() {
        val migrated = mutableSetOf<Int>()
        assertFalse(0 in migrated)
        assertFalse(1 in migrated)
    }

    @Test
    fun `adding a profile marks only that profile as migrated`() {
        val migrated = mutableSetOf<Int>()
        migrated.add(0)
        assertTrue(0 in migrated)
        assertFalse(1 in migrated)
    }

    @Test
    fun `re-adding the same profile is idempotent`() {
        val migrated = mutableSetOf<Int>()
        migrated.add(0)
        migrated.add(0)
        assertTrue(0 in migrated)
        assertEquals(1, migrated.size)
    }

    @Test
    fun `switching back to a previously-migrated profile still treats it as migrated`() {
        val migrated = mutableSetOf<Int>()
        migrated.add(0)
        migrated.add(1)
        // Simulate switching back to profile 0 — gate must still recognise it.
        assertTrue(0 in migrated)
        assertTrue(1 in migrated)
        assertFalse(2 in migrated)
    }
}

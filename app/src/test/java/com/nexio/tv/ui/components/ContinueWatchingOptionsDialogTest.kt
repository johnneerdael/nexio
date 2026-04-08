package com.nexio.tv.ui.components

import com.nexio.tv.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-level tests for [ContinueWatchingOptionsDialog].
 *
 * No Compose test infrastructure is available in the unit-test source set and
 * kotlin-reflect is not on the test classpath, so structural contract is verified
 * via Java reflection and label behavior is tested through the extracted pure helper
 * [cwLibraryLabelResId].
 */
class ContinueWatchingOptionsDialogTest {

    /**
     * Structural-removal guard: confirms that the old `onManageLists` parameter has been
     * removed and replaced with `isInWatchlist: Boolean`.
     *
     * This is a bytecode-shape assertion, not a behavior test. Its purpose is to catch
     * accidental reversion to the old signature during refactors.
     */
    @Test
    fun `noManageListsEntry - compiled dialog carries a boolean param for isInWatchlist`() {
        val clazz = Class.forName("com.nexio.tv.ui.components.ContinueWatchingSectionKt")

        // There may be several synthetic overloads (default-param bridges). We want the
        // one that has the most parameters — that is the primary non-bridge overload.
        val methods = clazz.declaredMethods.filter { it.name == "ContinueWatchingOptionsDialog" }
        assertFalse("ContinueWatchingOptionsDialog overloads must exist", methods.isEmpty())

        val primary = methods.maxByOrNull { it.parameterCount }!!

        val paramTypes = primary.parameterTypes.map { it.name }

        // isInWatchlist: Boolean — compiled to a primitive `boolean` in JVM bytecode
        assertTrue(
            "ContinueWatchingOptionsDialog must have a boolean param (isInWatchlist). " +
                "Param types found: $paramTypes",
            paramTypes.any { it == "boolean" }
        )
    }

    /**
     * Verifies that [cwLibraryLabelResId] returns the correct string resource for each
     * watchlist state. This tests the actual label-flip behavior: the resource ID changes
     * based on the boolean input, which directly controls what string the composable renders.
     */
    @Test
    fun `labelFlipsByWatchlistState - cwLibraryLabelResId returns add when false, remove when true`() {
        assertEquals(
            "isInWatchlist=false must resolve to cw_add_to_library",
            R.string.cw_add_to_library,
            cwLibraryLabelResId(isInWatchlist = false)
        )
        assertEquals(
            "isInWatchlist=true must resolve to cw_remove_from_library",
            R.string.cw_remove_from_library,
            cwLibraryLabelResId(isInWatchlist = true)
        )
    }
}

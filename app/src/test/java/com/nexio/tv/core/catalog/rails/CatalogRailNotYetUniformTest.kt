package com.nexio.tv.core.catalog.rails

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CatalogRailNotYetUniformTest {

    @CatalogRailNotYetUniform(
        reason = "test fixture",
        tracking = "docs/superpowers/plans/2026-04-30-catalog-rails-uniform-contract-foundation.md"
    )
    private class Annotated

    private class NotAnnotated

    @Test
    fun `annotation is readable via reflection at runtime is NOT required (source retention)`() {
        // Source-retention annotations are not present at runtime by design. We assert the
        // annotation is absent at runtime so a stricter retention is not silently introduced.
        val present = Annotated::class.java.getAnnotation(CatalogRailNotYetUniform::class.java)
        assertNull(
            "CatalogRailNotYetUniform must use SOURCE retention (not runtime). " +
                "Strict retention would couple production code to the audit annotation.",
            present
        )
    }

    @Test
    fun `annotation accepts reason and tracking strings`() {
        // Compile-only assertion: if the annotation's signature changes, this won't compile.
        @CatalogRailNotYetUniform(
            reason = "compile-only check",
            tracking = "docs/superpowers/plans/2026-04-30-catalog-rails-uniform-contract-foundation.md"
        )
        class CompileCheck

        // Trivial assertion to keep JUnit happy.
        assertEquals(CompileCheck::class.simpleName, "CompileCheck")
    }
}

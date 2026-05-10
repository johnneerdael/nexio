package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FieldResolverOriginalLanguageTest {
    @Test
    fun `originalLanguage routed from ORIGINAL_LANGUAGE field`() {
        val fields = mapOf<ResolvedField, Any>(
            ResolvedField.ORIGINAL_LANGUAGE to "eng"
        )
        val document = buildDocumentFromFieldsForTest(fields)
        assertEquals("eng", document.originalLanguage)
    }

    @Test
    fun `originalLanguage null when ORIGINAL_LANGUAGE field absent`() {
        val document = buildDocumentFromFieldsForTest(emptyMap())
        assertNull(document.originalLanguage)
    }
}

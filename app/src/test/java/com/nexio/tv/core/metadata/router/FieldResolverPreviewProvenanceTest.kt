package com.nexio.tv.core.metadata.router

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-B-01 pin: PREVIEW resolution must produce a ResolvedMetadataDocument with non-empty
 * fieldOwners when only a preview candidate is supplied (primary = null).
 *
 * The bug being guarded: MetadataRouterFacade.resolveRequest's PREVIEW early-return path
 * calls HomeDisplayMetadata.toResolvedDocument() which hard-codes fieldOwners = emptyMap().
 * The fix (Task 3) routes that path through FieldResolver.resolveWithPreview so every
 * preview-sourced field carries owner provenance.
 *
 * Note: FieldOwner has no PREVIEW variant — preview-sourced fields are owned by whichever
 * FieldOwner is passed in FieldValue.owner. Using FieldOwner.PRIMARY here mirrors how
 * addon-preview candidates are built in production (sourceContext.toPreviewCandidate).
 */
class FieldResolverPreviewProvenanceTest {

    private val resolver = FieldResolver()

    @Test
    fun `resolveWithPreview produces non-empty fieldOwners for preview-only inputs`() {
        val previewCandidate = MetadataCandidate(
            provider = MetadataPrimaryProvider.TMDB,
            fields = mapOf(
                ResolvedField.TITLE to FieldValue(
                    value = "Fight Club",
                    owner = FieldOwner.PRIMARY,
                    sourceRole = SourceRole.ADDON_PREVIEW
                ),
                ResolvedField.OVERVIEW to FieldValue(
                    value = "An office worker and a soap salesman form an underground fight club.",
                    owner = FieldOwner.PRIMARY,
                    sourceRole = SourceRole.ADDON_PREVIEW
                ),
                ResolvedField.POSTER to FieldValue(
                    value = "https://example.com/poster.jpg",
                    owner = FieldOwner.PRIMARY,
                    sourceRole = SourceRole.ADDON_PREVIEW
                )
            ),
            sourceProvider = "stremio.cinemeta",
            sourceRole = SourceRole.ADDON_PREVIEW
        )

        val doc = resolver.resolveWithPreview(
            preview = previewCandidate,
            primary = null,
            secondary = emptyList()
        )

        assertEquals("title must come from preview candidate", "Fight Club", doc.title)
        assertTrue(
            "fieldOwners must not be empty — PREVIEW path must carry provenance",
            doc.fieldOwners.isNotEmpty()
        )
        assertEquals(
            "TITLE owner must be PRIMARY (preview-sourced, no PREVIEW variant in FieldOwner)",
            FieldOwner.PRIMARY,
            doc.fieldOwners[ResolvedField.TITLE]
        )
        assertEquals(
            "OVERVIEW owner must be PRIMARY",
            FieldOwner.PRIMARY,
            doc.fieldOwners[ResolvedField.OVERVIEW]
        )
        assertEquals(
            "POSTER owner must be PRIMARY",
            FieldOwner.PRIMARY,
            doc.fieldOwners[ResolvedField.POSTER]
        )
    }
}

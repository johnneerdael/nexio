package com.nexio.tv.notices

import com.nexio.tv.notices.model.RemoteNoticeManifest
import com.nexio.tv.notices.model.RemoteNoticeManifestItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

class RemoteNoticeSelectorTest {
    private val now = Instant.parse("2026-05-12T12:00:00Z")
    private val baseline = Instant.parse("2026-05-12T10:00:00Z")

    @Test
    fun `first install baseline suppresses existing notices`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("old", "2026-05-12T09:00:00Z"),
                notice("same", "2026-05-12T10:00:00Z")
            ),
            now = baseline,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `notice published after baseline is eligible`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("new", "2026-05-12T10:01:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("new", selected?.id)
    }

    @Test
    fun `seen notice is skipped`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("new", "2026-05-12T10:01:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = setOf("new"),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `version and expiry filters are applied`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("too-low", "2026-05-12T10:01:00Z", minVersion = "2.0.0"),
                notice("too-high", "2026-05-12T10:02:00Z", maxVersion = "1.4.9"),
                notice("expired", "2026-05-12T10:03:00Z", expiresAt = "2026-05-12T11:00:00Z"),
                notice("valid", "2026-05-12T10:04:00Z", minVersion = "1.4.0", maxVersion = "1.9.0")
            ),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("valid", selected?.id)
    }

    @Test
    fun `future notices are skipped`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(notice("future", "2026-05-12T13:00:00Z")),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertNull(selected)
    }

    @Test
    fun `newest notice wins with id tie break`() {
        val selected = RemoteNoticeSelector.selectNewestEligible(
            manifest = manifest(
                notice("b", "2026-05-12T10:10:00Z"),
                notice("a", "2026-05-12T10:10:00Z"),
                notice("older", "2026-05-12T10:09:00Z")
            ),
            now = now,
            baselineAt = baseline,
            seenIds = emptySet(),
            appVersion = "1.5.0"
        )

        assertEquals("a", selected?.id)
    }

    @Test
    fun `invalid manifest and invalid urls return no selection`() {
        assertNull(
            RemoteNoticeSelector.selectNewestEligible(
                manifest = RemoteNoticeManifest(schemaVersion = 2, notices = listOf(notice("new", "2026-05-12T10:01:00Z"))),
                now = now,
                baselineAt = baseline,
                seenIds = emptySet(),
                appVersion = "1.5.0"
            )
        )

        assertNull(
            RemoteNoticeSelector.selectNewestEligible(
                manifest = manifest(notice("bad-url", "2026-05-12T10:01:00Z", markdownUrl = "http://example.com/a.md")),
                now = now,
                baselineAt = baseline,
                seenIds = emptySet(),
                appVersion = "1.5.0"
            )
        )
    }

    private fun manifest(vararg notices: RemoteNoticeManifestItem) =
        RemoteNoticeManifest(schemaVersion = 1, notices = notices.toList())

    private fun notice(
        id: String,
        publishedAt: String,
        title: String = "Notice $id",
        markdownUrl: String = "https://raw.githubusercontent.com/johnneerdael/nexio/main/notices/$id.md",
        minVersion: String? = null,
        maxVersion: String? = null,
        expiresAt: String? = null
    ) = RemoteNoticeManifestItem(
        id = id,
        title = title,
        publishedAt = publishedAt,
        markdownUrl = markdownUrl,
        minVersion = minVersion,
        maxVersion = maxVersion,
        expiresAt = expiresAt
    )
}

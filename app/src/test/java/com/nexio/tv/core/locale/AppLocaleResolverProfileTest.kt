package com.nexio.tv.core.locale

import android.content.Context
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AppLocaleResolverProfileTest {

    @Test
    fun `stored locale is isolated by active profile`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)

        AppLocaleResolver.setStoredLocaleTag(context, "nl")
        AppLocaleResolver.setActiveProfileId(context, 2)
        assertNull(AppLocaleResolver.getStoredLocaleTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "en")
        assertEquals("en", AppLocaleResolver.getStoredLocaleTag(context))

        AppLocaleResolver.setActiveProfileId(context, 1)
        assertEquals("nl", AppLocaleResolver.getStoredLocaleTag(context))
    }

    @Test
    fun `tvdb language tag maps every supported app language to tvdb abbreviation`() {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)

        AppLocaleResolver.setStoredLocaleTag(context, "en")
        assertEquals("eng", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "es")
        assertEquals("spa", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "fr")
        assertEquals("fra", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "de")
        assertEquals("deu", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "nl")
        assertEquals("nld", AppLocaleResolver.resolveTvdbLanguageTag(context))

        AppLocaleResolver.setStoredLocaleTag(context, "zh-CN")
        assertEquals("zho", AppLocaleResolver.resolveTvdbLanguageTag(context))
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk {
            every { getSharedPreferences("app_locale", Context.MODE_PRIVATE) } returns prefs
        }
    }
}

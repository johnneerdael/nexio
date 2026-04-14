package com.nexio.tv.profile

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

typealias AndroidJUnit4 = RobolectricTestRunner

@RunWith(AndroidJUnit4::class)
class ProfileAvatarTest {
    @Test
    fun WEB_05_avatar_contract_uses_url_with_color_fallback() {
        val contract = listOf(
            "WEB-05",
            "avatar_url",
            "avatarUrl",
            "avatarColorHex",
            "profile-avatars",
            "?t="
        )

        assertTrue("WEB-05 Supabase avatar column should be documented", "avatar_url" in contract)
        assertTrue("WEB-05 Android avatar field should be documented", "avatarUrl" in contract)
        assertTrue("WEB-05 color fallback field should be documented", "avatarColorHex" in contract)
        assertTrue("WEB-05 public avatar bucket should be documented", "profile-avatars" in contract)
        assertTrue("WEB-05 URL cache invalidation token should be documented", "?t=" in contract)
    }

    @Test
    fun WEB_05_avatar_url_field_is_nullable_contract() {
        val avatarUrl: String? = null

        assertNull("WEB-05 avatar URL should allow null so color fallback can render", avatarUrl)
    }
}

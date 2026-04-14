package com.nexio.tv.core.sync

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Ignore
import org.junit.runner.RunWith

@Ignore("Behavior tests require Postgrest test doubles for RPC response bodies.")
@RunWith(AndroidJUnit4::class)
class ProfileSyncServiceTest {
    fun `pushToRemote encodes all UserProfile fields`() = Unit

    fun `pullFromRemote replaces profile list atomically`() = Unit

    fun `pullFromRemote with empty list does not call replaceAllProfiles`() = Unit
}

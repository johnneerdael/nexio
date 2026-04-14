package com.nexio.tv.data.local

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ProfileDataStoreFactoryTest {

    private lateinit var factory: ProfileDataStoreFactory

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Application>()
        factory = ProfileDataStoreFactory(context)
    }

    @Test
    fun `get same profile and feature returns cached instance`() {
        val store1 = factory.get(1, "trakt_auth")
        val store2 = factory.get(1, "trakt_auth")
        assertSame(store1, store2)
    }

    @Test
    fun `get profile 1 and profile 2 with same feature returns different instances`() {
        val store1 = factory.get(1, "trakt_auth")
        val store2 = factory.get(2, "trakt_auth")
        assertNotSame(store1, store2)
    }

    @Test
    fun `isProfileDeleted returns true after clearProfile`() = runTest {
        factory.clearProfile(2)
        assertTrue(factory.isProfileDeleted(2))
    }

    @Test
    fun `isProfileDeleted returns false after markProfileCreated`() = runTest {
        factory.clearProfile(2)
        factory.markProfileCreated(2)
        assertFalse(factory.isProfileDeleted(2))
    }

    @Test
    fun `clearProfile profile 1 is a no-op`() = runTest {
        factory.clearProfile(1)
        assertFalse(factory.isProfileDeleted(1))
    }

    @Test
    fun `get after clearProfile returns fresh instance`() = runTest {
        val storeBefore = factory.get(2, "feat")
        factory.clearProfile(2)
        val storeAfter = factory.get(2, "feat")
        assertNotSame(storeBefore, storeAfter)
    }
}

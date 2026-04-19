package com.nexio.tv.data.local

import android.content.Context
import com.nexio.tv.data.repository.MDBListDiscoverySnapshot
import com.nexio.tv.data.repository.MDBListListOption
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class MDBListDiscoverySnapshotStoreTest {
    @Test
    fun `explicit profile id keeps mdblist discovery snapshots isolated`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = MDBListDiscoverySnapshotStore(context)
        val profileOne = MDBListDiscoverySnapshot(personalLists = listOf(option("one")), updatedAtMs = 1L)
        val profileTwo = MDBListDiscoverySnapshot(personalLists = listOf(option("two")), updatedAtMs = 2L)

        store.write(profileOne, profileId = 1)
        store.write(profileTwo, profileId = 2)

        assertEquals("one", store.read(profileId = 1)?.personalLists?.single()?.key)
        assertEquals("two", store.read(profileId = 2)?.personalLists?.single()?.key)
    }

    @Test
    fun `read sanitizes null list fields before snapshot hashing`() {
        val prefsByName = linkedMapOf<String, InMemorySharedPreferences>()
        val context = mockContext(prefsByName)
        val store = MDBListDiscoverySnapshotStore(context)
        prefsByName.getOrPut("mdblist_discovery_snapshot") { InMemorySharedPreferences() }
            .edit()
            .putString(
                "snapshot",
                """
                {
                  "topLists": [
                    {
                      "key": "top:owner/list-one",
                      "owner": null,
                      "listId": null,
                      "title": null,
                      "itemCount": 5
                    },
                    {
                      "key": "top:broken",
                      "owner": null,
                      "listId": null
                    }
                  ],
                  "updatedAtMs": 123
                }
                """.trimIndent()
            )
            .commit()

        val snapshot = store.read(profileId = 1)

        assertNotNull(snapshot)
        assertEquals(1, snapshot?.topLists?.size)
        assertEquals("owner", snapshot?.topLists?.single()?.owner)
        assertEquals("list-one", snapshot?.topLists?.single()?.listId)
        assertEquals("owner/list-one", snapshot?.topLists?.single()?.title)
        snapshot.hashCode()
    }

    private fun option(key: String) = MDBListListOption(
        key = key,
        owner = "owner",
        listId = key,
        title = key,
        itemCount = 1,
        isPersonal = true
    )

    private fun mockContext(prefsByName: MutableMap<String, InMemorySharedPreferences>): Context {
        return mockk {
            every { getSharedPreferences(any(), Context.MODE_PRIVATE) } answers {
                prefsByName.getOrPut(firstArg()) { InMemorySharedPreferences() }
            }
        }
    }
}

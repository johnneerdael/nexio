package com.nexio.tv.data.trakt.outbox

import android.content.Context
import com.google.gson.JsonObject
import com.nexio.tv.domain.model.TrackingProvider
import com.nexio.tv.testutil.InMemorySharedPreferences
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TraktMutationOutboxStoreTest {

    @Test
    fun `write and read round trips snapshot`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(sampleEnvelope(id = "queued-1")),
            nextWritableAtMs = 3_000L,
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    @Test
    fun `future schema is ignored`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 99,
              "snapshot": {
                "items": [{"id":"future"}],
                "nextWritableAtMs": 50,
                "updatedAtMs": 60
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read()

        assertTrue(restored.items.isEmpty())
        assertEquals(0L, restored.nextWritableAtMs)
        assertEquals(0L, restored.updatedAtMs)
    }

    @Test
    fun `write and read round trips SIMKL mutation envelope snapshot`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(
                TraktMutationEnvelope(
                    id = "simkl-queued-1",
                    provider = TrackingProvider.SIMKL,
                    credentialHash = "simkl-test-credential",
                    adapterKey = "simkl.library",
                    mutationKind = "simkl.library.addToList",
                    priority = TraktMutationPriorityBucket.WATCHLIST,
                    collapseKey = "simkl.library:simkl:plantowatch",
                    payload = JsonObject().apply { addProperty("contentId", "tt1375666") },
                    rollbackPayload = JsonObject().apply { addProperty("before", false) },
                    metadata = JsonObject().apply { addProperty("scope", "simkl") },
                    state = TraktMutationLifecycleState.QUEUED,
                    createdAtMs = 1_000L,
                    updatedAtMs = 1_000L,
                    nextAttemptAtMs = 1_000L
                )
            ),
            nextWritableAtMs = 3_000L,
            updatedAtMs = 2_000L
        )

        store.write(snapshot)

        assertEquals(snapshot, store.read())
    }

    @Test
    fun `write and read round trips mutation profile id`() = runTest {
        val prefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))
        val snapshot = TraktMutationOutboxSnapshot(
            items = listOf(sampleEnvelope(id = "queued-profile-2", profileId = 2)),
            updatedAtMs = 1_000L
        )

        store.write(snapshot)

        assertEquals(2, store.read().items.single().profileId)
    }

    @Test
    fun `explicit profile reads and writes stay in that profile store`() = runTest {
        val profileOnePrefs = InMemorySharedPreferences()
        val profileTwoPrefs = InMemorySharedPreferences()
        val store = TraktMutationOutboxStore(
            context = mockContext(
                "trakt_mutation_outbox" to profileOnePrefs,
                "trakt_mutation_outbox_p2" to profileTwoPrefs
            )
        )
        val profileTwoSnapshot = TraktMutationOutboxSnapshot(
            items = listOf(sampleEnvelope(id = "queued-profile-2", profileId = 2)),
            updatedAtMs = 1_000L
        )

        store.write(profileTwoSnapshot, profileId = 2)

        assertTrue(store.read(profileId = 1).items.isEmpty())
        assertEquals("queued-profile-2", store.read(profileId = 2).items.single().id)
        assertEquals(2, store.read(profileId = 2).items.single().profileId)
    }

    @Test
    fun `legacy persisted mutation without account scope defaults to profile one and quarantines`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 1,
              "snapshot": {
                "items": [
                  {
                    "id": "legacy-queued",
                    "adapterKey": "scrobble",
                    "mutationKind": "scrobble.state",
                    "priority": "SCROBBLE",
                    "payload": {},
                    "metadata": {},
                    "state": "QUEUED",
                    "createdAtMs": 1,
                    "updatedAtMs": 1,
                    "nextAttemptAtMs": 1,
                    "attemptCount": 0
                  }
                ],
                "nextWritableAtMs": 0,
                "updatedAtMs": 1
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read().items.single()
        assertEquals(1, restored.profileId)
        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, restored.state)
        assertEquals("MISSING_ACCOUNT_SCOPE", restored.lastError)
    }

    @Test
    fun `legacy envelopes without account scope are quarantined instead of executed`() = runTest {
        val prefs = InMemorySharedPreferences()
        val context = mockContext(prefs)
        val store = TraktMutationOutboxStore(context = context)
        val legacyEnvelope = JsonObject().apply {
            addProperty("id", "legacy")
            addProperty("profileId", 1)
            addProperty("adapterKey", "progress-history")
            addProperty("mutationKind", "progress.history.add")
            addProperty("priority", "WATCHED")
            add("payload", JsonObject())
            add("metadata", JsonObject())
            addProperty("state", "QUEUED")
        }
        val root = JsonObject().apply {
            addProperty("schemaVersion", 1)
            add("snapshot", JsonObject().apply {
                add("items", com.google.gson.JsonArray().apply { add(legacyEnvelope) })
                addProperty("nextWritableAtMs", 0L)
                addProperty("updatedAtMs", 0L)
            })
        }
        prefs.edit().putString("snapshot", root.toString()).commit()

        val snapshot = store.read(profileId = 1)

        assertEquals(1, snapshot.items.size)
        assertEquals(TraktMutationLifecycleState.TERMINAL_FAILED, snapshot.items.single().state)
        assertEquals("MISSING_ACCOUNT_SCOPE", snapshot.items.single().lastError)
    }

    @Test
    fun `read restores persisted item as envelope instead of raw map`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 1,
              "snapshot": {
                "items": [
                  {
                    "id": "queued-1",
                    "adapterKey": "season-batch",
                    "mutationKind": "progress.history.batchAdd",
                    "provider": "TRAKT",
                    "credentialHash": "trakt-test-credential",
                    "priority": "WATCHED",
                    "collapseKey": "show-1:season:1",
                    "payload": {
                      "showContentId": "show-1",
                      "seasonNumber": 1,
                      "episodes": [
                        {
                          "episodeNumber": 1,
                          "traktId": 101
                        }
                      ]
                    },
                    "rollbackPayload": {
                      "resumeItems": [],
                      "nextUpItems": [],
                      "traktUpNextItems": []
                    },
                    "metadata": {},
                    "state": "QUEUED",
                    "createdAtMs": 1,
                    "updatedAtMs": 1,
                    "nextAttemptAtMs": 1,
                    "attemptCount": 0
                  }
                ],
                "nextWritableAtMs": 0,
                "updatedAtMs": 1
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read()

        assertEquals(1, restored.items.size)
        val envelope = restored.items.single()
        assertEquals("queued-1", envelope.id)
        assertEquals("season-batch", envelope.adapterKey)
        assertEquals(TraktMutationPriorityBucket.WATCHED, envelope.priority)
        assertFalse(envelope.payload.getAsJsonArray("episodes").isEmpty)
    }

    @Test
    fun `read skips malformed persisted items but keeps valid envelopes`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 1,
              "snapshot": {
                "items": [
                  "broken",
                  {
                    "id": "queued-2",
                    "adapterKey": "simkl.season-batch",
                    "mutationKind": "simkl.progress.history.batchAdd",
                    "provider": "SIMKL",
                    "credentialHash": "simkl-test-credential",
                    "priority": "WATCHED",
                    "collapseKey": "show-2:season:2",
                    "payload": {
                      "showContentId": "show-2",
                      "seasonNumber": 2
                    },
                    "rollbackPayload": {},
                    "metadata": {},
                    "state": "QUEUED",
                    "createdAtMs": 2,
                    "updatedAtMs": 2,
                    "nextAttemptAtMs": 2,
                    "attemptCount": 0
                  }
                ],
                "nextWritableAtMs": 0,
                "updatedAtMs": 2
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read()

        assertEquals(1, restored.items.size)
        assertEquals("queued-2", restored.items.single().id)
    }

    @Test
    fun `read drops object shaped malformed envelopes missing required fields`() = runTest {
        val prefs = InMemorySharedPreferences()
        prefs.edit().putString(
            "snapshot",
            """
            {
              "schemaVersion": 1,
              "snapshot": {
                "items": [
                  {
                    "id": "missing-required-fields",
                    "priority": "WATCHED",
                    "payload": {},
                    "metadata": {},
                    "state": "QUEUED",
                    "createdAtMs": 1,
                    "updatedAtMs": 1,
                    "nextAttemptAtMs": 1,
                    "attemptCount": 0
                  },
                  {
                    "id": "valid",
                    "adapterKey": "progress",
                    "mutationKind": "history_add",
                    "provider": "TRAKT",
                    "credentialHash": "trakt-test-credential",
                    "priority": "WATCHED",
                    "payload": {},
                    "metadata": {},
                    "state": "QUEUED",
                    "createdAtMs": 1,
                    "updatedAtMs": 1,
                    "nextAttemptAtMs": 1,
                    "attemptCount": 0,
                    "profileId": 1
                  }
                ],
                "nextWritableAtMs": 0,
                "updatedAtMs": 1
              }
            }
            """.trimIndent()
        ).commit()
        val store = TraktMutationOutboxStore(context = mockContext(prefs))

        val restored = store.read()

        assertEquals(listOf("valid"), restored.items.map { it.id })
    }

    private fun mockContext(prefs: InMemorySharedPreferences): Context {
        return mockk(relaxed = true) {
            every { getSharedPreferences("trakt_mutation_outbox", Context.MODE_PRIVATE) } returns prefs
        }
    }

    private fun mockContext(vararg prefsByName: Pair<String, InMemorySharedPreferences>): Context {
        return mockk(relaxed = true) {
            prefsByName.forEach { (name, prefs) ->
                every { getSharedPreferences(name, Context.MODE_PRIVATE) } returns prefs
            }
        }
    }

    private fun sampleEnvelope(
        id: String,
        profileId: Int = 1
    ): TraktMutationEnvelope {
        return TraktMutationEnvelope(
            id = id,
            profileId = profileId,
            provider = TrackingProvider.TRAKT,
            credentialHash = "trakt-test-credential",
            adapterKey = "progress",
            mutationKind = "history_add",
            priority = TraktMutationPriorityBucket.WATCHED,
            collapseKey = "show-1",
            payload = JsonObject().apply { addProperty("contentId", "show-1") },
            rollbackPayload = JsonObject().apply { addProperty("before", false) },
            metadata = JsonObject().apply { addProperty("scope", "progress") },
            state = TraktMutationLifecycleState.QUEUED,
            createdAtMs = 1_000L,
            updatedAtMs = 1_000L,
            nextAttemptAtMs = 1_000L
        )
    }
}

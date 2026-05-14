package com.nexio.tv.data.local

import com.google.gson.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class FileBackedJsonObjectStoreTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `put persists entry and get reloads from disk`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val first = FileBackedJsonObjectStore(file)
        first.put("alpha", jsonObject("name", "A"))

        val second = FileBackedJsonObjectStore(file)

        assertEquals("A", second.get("alpha")?.get("name")?.asString)
    }

    @Test
    fun `two instances for same file do not lose sequential updates`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val first = FileBackedJsonObjectStore(file)
        val second = FileBackedJsonObjectStore(file)

        first.put("alpha", jsonObject("name", "A"))
        second.put("beta", jsonObject("name", "B"))
        first.put("gamma", jsonObject("name", "C"))

        val reloaded = FileBackedJsonObjectStore(file)
        val text = file.readText(Charsets.UTF_8)
        assertEquals(setOf("alpha", "beta", "gamma"), reloaded.keys())
        assertEquals("A", reloaded.get("alpha")?.get("name")?.asString)
        assertEquals("B", reloaded.get("beta")?.get("name")?.asString)
        assertEquals("C", reloaded.get("gamma")?.get("name")?.asString)
        assertTrue(text.contains("\"alpha\""))
        assertTrue(text.contains("\"beta\""))
        assertTrue(text.contains("\"gamma\""))
    }

    @Test
    fun `remove deletes only matching key`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.put("alpha", jsonObject("name", "A"))
        store.put("beta", jsonObject("name", "B"))

        store.remove("alpha")

        assertNull(store.get("alpha"))
        assertEquals("B", store.get("beta")?.get("name")?.asString)
    }

    @Test
    fun `removeAll deletes matching keys in one persisted update`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.putAll(
            mapOf(
                "alpha" to jsonObject("name", "A"),
                "beta" to jsonObject("name", "B"),
                "gamma" to jsonObject("name", "C")
            )
        )

        assertTrue(store.removeAll(listOf("alpha", "gamma")))

        val reloaded = FileBackedJsonObjectStore(file)
        assertEquals(setOf("beta"), reloaded.keys())
        assertEquals("B", reloaded.get("beta")?.get("name")?.asString)
        val text = file.readText(Charsets.UTF_8)
        assertFalse(text.contains("\"alpha\""))
        assertFalse(text.contains("\"gamma\""))
        assertTrue(text.contains("\"beta\""))
    }

    @Test
    fun `resetSharedStateForTest forces next instance to read disk`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.put("alpha", jsonObject("name", "A"))
        file.writeText("""{"alpha":{"name":"disk"}}""", Charsets.UTF_8)

        assertEquals("A", FileBackedJsonObjectStore(file).get("alpha")?.get("name")?.asString)

        FileBackedJsonObjectStore.resetSharedStateForTest(file)

        assertEquals("disk", FileBackedJsonObjectStore(file).get("alpha")?.get("name")?.asString)
    }

    @Test
    fun `keys returns persisted keys after reload`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        FileBackedJsonObjectStore(file).putAll(
            mapOf(
                "alpha" to jsonObject("name", "A"),
                "beta" to jsonObject("name", "B")
            )
        )

        val reloaded = FileBackedJsonObjectStore(file)

        assertEquals(setOf("alpha", "beta"), reloaded.keys())
    }

    @Test
    fun `entries returns deep copies of persisted values`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.put("alpha", jsonObject("name", "A"))

        val entries = store.entries()
        entries.getValue("alpha").addProperty("name", "mutated")

        assertEquals("A", store.get("alpha")?.get("name")?.asString)
    }

    @Test
    fun `put deep copies input value`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        val value = jsonObject("name", "A")

        store.put("alpha", value)
        value.addProperty("name", "mutated")

        assertEquals("A", store.get("alpha")?.get("name")?.asString)
    }

    @Test
    fun `failed put does not mutate in-memory entries`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.put("alpha", jsonObject("name", "A"))
        assertEquals("A", store.get("alpha")?.get("name")?.asString)
        assertTrue(file.delete())
        assertTrue(file.mkdir())

        val stored = store.put("beta", jsonObject("name", "B"))

        assertFalse(stored)
        assertEquals(setOf("alpha"), store.keys())
        assertEquals("A", store.get("alpha")?.get("name")?.asString)
        assertNull(store.get("beta"))
    }

    @Test
    fun `replaceAll overwrites existing entries`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.put("alpha", jsonObject("name", "A"))

        store.replaceAll(mapOf("beta" to jsonObject("name", "B")))

        val reloaded = FileBackedJsonObjectStore(file)
        assertNull(reloaded.get("alpha"))
        assertEquals(setOf("beta"), reloaded.keys())
        assertEquals("B", reloaded.get("beta")?.get("name")?.asString)
    }

    @Test
    fun `clear removes all entries`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        val store = FileBackedJsonObjectStore(file)
        store.putAll(
            mapOf(
                "alpha" to jsonObject("name", "A"),
                "beta" to jsonObject("name", "B")
            )
        )

        store.clear()

        assertTrue(FileBackedJsonObjectStore(file).entries().isEmpty())
    }

    @Test
    fun `corrupt file reads as empty and can be overwritten`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        file.writeText("{not-json", Charsets.UTF_8)
        val store = FileBackedJsonObjectStore(file)

        assertNull(store.get("alpha"))
        store.put("alpha", jsonObject("name", "A"))

        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("\"alpha\""))
        assertFalse(text.contains("not-json"))
    }

    @Test
    fun `wrong-type entry values are skipped while valid objects load`() {
        val file = tmp.newFolder("cache").resolve("entries.json")
        file.writeText(
            """
            {
              "alpha": {"name": "A"},
              "string": "bad",
              "array": ["bad"],
              "null": null
            }
            """.trimIndent(),
            Charsets.UTF_8
        )

        val store = FileBackedJsonObjectStore(file)

        assertEquals(setOf("alpha"), store.keys())
        assertEquals("A", store.get("alpha")?.get("name")?.asString)
        assertNull(store.get("string"))
        assertNull(store.get("array"))
        assertNull(store.get("null"))
    }

    @Test
    fun `missing file reads as empty`() {
        val file = tmp.newFolder("cache").resolve("entries.json")

        val store = FileBackedJsonObjectStore(file)

        assertTrue(store.keys().isEmpty())
        assertTrue(store.entries().isEmpty())
    }

    private fun jsonObject(name: String, value: String): JsonObject =
        JsonObject().apply { addProperty(name, value) }
}

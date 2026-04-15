package com.nexio.tv.data.local

import android.content.Context
import android.content.SharedPreferences
import com.nexio.tv.domain.model.ContentType
import com.nexio.tv.domain.model.Meta
import com.nexio.tv.domain.model.PosterShape
import io.mockk.every
import io.mockk.mockk
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.EmptyCoroutineContext
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Test

class MetadataDiskCacheStoreWriteBatchingTest {

    @Test
    fun `repeated metadata writes are batched into one disk commit window`() {
        val prefs = RecordingSharedPreferences()
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val store = MetadataDiskCacheStore(
            context = context,
            ioScope = CoroutineScope(EmptyCoroutineContext),
            debounceMs = Long.MAX_VALUE,
        )

        repeat(20) { index ->
            store.writeMeta(
                itemKey = "item-$index",
                languageTag = "en",
                providerToken = "pm",
                meta = meta(index),
            )
        }

        store.flushPendingWritesForTest()

        assertEquals(1, prefs.applyCount)
    }

    @Test
    fun `tvdb reference writes are batched`() {
        val prefs = RecordingSharedPreferences()
        val context = mockk<Context>()
        every { context.getSharedPreferences(any(), any()) } returns prefs
        val store = MetadataDiskCacheStore(
            context = context,
            ioScope = CoroutineScope(EmptyCoroutineContext),
            debounceMs = Long.MAX_VALUE,
        )

        // Write multiple reference kinds
        store.writeTvdbReference("genres", listOf(mapOf("id" to 1, "name" to "Drama")))
        store.writeTvdbReference("languages", listOf(mapOf("id" to "eng", "name" to "English")))
        store.writeTvdbReference("entity_types", listOf(mapOf("id" to 1, "name" to "Series")))

        store.flushPendingWritesForTest()

        assertEquals(1, prefs.applyCount)
    }

    private fun meta(index: Int): Meta {
        return Meta(
            id = "item-$index",
            type = ContentType.MOVIE,
            name = "Item $index",
            poster = null,
            posterShape = PosterShape.POSTER,
            background = null,
            logo = null,
            description = null,
            releaseInfo = null,
            imdbRating = null,
            genres = emptyList(),
            runtime = null,
            director = emptyList(),
            cast = emptyList(),
            videos = emptyList(),
            country = null,
            awards = null,
            language = null,
            links = emptyList(),
        )
    }

    private class RecordingSharedPreferences : SharedPreferences {
        private val values = ConcurrentHashMap<String, Any?>()
        var applyCount: Int = 0
            private set

        override fun getAll(): MutableMap<String, *> = values.toMutableMap()
        override fun getString(key: String?, defValue: String?): String? = values[key] as? String ?: defValue
        override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String?, defValue: Int): Int = values[key] as? Int ?: defValue
        override fun getLong(key: String?, defValue: Long): Long = values[key] as? Long ?: defValue
        override fun getFloat(key: String?, defValue: Float): Float = values[key] as? Float ?: defValue
        override fun getBoolean(key: String?, defValue: Boolean): Boolean = values[key] as? Boolean ?: defValue
        override fun contains(key: String?): Boolean = values.containsKey(key)
        override fun edit(): SharedPreferences.Editor = Editor()
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        private inner class Editor : SharedPreferences.Editor {
            private val pending = LinkedHashMap<String, Any?>()
            private var clearRequested = false

            override fun putString(key: String?, value: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = values?.toSet()
            }

            override fun putInt(key: String?, value: Int): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putLong(key: String?, value: Long): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = value
            }

            override fun remove(key: String?): SharedPreferences.Editor = apply {
                if (key != null) pending[key] = null
            }

            override fun clear(): SharedPreferences.Editor = apply {
                clearRequested = true
            }

            override fun commit(): Boolean {
                apply()
                return true
            }

            override fun apply() {
                if (clearRequested) values.clear()
                pending.forEach { (key, value) ->
                    if (value == null) values.remove(key) else values[key] = value
                }
                applyCount += 1
            }
        }
    }
}

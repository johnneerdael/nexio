package com.nexio.tv.testutil

import android.content.SharedPreferences

class InMemorySharedPreferences : SharedPreferences {
    private val values = linkedMapOf<String, Any?>()
    private val listeners = linkedSetOf<SharedPreferences.OnSharedPreferenceChangeListener>()

    override fun getAll(): MutableMap<String, *> = values.toMutableMap()

    override fun getString(key: String?, defValue: String?): String? =
        values[key] as? String ?: defValue

    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? =
        (values[key] as? MutableSet<String>) ?: defValues

    override fun getInt(key: String?, defValue: Int): Int =
        values[key] as? Int ?: defValue

    override fun getLong(key: String?, defValue: Long): Long =
        values[key] as? Long ?: defValue

    override fun getFloat(key: String?, defValue: Float): Float =
        values[key] as? Float ?: defValue

    override fun getBoolean(key: String?, defValue: Boolean): Boolean =
        values[key] as? Boolean ?: defValue

    override fun contains(key: String?): Boolean = values.containsKey(key)

    override fun edit(): SharedPreferences.Editor = Editor()

    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners += listener
    }

    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {
        if (listener != null) listeners -= listener
    }

    private inner class Editor : SharedPreferences.Editor {
        private val pending = linkedMapOf<String, Any?>()
        private var clearRequested = false

        override fun putString(key: String?, value: String?): SharedPreferences.Editor = applyChange(key, value)

        override fun putStringSet(key: String?, values: MutableSet<String>?): SharedPreferences.Editor =
            applyChange(key, values)

        override fun putInt(key: String?, value: Int): SharedPreferences.Editor = applyChange(key, value)

        override fun putLong(key: String?, value: Long): SharedPreferences.Editor = applyChange(key, value)

        override fun putFloat(key: String?, value: Float): SharedPreferences.Editor = applyChange(key, value)

        override fun putBoolean(key: String?, value: Boolean): SharedPreferences.Editor = applyChange(key, value)

        override fun remove(key: String?): SharedPreferences.Editor = applyChange(key, null)

        override fun clear(): SharedPreferences.Editor {
            clearRequested = true
            return this
        }

        override fun commit(): Boolean {
            apply()
            return true
        }

        override fun apply() {
            if (clearRequested) {
                val keys = values.keys.toList()
                values.clear()
                keys.forEach(::notifyChanged)
            }
            pending.forEach { (key, value) ->
                if (value == null) {
                    values.remove(key)
                } else {
                    values[key] = value
                }
                notifyChanged(key)
            }
        }

        private fun applyChange(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) {
                pending[key] = value
            }
            return this
        }
    }

    private fun notifyChanged(key: String) {
        listeners.forEach { it.onSharedPreferenceChanged(this, key) }
    }
}

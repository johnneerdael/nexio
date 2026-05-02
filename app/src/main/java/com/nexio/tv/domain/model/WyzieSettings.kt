package com.nexio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * Snapshot of the user's Wyzie subtitle preferences.
 *
 * `apiKey == null` (or blank) means "no key set"; the Wyzie lane is silently skipped.
 * `enabled == false` means the user explicitly disabled Wyzie even if a key exists.
 */
@Immutable
data class WyzieSettings(
    val apiKey: String? = null,
    val enabled: Boolean = true,
) {
    val isUsable: Boolean
        get() = enabled && !apiKey.isNullOrBlank()

    companion object {
        val DEFAULT = WyzieSettings()
    }
}

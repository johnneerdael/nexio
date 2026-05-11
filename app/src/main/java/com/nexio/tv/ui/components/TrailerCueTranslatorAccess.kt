package com.nexio.tv.ui.components

import android.content.Context
import com.nexio.tv.data.local.SubtitleTranslationSettingsDataStore
import com.nexio.tv.data.repository.SubtitleTranslationService
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/**
 * Hilt accessor for the deps needed to construct a
 * `BuiltInSubtitleCueTranslator` from a `@Composable` context.
 *
 * The translator is the same one streams use — it's plugged into
 * ExoPlayer's TextRenderer and translates cue groups on-demand as
 * playback reaches them, so captions render instantly in source
 * language and swap in batched translation as it arrives. No
 * blocking pre-translation, no playback restart.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
internal interface TrailerCueTranslatorAccess {
    fun subtitleTranslationService(): SubtitleTranslationService
    fun subtitleTranslationSettingsDataStore(): SubtitleTranslationSettingsDataStore

    companion object {
        fun from(context: Context): TrailerCueTranslatorAccess {
            return EntryPointAccessors
                .fromApplication(
                    context.applicationContext,
                    TrailerCueTranslatorAccess::class.java
                )
        }
    }
}

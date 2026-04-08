package com.nexio.tv.core.di

import android.content.Context
import com.nexio.tv.instrumentation.PlaybackTraceToggle
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides [PlaybackTraceToggle] for the playback instrumentation pipeline.
 *
 * WP1 deliberately does not collect [PlaybackTraceToggle.enabledFlow] from a
 * lifecycle scope here — that startup wiring is owned by WP2.
 */
@Module
@InstallIn(SingletonComponent::class)
object PlaybackInstrumentationModule {

    @Provides
    @Singleton
    fun providePlaybackTraceToggle(
        @ApplicationContext context: Context
    ): PlaybackTraceToggle = PlaybackTraceToggle(context)
}

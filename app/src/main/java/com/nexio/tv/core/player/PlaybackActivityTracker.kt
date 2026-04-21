package com.nexio.tv.core.player

import android.content.Context
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * App-wide flag for "is a player session currently live". Flipped by the
 * player runtime controller on init/release. Read by background work
 * (home catalog refresh image prefetch, etc.) to suppress non-essential
 * image decoding/GPU work while the user is watching.
 */
@Singleton
class PlaybackActivityTracker @Inject constructor() {
    private val _isActive = MutableStateFlow(false)
    val isActive: StateFlow<Boolean> = _isActive.asStateFlow()

    fun setActive(active: Boolean) {
        _isActive.value = active
    }

    @EntryPoint
    @InstallIn(SingletonComponent::class)
    interface EntryPointAccess {
        fun playbackActivityTracker(): PlaybackActivityTracker
    }

    companion object {
        fun fromContext(context: Context): PlaybackActivityTracker {
            val appContext = context.applicationContext
            return EntryPointAccessors
                .fromApplication(appContext, EntryPointAccess::class.java)
                .playbackActivityTracker()
        }
    }
}

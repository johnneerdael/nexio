package com.nexio.tv.core.playback

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

@Singleton
class PlaybackSessionRegistry @Inject constructor() {
    private data class Entry(val token: String, val context: PlaybackOwnerContext)

    private val current = AtomicReference<Entry?>(null)

    private val _ownerState = MutableStateFlow<PlaybackOwnerContext?>(null)
    val ownerState: StateFlow<PlaybackOwnerContext?> = _ownerState.asStateFlow()

    fun register(context: PlaybackOwnerContext): String {
        val token = UUID.randomUUID().toString()
        current.set(Entry(token, context))
        _ownerState.value = context
        return token
    }

    fun unregister(token: String) {
        current.updateAndGet { existing ->
            if (existing != null && existing.token == token) null else existing
        }
        _ownerState.value = current.get()?.context
    }

    fun activeOwner(): PlaybackOwnerContext? = current.get()?.context

    fun isIdle(): Boolean = current.get() == null
}

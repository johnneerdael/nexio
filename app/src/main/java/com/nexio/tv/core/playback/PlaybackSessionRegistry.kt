package com.nexio.tv.core.playback

import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaybackSessionRegistry @Inject constructor() {
    private data class Entry(val token: String, val context: PlaybackOwnerContext)

    private val current = AtomicReference<Entry?>(null)

    fun register(context: PlaybackOwnerContext): String {
        val token = UUID.randomUUID().toString()
        current.set(Entry(token, context))
        return token
    }

    fun unregister(token: String) {
        current.updateAndGet { existing ->
            if (existing != null && existing.token == token) null else existing
        }
    }

    fun activeOwner(): PlaybackOwnerContext? = current.get()?.context

    fun isIdle(): Boolean = current.get() == null
}

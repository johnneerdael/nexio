package com.nexio.tv.core.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import `is`.xyz.mpv.MPVLib

@Suppress("unused")
object NexioMpvLib {
    val isAvailable: Boolean
        get() = MPVLib.isAvailable

    fun availabilityError(): Throwable? = MPVLib.availabilityError()

    fun create(appctx: Context) {
        MPVLib.create(appctx)
    }

    fun init() {
        MPVLib.init()
    }

    fun destroy() {
        MPVLib.destroy()
    }

    fun attachSurface(surface: Surface) {
        MPVLib.attachSurface(surface)
    }

    fun detachSurface() {
        MPVLib.detachSurface()
    }

    fun command(cmd: Array<out String>) {
        MPVLib.command(cmd)
    }

    fun setOptionString(name: String, value: String): Int {
        return MPVLib.setOptionString(name, value)
    }

    fun grabThumbnail(dimension: Int): Bitmap? {
        return MPVLib.grabThumbnail(dimension)
    }

    fun getPropertyInt(property: String): Int? {
        return MPVLib.getPropertyInt(property)
    }

    fun setPropertyInt(property: String, value: Int) {
        MPVLib.setPropertyInt(property, value)
    }

    fun getPropertyDouble(property: String): Double? {
        return MPVLib.getPropertyDouble(property)
    }

    fun setPropertyDouble(property: String, value: Double) {
        MPVLib.setPropertyDouble(property, value)
    }

    fun getPropertyBoolean(property: String): Boolean? {
        return MPVLib.getPropertyBoolean(property)
    }

    fun setPropertyBoolean(property: String, value: Boolean) {
        MPVLib.setPropertyBoolean(property, value)
    }

    fun getPropertyString(property: String): String? {
        return MPVLib.getPropertyString(property)
    }

    fun setPropertyString(property: String, value: String) {
        MPVLib.setPropertyString(property, value)
    }

    fun observeProperty(property: String, format: Int) {
        MPVLib.observeProperty(property, format)
    }

    private val observers = mutableListOf<EventObserver>()
    private val logObservers = mutableListOf<LogObserver>()

    @JvmStatic
    fun addObserver(observer: EventObserver) {
        synchronized(observers) {
            observers.add(observer)
        }
    }

    @JvmStatic
    fun removeObserver(observer: EventObserver) {
        synchronized(observers) {
            observers.remove(observer)
        }
    }

    @JvmStatic
    fun addLogObserver(observer: LogObserver) {
        synchronized(logObservers) {
            logObservers.add(observer)
        }
    }

    @JvmStatic
    fun removeLogObserver(observer: LogObserver) {
        synchronized(logObservers) {
            logObservers.remove(observer)
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        synchronized(observers) {
            observers.toList().forEach { it.eventProperty(property, value) }
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        synchronized(observers) {
            observers.toList().forEach { it.eventProperty(property, value) }
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        synchronized(observers) {
            observers.toList().forEach { it.eventProperty(property, value) }
        }
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        synchronized(observers) {
            observers.toList().forEach { it.eventProperty(property, value) }
        }
    }

    @JvmStatic
    fun eventProperty(property: String) {
        synchronized(observers) {
            observers.toList().forEach { it.eventProperty(property) }
        }
    }

    @JvmStatic
    fun event(eventId: Int) {
        synchronized(observers) {
            observers.toList().forEach { it.event(eventId) }
        }
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        synchronized(logObservers) {
            logObservers.toList().forEach { it.logMessage(prefix, level, text) }
        }
    }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object Format {
        const val NONE = 0
        const val STRING = 1
        const val OSD_STRING = 2
        const val FLAG = 3
        const val INT64 = 4
        const val DOUBLE = 5
    }

    object Event {
        const val SHUTDOWN = 1
        const val LOG_MESSAGE = 2
        const val START_FILE = 6
        const val END_FILE = 7
        const val FILE_LOADED = 8
        const val VIDEO_RECONFIG = 17
        const val AUDIO_RECONFIG = 18
        const val SEEK = 20
        const val PLAYBACK_RESTART = 21
        const val PROPERTY_CHANGE = 22
    }
}

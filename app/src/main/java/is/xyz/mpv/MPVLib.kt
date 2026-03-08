package `is`.xyz.mpv

import android.content.Context
import android.graphics.Bitmap
import android.view.Surface
import com.nexio.tv.core.mpv.NexioMpvLib

@Suppress("unused")
object MPVLib {
    private val libraryLoadError: Throwable? by lazy {
        runCatching {
            System.loadLibrary("mpv")
            System.loadLibrary("player")
        }.exceptionOrNull()
    }

    val isAvailable: Boolean
        get() = libraryLoadError == null

    fun availabilityError(): Throwable? = libraryLoadError

    private fun ensureAvailable() {
        libraryLoadError?.let { throw it }
    }

    init {
        ensureAvailable()
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun command(cmd: Array<out String>)
    external fun setOptionString(name: String, value: String): Int
    external fun grabThumbnail(dimension: Int): Bitmap?
    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)
    external fun observeProperty(property: String, format: Int)

    @JvmStatic
    fun eventProperty(property: String, value: Long) {
        NexioMpvLib.eventProperty(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Boolean) {
        NexioMpvLib.eventProperty(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: Double) {
        NexioMpvLib.eventProperty(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String, value: String) {
        NexioMpvLib.eventProperty(property, value)
    }

    @JvmStatic
    fun eventProperty(property: String) {
        NexioMpvLib.eventProperty(property)
    }

    @JvmStatic
    fun event(eventId: Int) {
        NexioMpvLib.event(eventId)
    }

    @JvmStatic
    fun logMessage(prefix: String, level: Int, text: String) {
        NexioMpvLib.logMessage(prefix, level, text)
    }
}

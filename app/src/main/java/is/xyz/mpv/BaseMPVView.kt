package `is`.xyz.mpv

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import android.view.SurfaceHolder
import android.view.SurfaceView

// Minimal upstream mpv-android view lifecycle used to keep screen ownership identical.
abstract class BaseMPVView(
    context: Context,
    attrs: AttributeSet?
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    private var surfaceAttached = false

    fun initialize(configDir: String, cacheDir: String) {
        MPVLib.create(context)
        MPVLib.setOptionString("config", "yes")
        MPVLib.setOptionString("config-dir", configDir)
        for (option in arrayOf("gpu-shader-cache-dir", "icc-cache-dir")) {
            MPVLib.setOptionString(option, cacheDir)
        }
        initOptions()
        MPVLib.init()
        postInitOptions()
        MPVLib.setOptionString("force-window", "no")
        MPVLib.setOptionString("idle", "once")
        holder.addCallback(this)
        attachSurfaceIfReady()
        observeProperties()
    }

    fun destroy() {
        holder.removeCallback(this)
        MPVLib.destroy()
    }

    protected abstract fun initOptions()
    protected abstract fun postInitOptions()
    protected abstract fun observeProperties()

    private var filePath: String? = null
    private var voInUse: String = "gpu"

    fun playFile(filePath: String) {
        if (ensureSurfaceAttached()) {
            MPVLib.command(arrayOf("loadfile", filePath))
            this.filePath = null
        } else {
            this.filePath = filePath
        }
    }

    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        if (surfaceAttached) return
        Log.w(TAG, "attaching surface")
        surfaceAttached = true
        MPVLib.attachSurface(holder.surface)
        MPVLib.setOptionString("force-window", "yes")

        val queuedFile = filePath
        if (queuedFile != null) {
            MPVLib.command(arrayOf("loadfile", queuedFile))
            filePath = null
        } else {
            MPVLib.setPropertyString("vo", voInUse)
        }
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.w(TAG, "detaching surface")
        surfaceAttached = false
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    private fun ensureSurfaceAttached(): Boolean {
        if (surfaceAttached) return true
        return attachSurfaceIfReady()
    }

    private fun attachSurfaceIfReady(): Boolean {
        if (surfaceAttached) return true
        if (!holder.surface.isValid) return false
        Log.w(TAG, "surface already valid, attaching eagerly")
        surfaceCreated(holder)
        val width = width.takeIf { it > 0 } ?: holder.surfaceFrame.width()
        val height = height.takeIf { it > 0 } ?: holder.surfaceFrame.height()
        if (width > 0 && height > 0) {
            surfaceChanged(holder, 0, width, height)
        }
        return surfaceAttached
    }

    companion object {
        private const val TAG = "mpv"
    }
}

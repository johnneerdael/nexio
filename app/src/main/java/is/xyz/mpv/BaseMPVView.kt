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
        this.filePath = filePath
    }

    fun setVo(vo: String) {
        voInUse = vo
        MPVLib.setOptionString("vo", vo)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        MPVLib.setPropertyString("android-surface-size", "${width}x$height")
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.w(TAG, "attaching surface")
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
        MPVLib.setPropertyString("vo", "null")
        MPVLib.setPropertyString("force-window", "no")
        MPVLib.detachSurface()
    }

    companion object {
        private const val TAG = "mpv"
    }
}

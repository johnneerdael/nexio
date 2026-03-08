package com.nexio.tv.core.mpv

import android.content.Context
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import androidx.media3.ui.AspectRatioFrameLayout
import kotlin.math.roundToInt

class NexioMpvSurfaceView(context: Context) : FrameLayout(context), SurfaceHolder.Callback {
    private val surfaceView = SurfaceView(context)
    private var session: NexioMpvSession? = null
    private var surfaceAlive: Boolean = false
    private var resizeMode: Int = AspectRatioFrameLayout.RESIZE_MODE_FIT
    private var videoWidth: Int = 0
    private var videoHeight: Int = 0

    init {
        addView(
            surfaceView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
        )
        surfaceView.holder.addCallback(this)
    }

    fun bindSession(session: NexioMpvSession?) {
        val previousSession = this.session
        if (session != null) {
            this.session = session
            if (
                surfaceAlive &&
                session !== previousSession &&
                previousSession?.shouldRetainSurfaceBinding() != true
            ) {
                session.attachSurface(surfaceView.holder.surface)
                if (width > 0 && height > 0) {
                    session.setSurfaceSize(width, height)
                }
            }
            return
        }
        if (this.session?.shouldRetainSurfaceBinding() == true) {
            return
        }
        this.session = null
    }

    fun setResizeMode(mode: Int) {
        resizeMode = mode
        requestLayout()
    }

    fun setVideoSize(width: Int, height: Int) {
        if (videoWidth == width && videoHeight == height) return
        videoWidth = width
        videoHeight = height
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)
        val parentWidth = measuredWidth
        val parentHeight = measuredHeight
        if (videoWidth <= 0 || videoHeight <= 0 || parentWidth <= 0 || parentHeight <= 0) {
            surfaceView.layoutParams = LayoutParams(parentWidth, parentHeight)
            return
        }

        val videoAspect = videoWidth.toFloat() / videoHeight.toFloat()
        val parentAspect = parentWidth.toFloat() / parentHeight.toFloat()
        val layoutWidth: Int
        val layoutHeight: Int
        when (resizeMode) {
            AspectRatioFrameLayout.RESIZE_MODE_FILL -> {
                layoutWidth = parentWidth
                layoutHeight = parentHeight
            }
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM -> {
                if (videoAspect > parentAspect) {
                    layoutHeight = parentHeight
                    layoutWidth = (parentHeight * videoAspect).roundToInt()
                } else {
                    layoutWidth = parentWidth
                    layoutHeight = (parentWidth / videoAspect).roundToInt()
                }
            }
            else -> {
                if (videoAspect > parentAspect) {
                    layoutWidth = parentWidth
                    layoutHeight = (parentWidth / videoAspect).roundToInt()
                } else {
                    layoutHeight = parentHeight
                    layoutWidth = (parentHeight * videoAspect).roundToInt()
                }
            }
        }
        surfaceView.layoutParams = LayoutParams(layoutWidth, layoutHeight).apply {
            leftMargin = ((parentWidth - layoutWidth) / 2).coerceAtLeast(0)
            topMargin = ((parentHeight - layoutHeight) / 2).coerceAtLeast(0)
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceAlive = true
        session?.attachSurface(holder.surface)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceAlive = false
        session?.detachSurface()
        if (session?.shouldRetainSurfaceBinding() != true) {
            session = null
        }
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        session?.setSurfaceSize(width, height)
    }

    override fun onDetachedFromWindow() {
        surfaceView.holder.removeCallback(this)
        if (surfaceAlive) {
            surfaceAlive = false
            session?.detachSurface()
        }
        if (session?.shouldRetainSurfaceBinding() != true) {
            session = null
        }
        super.onDetachedFromWindow()
    }
}

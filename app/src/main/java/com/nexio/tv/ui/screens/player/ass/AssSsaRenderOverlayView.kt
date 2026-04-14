package com.nexio.tv.ui.screens.player.ass

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

internal class AssSsaRenderOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {
    private val displayCanvas = Canvas()
    private val sourceRect = Rect()
    private val destinationRect = RectF()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)
    private var displayBitmap: Bitmap? = null
    private var hasRenderedBitmap = false

    init {
        setWillNotDraw(false)
        setLayerType(LAYER_TYPE_SOFTWARE, null)
    }

    fun setRenderedBitmap(source: Bitmap) {
        val bitmap = displayBitmapFor(source)
        displayCanvas.setBitmap(bitmap)
        displayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        displayCanvas.drawBitmap(source, 0f, 0f, null)
        displayCanvas.setBitmap(null)
        hasRenderedBitmap = true
        invalidate()
    }

    fun clearOverlay() {
        displayBitmap?.let { bitmap ->
            displayCanvas.setBitmap(bitmap)
            displayCanvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            displayCanvas.setBitmap(null)
        }
        hasRenderedBitmap = false
        invalidate()
    }

    internal fun hasRenderedBitmapForTesting(): Boolean = hasRenderedBitmap

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = displayBitmap
        if (!hasRenderedBitmap || bitmap == null || width <= 0 || height <= 0) return

        sourceRect.set(0, 0, bitmap.width, bitmap.height)
        destinationRect.set(0f, 0f, width.toFloat(), height.toFloat())
        canvas.drawBitmap(bitmap, sourceRect, destinationRect, paint)
    }

    private fun displayBitmapFor(source: Bitmap): Bitmap {
        val existing = displayBitmap
        if (existing != null && existing.width == source.width && existing.height == source.height) {
            return existing
        }
        return Bitmap.createBitmap(source.width, source.height, Bitmap.Config.ARGB_8888).also {
            displayBitmap?.recycle()
            displayBitmap = it
        }
    }

    override fun onDetachedFromWindow() {
        displayBitmap?.recycle()
        displayBitmap = null
        hasRenderedBitmap = false
        super.onDetachedFromWindow()
    }
}

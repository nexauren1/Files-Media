package com.nexauren1.filesmedia

import android.content.Context
import android.graphics.Matrix
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView
import kotlin.math.min

/**
 * Lightweight PDF page view with pinch-to-zoom and panning.
 * It uses the platform ImageView and Matrix APIs to avoid a large viewer dependency.
 */
class ZoomablePdfImageView(context: Context) : ImageView(context) {
    private val displayMatrix = Matrix()
    private val scaleDetector = ScaleGestureDetector(context, ScaleListener())
    private var baseScale = 1f
    private var currentScale = 1f
    private var lastX = 0f
    private var lastY = 0f
    private var moved = false

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
        contentDescription = "Página PDF ampliável"
    }

    override fun setImageBitmap(bitmap: android.graphics.Bitmap?) {
        super.setImageBitmap(bitmap)
        post { fitPage() }
    }

    fun fitPage() {
        val drawable = drawable ?: return
        if (width <= 0 || height <= 0 || drawable.intrinsicWidth <= 0 || drawable.intrinsicHeight <= 0) return
        baseScale = min(
            width.toFloat() / drawable.intrinsicWidth.toFloat(),
            height.toFloat() / drawable.intrinsicHeight.toFloat()
        ).coerceAtLeast(0.01f)
        currentScale = baseScale
        val scaledWidth = drawable.intrinsicWidth * baseScale
        val scaledHeight = drawable.intrinsicHeight * baseScale
        displayMatrix.setScale(baseScale, baseScale)
        displayMatrix.postTranslate((width - scaledWidth) / 2f, (height - scaledHeight) / 2f)
        imageMatrix = displayMatrix
        invalidate()
    }

    fun zoomBy(factor: Float) {
        val nextScale = (currentScale * factor).coerceIn(baseScale, baseScale * 5f)
        val appliedFactor = nextScale / currentScale
        displayMatrix.postScale(appliedFactor, appliedFactor, width / 2f, height / 2f)
        currentScale = nextScale
        imageMatrix = displayMatrix
        invalidate()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastX = event.x
                lastY = event.y
                moved = false
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (!scaleDetector.isInProgress) {
                    val dx = event.x - lastX
                    val dy = event.y - lastY
                    if (kotlin.math.abs(dx) > 2f || kotlin.math.abs(dy) > 2f) moved = true
                    if (currentScale > baseScale * 1.01f) {
                        displayMatrix.postTranslate(dx, dy)
                        imageMatrix = displayMatrix
                    }
                    lastX = event.x
                    lastY = event.y
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (!moved && !scaleDetector.isInProgress) performClick()
                return true
            }
            MotionEvent.ACTION_CANCEL -> return true
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private inner class ScaleListener : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val nextScale = (currentScale * detector.scaleFactor).coerceIn(baseScale, baseScale * 5f)
            val factor = nextScale / currentScale
            displayMatrix.postScale(factor, factor, detector.focusX, detector.focusY)
            currentScale = nextScale
            imageMatrix = displayMatrix
            invalidate()
            return true
        }
    }
}

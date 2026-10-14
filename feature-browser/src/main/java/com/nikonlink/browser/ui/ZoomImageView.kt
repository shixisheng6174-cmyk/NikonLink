package com.nikonlink.browser.ui

import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.ImageView

class ZoomImageView(context: Context) : ImageView(context) {
    private var zoom = 1f
    private var lastX = 0f
    private var lastY = 0f
    private val transform = Matrix()
    private val scale = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val next = (zoom * detector.scaleFactor).coerceIn(1f, 8f)
            transform.postScale(next / zoom, next / zoom, detector.focusX, detector.focusY)
            zoom = next
            imageMatrix = transform
            return true
        }
    })
    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { fit(); return true }
    })
    init { scaleType = ScaleType.MATRIX; contentDescription = "影像预览，可双指缩放" }
    override fun setImageDrawable(drawable: Drawable?) { super.setImageDrawable(drawable); post { fit() } }
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) { super.onSizeChanged(w, h, oldw, oldh); fit() }
    private fun fit() {
        val d = drawable ?: return
        if (width == 0 || height == 0 || d.intrinsicWidth <= 0 || d.intrinsicHeight <= 0) return
        val ratio = minOf(width.toFloat() / d.intrinsicWidth, height.toFloat() / d.intrinsicHeight)
        transform.reset()
        transform.postScale(ratio, ratio)
        transform.postTranslate((width - d.intrinsicWidth * ratio) / 2, (height - d.intrinsicHeight * ratio) / 2)
        zoom = 1f
        imageMatrix = transform
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        scale.onTouchEvent(event)
        gestures.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (!scale.isInProgress && event.pointerCount == 1 && zoom > 1) {
                transform.postTranslate(event.x - lastX, event.y - lastY); imageMatrix = transform
            }
            MotionEvent.ACTION_UP -> performClick()
        }
        lastX = event.x; lastY = event.y
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

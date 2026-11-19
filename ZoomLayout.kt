package id.hendrik.pdfttd

import android.content.Context
import android.graphics.Canvas
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.widget.FrameLayout
import kotlin.math.max
import kotlin.math.min

/**
 * FrameLayout yang mendukung cubit (pinch) untuk zoom dan geser (pan) saat di-zoom.
 * Anak tunggal (biasanya RecyclerView) di-scale & di-translate.
 */
class ZoomLayout(ctx: Context) : FrameLayout(ctx) {

    private var scale = 1f
    private var minScale = 1f
    private var maxScale = 5f
    private var transX = 0f
    private var transY = 0f
    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var isScaling = false

    private val scaleDetector = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
            isScaling = true
            lastFocusX = detector.focusX
            lastFocusY = detector.focusY
            return true
        }

        override fun onScale(detector: ScaleGestureDetector): Boolean {
            val oldScale = scale
            scale = (scale * detector.scaleFactor).coerceIn(minScale, maxScale)
            val focusX = detector.focusX
            val focusY = detector.focusY
            // Zoom around focus point
            transX = focusX - (focusX - transX) * (scale / oldScale)
            transY = focusY - (focusY - transY) * (scale / oldScale)
            clampTranslation()
            applyTransform()
            lastFocusX = focusX
            lastFocusY = focusY
            return true
        }

        override fun onScaleEnd(detector: ScaleGestureDetector) {
            isScaling = false
            if (scale < 1.05f) {
                // Snap back to fit
                scale = 1f
                transX = 0f
                transY = 0f
                applyTransform()
            }
        }
    })

    private val gestureDetector = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > 1.2f) {
                scale = 1f
                transX = 0f
                transY = 0f
            } else {
                scale = 2.5f
                transX = e.x - (e.x - transX) * (scale / 1f)
                transY = e.y - (e.y - transY) * (scale / 1f)
                clampTranslation()
            }
            applyTransform()
            return true
        }

        override fun onScroll(
            e1: MotionEvent?,
            e2: MotionEvent,
            distanceX: Float,
            distanceY: Float
        ): Boolean {
            if (scale <= 1.01f || isScaling) return false
            transX -= distanceX
            transY -= distanceY
            clampTranslation()
            applyTransform()
            return true
        }
    })

    private fun clampTranslation() {
        val child = getChildAt(0) ?: return
        val contentW = child.width * scale
        val contentH = child.height * scale
        val viewW = width.toFloat()
        val viewH = height.toFloat()

        if (contentW <= viewW) {
            transX = (viewW - contentW) / 2f
        } else {
            val minTx = viewW - contentW
            transX = transX.coerceIn(minTx, 0f)
        }
        if (contentH <= viewH) {
            transY = (viewH - contentH) / 2f
        } else {
            val minTy = viewH - contentH
            transY = transY.coerceIn(minTy, 0f)
        }
    }

    private fun applyTransform() {
        val child = getChildAt(0) ?: return
        child.pivotX = 0f
        child.pivotY = 0f
        child.scaleX = scale
        child.scaleY = scale
        child.translationX = transX
        child.translationY = transY
    }

    /** Reset zoom ke 1x (dipanggil saat buka PDF baru / rotate). */
    fun resetZoom() {
        scale = 1f
        transX = 0f
        transY = 0f
        applyTransform()
    }

    fun getCurrentScale(): Float = scale

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(ev)
        // Intercept multi-touch for zoom; let single-finger scroll go to RecyclerView when scale≈1
        if (ev.pointerCount > 1) return true
        if (scale > 1.05f) {
            // When zoomed, we may pan ourselves
            return true
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        var handled = scaleDetector.onTouchEvent(event)
        handled = gestureDetector.onTouchEvent(event) || handled
        if (event.pointerCount > 1) return true
        if (scale > 1.05f) {
            // Consume so parent doesn't steal
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> return true
            }
        }
        return handled || super.onTouchEvent(event)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        clampTranslation()
        applyTransform()
    }

    override fun dispatchDraw(canvas: Canvas) {
        // Ensure children drawn with current transform
        super.dispatchDraw(canvas)
    }

    override fun addView(child: View?) {
        super.addView(child)
        // Only one main child expected
        post { applyTransform() }
    }
}

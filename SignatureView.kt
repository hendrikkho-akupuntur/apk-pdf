package id.hendrik.pdfttd

import android.content.Context
import android.graphics.Bitmap
import android.graphics.drawable.GradientDrawable
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import kotlin.math.max

/** Tanda tangan yang bisa digeser (1 jari), diperbesar/kecilkan (cubit), dihapus (tekan lama). */
class SignatureView(
    ctx: Context,
    bmp: Bitmap,
    private val p: Placement,
    private val pageW: Int,
    private val pageH: Int,
    private val onLongPressed: (SignatureView) -> Unit
) : ImageView(ctx) {

    private val aspect = bmp.height.toFloat() / bmp.width
    private var lastX = 0f
    private var lastY = 0f
    private var resync = false

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val oldW = p.w * pageW
            val newW = (oldW * d.scaleFactor).coerceIn(pageW * 0.08f, pageW * 1f)
            val cx = x + oldW / 2
            val cy = y + oldW * aspect / 2
            applyGeometry(cx - newW / 2, cy - newW * aspect / 2, newW)
            return true
        }
    })

    private val gesture = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onLongPress(e: MotionEvent) {
            onLongPressed(this@SignatureView)
        }
    })

    init {
        setImageBitmap(bmp)
        scaleType = ScaleType.FIT_XY
        background = GradientDrawable().apply { setStroke(3, 0xAA1E88E5.toInt()) }
        val wPx = (p.w * pageW).toInt()
        layoutParams = FrameLayout.LayoutParams(wPx, (wPx * aspect).toInt())
        x = p.x * pageW
        y = p.y * pageH
    }

    private fun applyGeometry(nx: Float, ny: Float, nw: Float) {
        val nh = nw * aspect
        val cx = nx.coerceIn(0f, max(0f, pageW - nw))
        val cy = ny.coerceIn(0f, max(0f, pageH - nh))
        val lp = layoutParams
        lp.width = nw.toInt()
        lp.height = nh.toInt()
        layoutParams = lp
        x = cx
        y = cy
        p.x = cx / pageW
        p.y = cy / pageH
        p.w = nw / pageW
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        gesture.onTouchEvent(e)
        scaleDet.onTouchEvent(e)
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent.requestDisallowInterceptTouchEvent(true)
                lastX = e.rawX
                lastY = e.rawY
                resync = false
            }
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> resync = true
            MotionEvent.ACTION_MOVE -> if (e.pointerCount == 1 && !scaleDet.isInProgress) {
                if (resync) {
                    lastX = e.rawX; lastY = e.rawY; resync = false
                } else {
                    applyGeometry(x + e.rawX - lastX, y + e.rawY - lastY, p.w * pageW)
                    lastX = e.rawX; lastY = e.rawY
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                parent.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }
}

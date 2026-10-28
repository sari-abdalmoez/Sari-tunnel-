package africa.saritunnel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/** Round connect button: split dark/light disc with rings, an amber spinner while busy, blue when connected. */
class ConnectButtonView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    enum class Mode { IDLE, BUSY, CONNECTED }

    var mode: Mode = Mode.IDLE
        set(value) {
            if (field == value) return
            field = value
            updateAnimation()
            invalidate()
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.parseColor("#F5A524")
    }
    private val arcBounds = RectF()
    private var sweepStart = 0f
    private var animator: ValueAnimator? = null

    init {
        isClickable = true
        isFocusable = true
    }

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun dispatchSetPressed(pressed: Boolean) {
        super.dispatchSetPressed(pressed)
        val s = if (pressed) 0.96f else 1f
        scaleX = s
        scaleY = s
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f - dp(12f)
        if (r <= 0f) return

        val top: Int
        val bottom: Int
        val rings: IntArray
        when (mode) {
            Mode.CONNECTED -> {
                top = Color.parseColor("#2B9AF0"); bottom = Color.parseColor("#0B3D75")
                rings = intArrayOf(Color.parseColor("#D6ECFF"), Color.parseColor("#B1D9FA"), Color.parseColor("#86C2F3"), Color.parseColor("#2E86D8"))
            }
            else -> {
                top = Color.parseColor("#8C8C8E"); bottom = Color.parseColor("#2E2F33")
                rings = intArrayOf(Color.parseColor("#D4D2D2"), Color.parseColor("#C0BEBE"), Color.parseColor("#A9A8A8"), Color.parseColor("#6E6E70"))
            }
        }

        fill.shader = null
        fill.color = Color.argb(28, 0, 0, 0)
        canvas.drawCircle(cx, cy + dp(5f), r, fill)

        fill.shader = LinearGradient(cx, cy - r, cx, cy + r, top, bottom, Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, r, fill)
        fill.shader = null

        canvas.save()
        canvas.clipRect(0f, 0f, cx, height.toFloat())
        val factors = floatArrayOf(0.90f, 0.68f, 0.46f, 0.22f)
        for (i in factors.indices) {
            fill.color = rings[i]
            canvas.drawCircle(cx, cy, r * factors[i], fill)
        }
        canvas.restore()

        if (mode == Mode.BUSY) {
            arc.strokeWidth = dp(5f)
            val pad = dp(6f)
            arcBounds.set(cx - r - pad, cy - r - pad, cx + r + pad, cy + r + pad)
            canvas.drawArc(arcBounds, sweepStart, 110f, false, arc)
        }
    }

    private fun updateAnimation() {
        animator?.cancel()
        animator = null
        if (mode == Mode.BUSY && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(0f, 360f).apply {
                duration = 1100
                repeatCount = ValueAnimator.INFINITE
                interpolator = LinearInterpolator()
                addUpdateListener { sweepStart = it.animatedValue as Float; invalidate() }
                start()
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateAnimation()
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        animator = null
        super.onDetachedFromWindow()
    }
}

package africa.saritunnel

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * Power button: a thick ring around a round face with a power glyph.
 * Idle = slate, Connected = teal glow, Busy = amber sweep. Deliberately not a split half-disc.
 */
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
    private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()
    private var sweepStart = -90f
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
        val outer = min(width, height) / 2f - dp(14f)
        if (outer <= 0f) return
        val stroke = dp(10f)
        val faceR = outer - stroke / 2f - dp(6f)

        val connected = mode == Mode.CONNECTED
        val ringColor = when (mode) {
            Mode.CONNECTED -> Color.parseColor("#14B8A6")
            Mode.BUSY -> Color.parseColor("#D0D5DD")
            Mode.IDLE -> Color.parseColor("#D0D5DD")
        }
        val glyphColor = if (connected) Color.parseColor("#0E9F8E") else Color.parseColor("#344054")

        // Soft glow when connected
        if (connected) {
            fill.shader = RadialGradient(cx, cy, outer + dp(18f),
                Color.argb(70, 20, 184, 166), Color.TRANSPARENT, Shader.TileMode.CLAMP)
            canvas.drawCircle(cx, cy, outer + dp(18f), fill)
            fill.shader = null
        }

        // Ring
        ring.strokeWidth = stroke
        ring.color = ringColor
        canvas.drawCircle(cx, cy, outer - stroke / 2f, ring)

        // Face
        fill.shader = if (connected)
            LinearGradient(cx, cy - faceR, cx, cy + faceR, Color.parseColor("#F0FDFA"), Color.parseColor("#CCFBF1"), Shader.TileMode.CLAMP)
        else
            LinearGradient(cx, cy - faceR, cx, cy + faceR, Color.parseColor("#FFFFFF"), Color.parseColor("#E4E7EC"), Shader.TileMode.CLAMP)
        canvas.drawCircle(cx, cy, faceR, fill)
        fill.shader = null

        // Power glyph: arc with a gap at the top, plus a short vertical bar
        glyph.strokeWidth = dp(7f)
        glyph.color = glyphColor
        val gr = faceR * 0.42f
        rect.set(cx - gr, cy - gr, cx + gr, cy + gr)
        canvas.drawArc(rect, -50f, 280f, false, glyph)
        canvas.drawLine(cx, cy - gr * 1.15f, cx, cy - gr * 0.05f, glyph)

        // Busy: amber sweep around the outside
        if (mode == Mode.BUSY) {
            ring.strokeWidth = stroke
            ring.color = Color.parseColor("#F5A524")
            rect.set(cx - outer + stroke / 2f, cy - outer + stroke / 2f, cx + outer - stroke / 2f, cy + outer - stroke / 2f)
            canvas.drawArc(rect, sweepStart, 110f, false, ring)
        }
    }

    private fun updateAnimation() {
        animator?.cancel()
        animator = null
        if (mode == Mode.BUSY && isAttachedToWindow) {
            animator = ValueAnimator.ofFloat(-90f, 270f).apply {
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

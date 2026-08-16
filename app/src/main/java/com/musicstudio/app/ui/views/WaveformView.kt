package com.musicstudio.app.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import androidx.core.content.ContextCompat
import com.musicstudio.app.R
import kotlin.math.min

/**
 * Real-time voice amplitude waveform visualiser.
 *
 * Feed amplitude (0-1) via [addAmplitude] at ~50Hz and the view
 * scrolls a bar graph from right to left, like a classic vocal meter.
 */
class WaveformView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyle: Int = 0
) : View(context, attrs, defStyle) {

    // ── Visual config ──────────────────────────────────────────────────
    private val BAR_WIDTH_DP = 4f
    private val BAR_GAP_DP  = 2f
    private val CORNER_DP   = 2f

    private val barWidth   = dp(BAR_WIDTH_DP)
    private val barGap     = dp(BAR_GAP_DP)
    private val cornerRad  = dp(CORNER_DP)

    // History of amplitude values (newest at end)
    private val amplitudes = ArrayDeque<Float>(MAX_BARS)

    // Active / idle gradient colours
    private val colorActive  = Color.parseColor("#BB86FC")   // purple accent
    private val colorPeak    = Color.parseColor("#FF6B6B")   // red for loud
    private val colorIdle    = Color.parseColor("#3D3D5C")   // dark muted

    private val bgColor = Color.parseColor("#12121E")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect  = RectF()

    // Gradient shader (rebuilt when size changes)
    private var gradientShader: LinearGradient? = null

    // ── Public API ──────────────────────────────────────────────────────

    /** Call this every ~20ms with an RMS value in [0, 1]. */
    fun addAmplitude(rms: Float) {
        val clamped = rms.coerceIn(0f, 1f)
        if (amplitudes.size >= MAX_BARS) amplitudes.removeFirst()
        amplitudes.addLast(clamped)
        invalidate()
    }

    fun clear() {
        amplitudes.clear()
        invalidate()
    }

    // ── Drawing ─────────────────────────────────────────────────────────

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        gradientShader = LinearGradient(
            0f, h.toFloat(), 0f, 0f,
            intArrayOf(colorActive, colorActive, colorPeak),
            floatArrayOf(0f, 0.7f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(bgColor)
        if (width == 0 || height == 0) return

        val step   = barWidth + barGap
        val maxBars = (width / step).toInt()
        val cx     = width.toFloat()
        val cy     = height.toFloat()
        val shader = gradientShader

        var i = amplitudes.size - 1
        var x = cx - barWidth

        while (x >= 0 && i >= 0) {
            val amp = amplitudes[i]
            val barH = (amp * cy * 0.9f).coerceAtLeast(dp(2f))
            val top  = cy / 2f - barH / 2f
            val bot  = cy / 2f + barH / 2f

            paint.shader = if (amp > 0.01f) shader else null
            paint.color  = if (amp > 0.01f) Color.WHITE else colorIdle

            rect.set(x, top, x + barWidth, bot)
            canvas.drawRoundRect(rect, cornerRad, cornerRad, paint)

            x -= step
            i--
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun dp(v: Float) = v * context.resources.displayMetrics.density

    companion object {
        private const val MAX_BARS = 200
    }
}

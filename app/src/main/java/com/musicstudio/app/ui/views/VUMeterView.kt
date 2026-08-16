package com.musicstudio.app.ui.views

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

/**
 * Classic vertical VU meter with green/yellow/red segments.
 * Feed [setLevel] with a 0–1 RMS value.
 */
class VUMeterView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val SEGMENTS     = 20
    private val GAP_DP       = 2f
    private val CORNER_DP    = 2f

    private val gap    = dp(GAP_DP)
    private val corner = dp(CORNER_DP)

    private var level    = 0f   // 0.0 – 1.0
    private var peakHold = 0f
    private var peakAge  = 0

    private val activeColors = Array(SEGMENTS) { i ->
        when {
            i < SEGMENTS * 0.6 -> Color.parseColor("#4CAF50")  // green
            i < SEGMENTS * 0.85-> Color.parseColor("#FFC107")  // yellow
            else               -> Color.parseColor("#F44336")  // red
        }
    }
    private val dimColor  = Color.parseColor("#2A2A3C")
    private val peakColor = Color.parseColor("#FFFFFF")

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect  = RectF()

    fun setLevel(rms: Float) {
        level = rms.coerceIn(0f, 1f)
        if (level >= peakHold) { peakHold = level; peakAge = 0 }
        else if (++peakAge > 30) peakHold = (peakHold - 0.02f).coerceAtLeast(0f)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawColor(Color.parseColor("#12121E"))

        val segH   = (height - gap * (SEGMENTS + 1)) / SEGMENTS
        val active = (level * SEGMENTS).toInt()
        val peak   = (peakHold * SEGMENTS).toInt().coerceIn(0, SEGMENTS - 1)

        for (i in 0 until SEGMENTS) {
            val row = SEGMENTS - 1 - i  // 0 = bottom
            val top = gap + i * (segH + gap)
            rect.set(gap, top, width - gap, top + segH)
            paint.color = when {
                row <= active        -> activeColors[row]
                row == peak && peak > 0 -> peakColor
                else                 -> dimColor
            }
            canvas.drawRoundRect(rect, corner, corner, paint)
        }
    }

    private fun dp(v: Float) = v * context.resources.displayMetrics.density
}

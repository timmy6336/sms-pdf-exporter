package com.personal.smspdfexport

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * A minimal, dependency-free stacked bar chart: one bar per day, segments
 * colored per sender so the combined daily volume and its per-person split
 * are both visible at a glance. No charting library needed for this.
 */
class DailyMessageBarChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    companion object {
        val PALETTE = intArrayOf(
            Color.parseColor("#2E5BFF"),
            Color.parseColor("#FF6B6B"),
            Color.parseColor("#FFA726"),
            Color.parseColor("#26A69A"),
            Color.parseColor("#AB47BC")
        )
    }

    private var days: List<LocalDate> = emptyList()
    private var dailyCounts: List<Map<String, Int>> = emptyList()
    private var senderOrder: List<String> = emptyList()

    private val barPaint = Paint().apply { isAntiAlias = true }
    private val axisPaint = Paint().apply {
        isAntiAlias = true
        color = Color.parseColor("#999999")
        textSize = 11f * resources.displayMetrics.scaledDensity
    }
    private val dayLabelFormatter = DateTimeFormatter.ofPattern("d")

    fun setData(days: List<LocalDate>, dailyCounts: List<Map<String, Int>>, senderOrder: List<String>) {
        this.days = days
        this.dailyCounts = dailyCounts
        this.senderOrder = senderOrder
        invalidate()
    }

    fun colorForIndex(index: Int): Int = PALETTE[index % PALETTE.size]

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = View.MeasureSpec.getSize(widthMeasureSpec)
        val height = (160 * resources.displayMetrics.density).toInt()
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (days.isEmpty()) return

        val labelSpace = 28f * resources.displayMetrics.density
        val chartHeight = height - labelSpace
        val maxTotal = dailyCounts.maxOfOrNull { it.values.sum() } ?: 0
        if (maxTotal <= 0) return

        val slot = width.toFloat() / days.size
        val barWidth = (slot * 0.6f).coerceAtLeast(1f)
        val sideGap = (slot - barWidth) / 2f

        // Thin out day-of-month labels once there isn't room for all of them.
        val labelStride = (days.size / 10).coerceAtLeast(1)

        for ((i, day) in days.withIndex()) {
            val counts = dailyCounts.getOrNull(i) ?: emptyMap()
            val x = i * slot + sideGap
            var yCursor = chartHeight

            for ((senderIndex, key) in senderOrder.withIndex()) {
                val count = counts[key] ?: 0
                if (count <= 0) continue
                val segmentHeight = chartHeight * (count.toFloat() / maxTotal)
                barPaint.color = colorForIndex(senderIndex)
                canvas.drawRect(x, yCursor - segmentHeight, x + barWidth, yCursor, barPaint)
                yCursor -= segmentHeight
            }

            if (i % labelStride == 0) {
                val label = day.format(dayLabelFormatter)
                val textWidth = axisPaint.measureText(label)
                canvas.drawText(label, x + barWidth / 2f - textWidth / 2f, height.toFloat() - 4f, axisPaint)
            }
        }
    }
}

package com.damn.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class TrafficSparkView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val inPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8"); strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val outPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8"); strokeWidth = 3f; style = Paint.Style.STROKE; isAntiAlias = true
    }
    private val inFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#38BDF8"); style = Paint.Style.FILL; alpha = 30
    }
    private val outFill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#818CF8"); style = Paint.Style.FILL; alpha = 30
    }

    private var inHistory: List<Int> = emptyList()
    private var outHistory: List<Int> = emptyList()

    fun setData(inH: List<Int>, outH: List<Int>) {
        inHistory = inH
        outHistory = outH
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (inHistory.size >= 2) drawSpark(canvas, inHistory, inPaint, inFill, w, h)
        if (outHistory.size >= 2) drawSpark(canvas, outHistory, outPaint, outFill, w, h)
    }

    private fun drawSpark(canvas: Canvas, data: List<Int>, stroke: Paint, fill: Paint, w: Float, h: Float) {
        val max = data.maxOrNull()?.coerceAtLeast(1) ?: 1
        val path = Path()
        val fillPath = Path()
        data.forEachIndexed { i, v ->
            val x = (i / 29f) * w
            val y = h - (v / max.toFloat()) * (h - 6) - 3
            if (i == 0) { path.moveTo(x, y); fillPath.moveTo(x, y) } else { path.lineTo(x, y); fillPath.lineTo(x, y) }
        }
        fillPath.lineTo(w, h)
        fillPath.lineTo(0f, h)
        fillPath.close()
        canvas.drawPath(fillPath, fill)
        canvas.drawPath(path, stroke)
    }
}

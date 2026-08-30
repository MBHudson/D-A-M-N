package com.damn.app.ui

import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View

class PingChartView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#1E293B")
        strokeWidth = 1f
        style = Paint.Style.STROKE
        alpha = 80
    }
    private val paints = mapOf(
        "nat" to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#22C55E"); strokeWidth = 4f; style = Paint.Style.STROKE; isAntiAlias = true },
        "tor" to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#A78BFA"); strokeWidth = 4f; style = Paint.Style.STROKE },
        "ngrok" to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#38BDF8"); strokeWidth = 4f; style = Paint.Style.STROKE },
        "cf" to Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#F59E0B"); strokeWidth = 4f; style = Paint.Style.STROKE }
    )
    private var data: Map<String, List<Int>> = emptyMap()
    private var maxPing: Int = 250

    fun setData(hist: Map<String, List<Int>>) {
        data = hist
        maxPing = 250
        hist.values.forEach { list -> list.forEach { v -> if (v > maxPing) maxPing = v } }
        maxPing = maxOf(250, maxPing)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        // grid 4 lines
        for (i in 0..4) {
            val y = (i / 4f) * h
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (data.isEmpty()) return
        data.forEach { (key, list) ->
            if (list.size < 2) return@forEach
            val paint = paints[key] ?: return@forEach
            val path = Path()
            list.forEachIndexed { idx, v ->
                val x = (idx / 29f) * w
                val y = h - (minOf(v, maxPing) / maxPing.toFloat()) * h
                if (idx == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            canvas.drawPath(path, paint)
        }
    }
}

package com.damn.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

class SPathView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = Color.parseColor("#38BDF8")
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = DashPathEffect(floatArrayOf(14f, 10f), 0f)
        setShadowLayer(8f, 0f, 0f, Color.parseColor("#38BDF8"))
    }
    private val path = Path()
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var firewallColor = "green"

    fun setFirewallColor(c: String) {
        firewallColor = c
        paint.color = when (c) {
            "red" -> Color.parseColor("#EF4444")
            "yellow" -> Color.parseColor("#F59E0B")
            "purple" -> Color.parseColor("#A78BFA")
            else -> Color.parseColor("#38BDF8")
        }
        paint.setShadowLayer(8f, 0f, 0f, paint.color)
        invalidate()
    }

    fun updatePath(
        startX: Float, startY: Float,
        fwTopX: Float, fwTopY: Float,
        fwBottomX: Float, fwBottomY: Float,
        endX: Float, endY: Float
    ) {
        path.reset()
        // Backwards S: start -> fwTop via cubic, then vertical through firewall, then fwBottom -> end
        path.moveTo(startX, startY)
        // First curve: start to fwTop
        val c1x1 = startX + 80f
        val c1y1 = startY + 90f
        val c1x2 = fwTopX + 70f
        val c1y2 = fwTopY - 60f
        path.cubicTo(c1x1, c1y1, c1x2, c1y2, fwTopX, fwTopY)
        path.lineTo(fwBottomX, fwBottomY)
        val c2x1 = fwBottomX - 80f
        val c2y1 = fwBottomY + 70f
        val c2x2 = endX - 60f
        val c2y2 = endY - 60f
        path.cubicTo(c2x1, c2y1, c2x2, c2y2, endX, endY)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 24f).apply {
            duration = 700
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                paint.pathEffect = DashPathEffect(floatArrayOf(14f, 10f), phase)
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        animator?.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // clip to not draw outside?
        canvas.drawPath(path, paint)
    }
}

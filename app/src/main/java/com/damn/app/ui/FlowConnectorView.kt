package com.damn.app.ui

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.*
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator

class FlowConnectorView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    private var color: Int = Color.parseColor("#38BDF8")
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 4f
        color = this@FlowConnectorView.color
        strokeCap = Paint.Cap.ROUND
        pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
    }
    private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = this@FlowConnectorView.color
    }
    private var phase = 0f
    private var animator: ValueAnimator? = null
    private var isAnimating = true

    fun setAnimating(anim: Boolean) {
        isAnimating = anim
        if (anim) {
            if (animator?.isStarted != true) animator?.start()
        } else {
            animator?.cancel()
            phase = 0f
            paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), 0f)
            invalidate()
        }
    }

    fun setColor(c: Int) {
        color = c
        paint.color = c
        arrowPaint.color = c
        paint.setShadowLayer(6f, 0f, 0f, c)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 18f).apply {
            duration = 600
            interpolator = LinearInterpolator()
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                phase = it.animatedValue as Float
                paint.pathEffect = DashPathEffect(floatArrayOf(10f, 8f), phase)
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
        val w = width.toFloat()
        val h = height.toFloat()
        val cy = h / 2f
        val pad = 10f
        // line
        val path = Path().apply {
            moveTo(pad, cy)
            lineTo(w - pad - 8f, cy)
        }
        canvas.drawPath(path, paint)
        // arrow head at end flowing direction right
        val ax = w - pad
        val arrowPath = Path().apply {
            moveTo(ax, cy)
            lineTo(ax - 8f, cy - 5f)
            lineTo(ax - 8f, cy + 5f)
            close()
        }
        canvas.drawPath(arrowPath, arrowPaint)
    }
}

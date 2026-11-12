package com.tvblplayer.ext.ui

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View

/**
 * 虚拟指针。在指针模式下显示，位置由 D-pad 控制。
 * 用 Canvas 直接画，避免额外的 drawable 资源。
 */
class CursorView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = Color.WHITE
        setShadowLayer(6f, 0f, 0f, Color.BLACK)
    }

    private val radius = 14f * resources.displayMetrics.density
    private val dotRadius = 3f * resources.displayMetrics.density

    var cx: Float = 0f
        private set
    var cy: Float = 0f
        private set

    init {
        setLayerType(LAYER_TYPE_SOFTWARE, null) // setShadowLayer 需要软件层
        isClickable = false
        isFocusable = false
    }

    fun moveTo(x: Float, y: Float) {
        cx = x
        cy = y
        invalidate()
    }

    fun centerIn(width: Int, height: Int) {
        if (width > 0 && height > 0) moveTo(width / 2f, height / 2f)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(cx, cy, radius, ringPaint)
        canvas.drawCircle(cx, cy, dotRadius, dotPaint)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: android.view.MotionEvent?): Boolean = false
}

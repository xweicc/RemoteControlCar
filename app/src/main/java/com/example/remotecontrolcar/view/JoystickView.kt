package com.example.remotecontrolcar.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.sqrt

enum class JoystickOrientation { VERTICAL, HORIZONTAL }

class JoystickView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    var orientation = JoystickOrientation.VERTICAL
    var onPositionChanged: ((x: Float, y: Float) -> Unit)? = null

    /**
     * 外部设置摇杆位置（如手柄输入），更新 UI 并触发回调
     * @param x 水平方向 -1~1（正为右）
     * @param y 垂直方向 -1~1（正为上）
     */
    fun setPosition(x: Float, y: Float) {
        when (orientation) {
            JoystickOrientation.VERTICAL -> {
                thumbX = centerX
                thumbY = centerY - y * radius
            }
            JoystickOrientation.HORIZONTAL -> {
                thumbX = centerX + x * radius
                thumbY = centerY
            }
        }
        invalidate()
    }

    private var centerX = 0f
    private var centerY = 0f
    private var radius = 0f
    private var thumbRadius = 0f
    private var thumbX = 0f
    private var thumbY = 0f
    private var trackingPointerId = -1

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(80, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val thumbPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(180, 255, 255, 255)
        style = Paint.Style.FILL
    }
    private val axisPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(50, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 4f
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        centerX = w / 2f
        centerY = h / 2f
        radius = minOf(w, h) / 2f * 0.8f
        thumbRadius = radius * 0.35f
        thumbX = centerX
        thumbY = centerY
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.drawCircle(centerX, centerY, radius, basePaint)
        when (orientation) {
            JoystickOrientation.VERTICAL ->
                canvas.drawLine(centerX, centerY - radius, centerX, centerY + radius, axisPaint)
            JoystickOrientation.HORIZONTAL ->
                canvas.drawLine(centerX - radius, centerY, centerX + radius, centerY, axisPaint)
        }
        canvas.drawCircle(thumbX, thumbY, thumbRadius, thumbPaint)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (trackingPointerId == -1) {
                    trackingPointerId = event.getPointerId(event.actionIndex)
                    parent.requestDisallowInterceptTouchEvent(true)
                    updateThumb(event, event.actionIndex)
                    return true
                }
            }
            MotionEvent.ACTION_MOVE -> {
                val idx = event.findPointerIndex(trackingPointerId)
                if (idx >= 0) updateThumb(event, idx)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == trackingPointerId) {
                    trackingPointerId = -1
                    thumbX = centerX
                    thumbY = centerY
                    onPositionChanged?.invoke(0f, 0f)
                    invalidate()
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                trackingPointerId = -1
                thumbX = centerX
                thumbY = centerY
                onPositionChanged?.invoke(0f, 0f)
                invalidate()
            }
        }
        return true
    }

    private fun updateThumb(event: MotionEvent, pointerIndex: Int) {
        var x = event.getX(pointerIndex)
        var y = event.getY(pointerIndex)
        when (orientation) {
            JoystickOrientation.VERTICAL -> x = centerX
            JoystickOrientation.HORIZONTAL -> y = centerY
        }
        var dx = x - centerX
        var dy = y - centerY
        val dist = sqrt(dx * dx + dy * dy)
        // 死区：距离中心小于 10% 半径时输出 0，超过后从 0 平滑过渡，防止误触
        val deadZone = radius * 0.1f
        if (dist < deadZone) {
            thumbX = centerX
            thumbY = centerY
            onPositionChanged?.invoke(0f, 0f)
            invalidate()
            return
        }
        if (dist > radius) {
            dx = dx / dist * radius
            dy = dy / dist * radius
        }
        thumbX = centerX + dx
        thumbY = centerY + dy
        // 有效范围 [deadZone, radius] 映射到 [0, 1]，从 0 平滑开始
        val effectiveDist = (dist - deadZone).coerceAtMost(radius - deadZone)
        val scale = effectiveDist / (radius - deadZone)
        val dirX = dx / dist
        val dirY = dy / dist
        val nx = (dirX * scale).coerceIn(-1f, 1f)
        val ny = (-dirY * scale).coerceIn(-1f, 1f)
        onPositionChanged?.invoke(nx, ny)
        invalidate()
    }
}
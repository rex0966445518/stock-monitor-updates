package com.rex.twboardingscanner.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

class ScoreRingView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
): View(context, attrs, defStyleAttr) {
    private val base = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        color = Color.parseColor("#20384F")
    }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 8f
        strokeCap = Paint.Cap.ROUND
    }
    private val number = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        textSize = 34f
        typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    private val grade = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#93AAC0")
        textAlign = Paint.Align.CENTER
        textSize = 18f
    }
    private var score = 0
    private var accent = Color.parseColor("#36A3FF")

    fun setScore(value: Int, color: Int) {
        score = value.coerceIn(0, 100)
        accent = color
        invalidate()
    }

    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        val density = resources.displayMetrics.density
        base.strokeWidth = 2f * density
        arc.strokeWidth = 2f * density
        number.textSize = 25f * density
        grade.textSize = 9f * density
        val pad = 5f * density
        val rect = RectF(pad, pad, width - pad, height - pad)
        c.drawArc(rect, -90f, 360f, false, base)
        arc.color = accent
        c.drawArc(rect, -90f, 360f * score / 100f, false, arc)
        c.drawText(score.toString(), width / 2f, height / 2f + 6f * density, number)
        val g = when {
            score >= 85 -> "A"
            score >= 75 -> "A-"
            score >= 65 -> "B"
            else -> "觀察"
        }
        c.drawText(g, width / 2f, height / 2f + 20f * density, grade)
    }
}

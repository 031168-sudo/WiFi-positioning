package ru.wifinet.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Lightweight rolling line chart, no external dependency: redraws itself as values stream in. */
class LineChartView(context: Context) : View(context) {

    var lineColor: Int = Color.CYAN
    var unit: String = ""
    var fixedMin: Float? = null
    var fixedMax: Float? = null

    private val maxPoints = 60
    private val values = ArrayDeque<Float>()

    private val gridPaint = Paint().apply { color = Color.argb(35, 255, 255, 255); strokeWidth = 1f }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 4f; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(200, 255, 255, 255); textSize = 26f
    }
    private val path = Path()

    fun addValue(v: Float) {
        values.addLast(v)
        while (values.size > maxPoints) values.removeFirst()
        invalidate()
    }

    fun clear() {
        values.clear()
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        for (i in 1..3) {
            val y = h * i / 4f
            canvas.drawLine(0f, y, w, y, gridPaint)
        }
        if (values.isEmpty()) {
            canvas.drawText("нет данных", 8f, h / 2f, labelPaint)
            return
        }
        val lo = fixedMin ?: values.min()
        val hi = fixedMax ?: values.max()
        val range = (hi - lo).let { if (it < 0.001f) 1f else it }
        path.reset()
        val n = values.size
        values.forEachIndexed { i, v ->
            val x = if (n == 1) w else w * i / (n - 1)
            val y = h - ((v - lo) / range) * h
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        linePaint.color = lineColor
        canvas.drawPath(path, linePaint)
        canvas.drawText("%.0f%s".format(values.last(), unit), 8f, 28f, labelPaint)
    }
}

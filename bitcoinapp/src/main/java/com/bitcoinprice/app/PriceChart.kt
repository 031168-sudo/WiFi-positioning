package com.bitcoinprice.app

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.Calendar
import java.util.TimeZone
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Line chart of BTC/USD close price on a log10 scale (linear would flatten
 * every year before ~2020 given the price range from cents to tens of thousands).
 */
@Composable
fun PriceHistoryChart(points: List<PricePoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(modifier = modifier.fillMaxWidth().height(260.dp)) {
        if (points.size < 2) return@Canvas

        val leftPad = 8.dp.toPx()
        val bottomPad = 24.dp.toPx()
        val topPad = 8.dp.toPx()
        val rightPad = 8.dp.toPx()

        val chartWidth = size.width - leftPad - rightPad
        val chartHeight = size.height - topPad - bottomPad

        val minTime = points.first().timeSec
        val maxTime = points.last().timeSec
        val timeSpan = max(1L, maxTime - minTime)

        val logPrices = points.map { ln(max(it.price, 0.0001)) }
        val minLog = logPrices.min()
        val maxLog = logPrices.max()
        val logSpan = max(0.0001, maxLog - minLog)

        fun xFor(timeSec: Long): Float =
            leftPad + (chartWidth * (timeSec - minTime).toFloat() / timeSpan)

        fun yFor(logPrice: Double): Float =
            topPad + chartHeight - (chartHeight * ((logPrice - minLog) / logSpan)).toFloat()

        val labelPaint = AndroidPaint().apply {
            color = labelColor.toArgbCompat()
            textSize = 10.sp.toPx()
            isAntiAlias = true
        }
        val centeredLabelPaint = AndroidPaint(labelPaint).apply {
            textAlign = AndroidPaint.Align.CENTER
        }

        // Horizontal grid lines with price labels.
        val gridLines = 4
        for (i in 0..gridLines) {
            val frac = i.toDouble() / gridLines
            val logValue = minLog + frac * logSpan
            val y = yFor(logValue)
            drawLine(
                color = gridColor,
                start = Offset(leftPad, y),
                end = Offset(size.width - rightPad, y),
                strokeWidth = 1f
            )
            val price = exp(logValue)
            drawContext.canvas.nativeCanvas.drawText(
                formatPriceShort(price),
                leftPad,
                (y - 4.dp.toPx()).coerceAtLeast(topPad + 10f),
                labelPaint
            )
        }

        // Year labels and vertical grid lines on the x-axis.
        val calendar = Calendar.getInstance(TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = minTime * 1000
        val startYear = calendar.get(Calendar.YEAR)
        calendar.timeInMillis = maxTime * 1000
        val endYear = calendar.get(Calendar.YEAR)
        val yearStep = max(1, (endYear - startYear) / 8)

        var year = startYear
        while (year <= endYear) {
            calendar.set(year, Calendar.JANUARY, 1, 0, 0, 0)
            val t = calendar.timeInMillis / 1000
            if (t in minTime..maxTime) {
                val x = xFor(t)
                drawLine(
                    color = gridColor,
                    start = Offset(x, topPad),
                    end = Offset(x, size.height - bottomPad),
                    strokeWidth = 1f
                )
                drawContext.canvas.nativeCanvas.drawText(
                    year.toString(),
                    x,
                    size.height - 6.dp.toPx(),
                    centeredLabelPaint
                )
            }
            year += yearStep
        }

        // Price line.
        val path = Path()
        points.forEachIndexed { index, point ->
            val x = xFor(point.timeSec)
            val y = yFor(ln(max(point.price, 0.0001)))
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path = path, color = lineColor, style = Stroke(width = 2.5.dp.toPx()))
    }
}

private fun formatPriceShort(price: Double): String = when {
    price >= 1000 -> "$" + "%,.0f".format(price)
    price >= 1 -> "$" + "%.1f".format(price)
    else -> "$" + "%.3f".format(price)
}

private fun Color.toArgbCompat(): Int {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

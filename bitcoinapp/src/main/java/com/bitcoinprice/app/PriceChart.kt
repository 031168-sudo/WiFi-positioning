package com.bitcoinprice.app

import android.graphics.Paint as AndroidPaint
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.awaitFirstDown
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale
import java.util.TimeZone
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max

/**
 * Line chart of BTC/USD close price on a log10 scale (linear would flatten
 * every year before ~2020 given the price range from cents to tens of thousands).
 * Tap or drag on the curve to see the date and price at that point.
 */
@Composable
fun PriceHistoryChart(points: List<PricePoint>, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val tooltipBg = MaterialTheme.colorScheme.inverseSurface
    val tooltipTextColor = MaterialTheme.colorScheme.inverseOnSurface

    var selectedIndex by remember(points) { mutableStateOf<Int?>(null) }

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(260.dp)
            .pointerInput(points) {
                if (points.size < 2) return@pointerInput
                val leftPad = 8.dp.toPx()
                val rightPad = 8.dp.toPx()
                val chartWidth = size.width - leftPad - rightPad
                val minTime = points.first().timeSec
                val maxTime = points.last().timeSec
                val timeSpan = max(1L, maxTime - minTime)

                fun indexForX(x: Float): Int {
                    val frac = ((x - leftPad) / chartWidth).coerceIn(0f, 1f)
                    val targetTime = minTime + (frac * timeSpan).toLong()
                    var lo = 0
                    var hi = points.size - 1
                    while (lo < hi) {
                        val mid = (lo + hi) / 2
                        if (points[mid].timeSec < targetTime) lo = mid + 1 else hi = mid
                    }
                    if (lo > 0 &&
                        abs(points[lo - 1].timeSec - targetTime) <= abs(points[lo].timeSec - targetTime)
                    ) {
                        lo -= 1
                    }
                    return lo
                }

                awaitEachGesture {
                    val down = awaitFirstDown()
                    selectedIndex = indexForX(down.position.x)
                    down.consume()
                    do {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change != null && change.pressed) {
                            selectedIndex = indexForX(change.position.x)
                            change.consume()
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
    ) {
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

        // Selection: vertical guide line, highlighted point and a date/price tooltip.
        val selected = selectedIndex?.let { points.getOrNull(it) }
        if (selected != null) {
            val selX = xFor(selected.timeSec)
            val selY = yFor(ln(max(selected.price, 0.0001)))

            drawLine(
                color = labelColor,
                start = Offset(selX, topPad),
                end = Offset(selX, size.height - bottomPad),
                strokeWidth = 1f
            )
            drawCircle(color = lineColor, radius = 5.dp.toPx(), center = Offset(selX, selY))
            drawCircle(
                color = Color.White,
                radius = 2.dp.toPx(),
                center = Offset(selX, selY)
            )

            val dateText = formatSelectedDate(selected.timeSec)
            val priceText = formatSelectedPrice(selected.price)

            val valuePaint = AndroidPaint().apply {
                color = tooltipTextColor.toArgbCompat()
                textSize = 13.sp.toPx()
                isAntiAlias = true
                isFakeBoldText = true
            }
            val datePaint = AndroidPaint(valuePaint).apply {
                textSize = 10.sp.toPx()
                isFakeBoldText = false
            }

            val textWidth = max(valuePaint.measureText(priceText), datePaint.measureText(dateText))
            val boxPaddingH = 10.dp.toPx()
            val boxPaddingV = 8.dp.toPx()
            val lineGap = 2.dp.toPx()
            val boxWidth = textWidth + boxPaddingH * 2
            val boxHeight = valuePaint.textSize + datePaint.textSize + lineGap + boxPaddingV * 2

            val gap = 10.dp.toPx()
            val boxAbove = selY - gap - boxHeight >= topPad
            val boxTop = if (boxAbove) selY - gap - boxHeight else selY + gap
            val maxBoxLeft = (size.width - rightPad - boxWidth).coerceAtLeast(leftPad)
            val boxLeft = (selX - boxWidth / 2).coerceIn(leftPad, maxBoxLeft)

            drawRoundRect(
                color = tooltipBg,
                topLeft = Offset(boxLeft, boxTop),
                size = androidx.compose.ui.geometry.Size(boxWidth, boxHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(6.dp.toPx(), 6.dp.toPx())
            )

            val centerX = boxLeft + boxWidth / 2
            val datePaintCentered = AndroidPaint(datePaint).apply { textAlign = AndroidPaint.Align.CENTER }
            val valuePaintCentered = AndroidPaint(valuePaint).apply { textAlign = AndroidPaint.Align.CENTER }
            drawContext.canvas.nativeCanvas.drawText(
                dateText,
                centerX,
                boxTop + boxPaddingV + datePaint.textSize - 2f,
                datePaintCentered
            )
            drawContext.canvas.nativeCanvas.drawText(
                priceText,
                centerX,
                boxTop + boxPaddingV + datePaint.textSize + lineGap + valuePaint.textSize - 2f,
                valuePaintCentered
            )
        }
    }
}

private fun formatPriceShort(price: Double): String = when {
    price >= 1000 -> "$" + "%,.0f".format(price)
    price >= 1 -> "$" + "%.1f".format(price)
    else -> "$" + "%.3f".format(price)
}

private fun formatSelectedPrice(price: Double): String = when {
    price >= 1 -> "$" + "%,.2f".format(price)
    else -> "$" + "%.4f".format(price)
}

private val selectedDateFormat = SimpleDateFormat("d MMM yyyy", Locale("ru")).apply {
    timeZone = TimeZone.getTimeZone("UTC")
}

private fun formatSelectedDate(timeSec: Long): String = selectedDateFormat.format(timeSec * 1000)

private fun Color.toArgbCompat(): Int {
    val r = (red * 255).toInt().coerceIn(0, 255)
    val g = (green * 255).toInt().coerceIn(0, 255)
    val b = (blue * 255).toInt().coerceIn(0, 255)
    val a = (alpha * 255).toInt().coerceIn(0, 255)
    return (a shl 24) or (r shl 16) or (g shl 8) or b
}

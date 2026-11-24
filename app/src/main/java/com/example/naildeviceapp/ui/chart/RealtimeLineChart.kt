package com.example.naildeviceapp.ui.chart

import android.graphics.Paint
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

private const val GRID_DIVISIONS = 4

/**
 * Rolling line chart drawn directly on a [Canvas]. Designed for high-frequency updates: it holds no
 * internal state and re-draws from the supplied [series] on every frame.
 */
@Composable
fun RealtimeLineChart(
    series: List<ChartSeries>,
    axis: ChartAxis,
    modifier: Modifier = Modifier,
    yScale: YScale = YScale.Auto(),
    gridColor: Color = Color(0xFFDCDCDC),
    axisColor: Color = Color(0xFF9A9A9A),
    labelColor: Color = Color(0xFF6B6B6B)
) {
    val density = LocalDensity.current
    val labelPaint = remember(density, labelColor) {
        Paint().apply {
            isAntiAlias = true
            color = labelColor.toArgb()
            textSize = with(density) { 9.sp.toPx() }
        }
    }

    Canvas(modifier) {
        val plotLeft = 46.dp.toPx()
        val plotTop = 6.dp.toPx()
        val plotRight = size.width
        val plotBottom = size.height - 18.dp.toPx()
        if (plotRight <= plotLeft || plotBottom <= plotTop) return@Canvas

        val bounds = yScale.resolve(series)
        val ySpan = (bounds.max - bounds.min).takeIf { abs(it) > 1e-9f } ?: 1f

        drawGrid(plotLeft, plotTop, plotRight, plotBottom, gridColor, axisColor)
        drawAxisLabels(plotLeft, plotTop, plotRight, plotBottom, bounds, axis, labelPaint)

        val plotHeight = plotBottom - plotTop
        val xStep = if (axis.capacity > 1) (plotRight - plotLeft) / (axis.capacity - 1) else 0f
        val strokeWidth = 1.6.dp.toPx()

        series.forEach { line ->
            if (line.points.size < 2) return@forEach
            val path = Path()
            line.points.forEachIndexed { index, value ->
                val x = plotLeft + index * xStep
                val ratio = ((value - bounds.min) / ySpan).coerceIn(0f, 1f)
                val y = plotBottom - ratio * plotHeight
                if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, line.color, style = Stroke(width = strokeWidth))
        }
    }
}

private fun DrawScope.drawGrid(
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    gridColor: Color,
    axisColor: Color
) {
    val hairline = 0.5.dp.toPx()
    repeat(GRID_DIVISIONS + 1) { step ->
        val y = plotBottom - (plotBottom - plotTop) * step / GRID_DIVISIONS
        drawLine(gridColor, Offset(plotLeft, y), Offset(plotRight, y), hairline)

        val x = plotLeft + (plotRight - plotLeft) * step / GRID_DIVISIONS
        drawLine(gridColor, Offset(x, plotTop), Offset(x, plotBottom), hairline)
    }
    val axisWidth = 1.dp.toPx()
    drawLine(axisColor, Offset(plotLeft, plotTop), Offset(plotLeft, plotBottom), axisWidth)
    drawLine(axisColor, Offset(plotLeft, plotBottom), Offset(plotRight, plotBottom), axisWidth)
}

private fun DrawScope.drawAxisLabels(
    plotLeft: Float,
    plotTop: Float,
    plotRight: Float,
    plotBottom: Float,
    bounds: YScale.Fixed,
    axis: ChartAxis,
    paint: Paint
) {
    val canvas = drawContext.canvas.nativeCanvas
    val gap = 4.dp.toPx()

    paint.textAlign = Paint.Align.RIGHT
    // Decimals come from the gap between ticks, not the tick value: on a narrow range such as
    // 0.98..1.10 a per-value rule would print "1.1" for two different ticks.
    val tickStep = (bounds.max - bounds.min) / GRID_DIVISIONS
    repeat(GRID_DIVISIONS + 1) { step ->
        val value = bounds.min + (bounds.max - bounds.min) * step / GRID_DIVISIONS
        val y = plotBottom - (plotBottom - plotTop) * step / GRID_DIVISIONS
        canvas.drawText(formatAxisValue(value, tickStep), plotLeft - gap, y + paint.textSize / 3f, paint)
    }

    paint.textAlign = Paint.Align.CENTER
    val baseline = plotBottom + paint.textSize + gap / 2f
    repeat(GRID_DIVISIONS + 1) { step ->
        val sample = axis.capacity.toFloat() * step / GRID_DIVISIONS
        val x = plotLeft + (plotRight - plotLeft) * step / GRID_DIVISIONS
        canvas.drawText(axis.xLabel(sample), x, baseline, paint)
    }
}

/** Formats a tick so that neighbouring ticks [tickStep] apart stay distinguishable. */
internal fun formatAxisValue(value: Float, tickStep: Float): String {
    val step = abs(tickStep)
    val decimals = when {
        step >= 10f -> 0
        step >= 1f -> 1
        step >= 0.1f -> 2
        step >= 0.01f -> 3
        else -> 4
    }
    val text = "%.${decimals}f".format(value)
    // A tick that lands a hair below zero formats as "-0" / "-0.00", which reads as a real negative
    // value on an axis whose other ticks are thousands. Auto-scaled bounds land there routinely.
    return if (text.startsWith("-") && text.drop(1).none { it in '1'..'9' }) text.drop(1) else text
}

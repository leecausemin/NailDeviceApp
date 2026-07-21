package com.example.naildeviceapp.ui.chart

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.abs
import kotlin.math.max

/**
 * One line on a chart. [points] are y-values in sample order; the x position of a point is its
 * index, so a series is drawn across the full [ChartAxis.capacity] of its chart.
 *
 * Backed by a [FloatArray] rather than a list: these are rebuilt every frame at streaming rates, and
 * boxing thousands of samples per second would churn the heap. Each snapshot is a fresh array, so
 * identity comparison is enough for Compose to notice a new frame.
 */
@Immutable
data class ChartSeries(
    val label: String,
    val color: Color,
    val points: FloatArray
) {
    override fun equals(other: Any?): Boolean =
        this === other ||
            (other is ChartSeries && label == other.label && color == other.color && points === other.points)

    override fun hashCode(): Int =
        31 * (31 * label.hashCode() + color.hashCode()) + System.identityHashCode(points)
}

/** Fixed x extent of a rolling chart window, expressed in samples. */
@Immutable
data class ChartAxis(
    val capacity: Int,
    /** Converts a sample index into the value rendered on the x axis (e.g. seconds). */
    val xLabel: (Float) -> String = { it.toInt().toString() }
)

/** Vertical bounds of a chart: either pinned by the caller or grown to fit the data. */
@Immutable
sealed interface YScale {
    @Immutable
    data class Fixed(val min: Float, val max: Float) : YScale

    /** Mirrors the iOS charts, which pad the observed range by [padding] on each side. */
    @Immutable
    data class Auto(val padding: Float = 0.3f, val fallback: Fixed = Fixed(-1f, 1f)) : YScale
}

internal fun YScale.resolve(series: List<ChartSeries>): YScale.Fixed = when (this) {
    is YScale.Fixed -> this
    is YScale.Auto -> {
        var min = Float.MAX_VALUE
        var max = -Float.MAX_VALUE
        series.forEach { line ->
            for (value in line.points) {
                if (value < min) min = value
                if (value > max) max = value
            }
        }
        if (min > max) {
            fallback
        } else {
            val span = max(abs(max - min), 1e-6f)
            YScale.Fixed(min - span * padding, max + span * padding)
        }
    }
}

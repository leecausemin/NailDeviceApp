package com.example.naildeviceapp.ui.chart

import android.graphics.Bitmap
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize

/**
 * Scrolling spectrogram. [columns] runs oldest-to-newest; each column holds one FFT frame with
 * magnitudes normalised to 0..1, bin 0 being the lowest frequency.
 */
@Immutable
data class SpectrogramData(
    val columns: List<FloatArray>,
    val binCount: Int
)

@Composable
fun SpectrogramView(data: SpectrogramData, modifier: Modifier = Modifier) {
    val image = remember(data) { data.toImageBitmap() }

    Canvas(modifier) {
        if (image == null) return@Canvas
        drawImage(
            image = image,
            srcOffset = IntOffset.Zero,
            srcSize = IntSize(image.width, image.height),
            dstOffset = IntOffset.Zero,
            dstSize = IntSize(size.width.toInt(), size.height.toInt()),
            filterQuality = FilterQuality.Low
        )
    }
}

private fun SpectrogramData.toImageBitmap(): ImageBitmap? {
    val width = columns.size
    if (width == 0 || binCount == 0) return null

    val pixels = IntArray(width * binCount)
    columns.forEachIndexed { columnIndex, magnitudes ->
        for (bin in 0 until binCount) {
            // Row 0 is the top of the image, so the highest frequency bin is drawn first.
            val row = binCount - 1 - bin
            val value = magnitudes.getOrElse(bin) { 0f }
            pixels[row * width + columnIndex] = heatColor(value)
        }
    }
    return Bitmap.createBitmap(pixels, width, binCount, Bitmap.Config.ARGB_8888).asImageBitmap()
}

/** Stops of the "inferno"-style ramp used by the iOS spectrogram layer. */
private val HEAT_STOPS = intArrayOf(
    0xFF000004.toInt(), 0xFF1B0C41.toInt(), 0xFF4A0C6B.toInt(), 0xFF781C6D.toInt(),
    0xFFA52C60.toInt(), 0xFFCF4446.toInt(), 0xFFED6925.toInt(), 0xFFFB9A06.toInt(),
    0xFFF7D13D.toInt(), 0xFFFCFFA4.toInt()
)

private fun heatColor(value: Float): Int {
    val clamped = value.coerceIn(0f, 1f)
    val scaled = clamped * (HEAT_STOPS.size - 1)
    val lowIndex = scaled.toInt().coerceAtMost(HEAT_STOPS.size - 2)
    val fraction = scaled - lowIndex

    val low = HEAT_STOPS[lowIndex]
    val high = HEAT_STOPS[lowIndex + 1]
    val red = lerpChannel(low shr 16 and 0xFF, high shr 16 and 0xFF, fraction)
    val green = lerpChannel(low shr 8 and 0xFF, high shr 8 and 0xFF, fraction)
    val blue = lerpChannel(low and 0xFF, high and 0xFF, fraction)
    return (0xFF shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun lerpChannel(from: Int, to: Int, fraction: Float): Int =
    (from + (to - from) * fraction).toInt().coerceIn(0, 255)

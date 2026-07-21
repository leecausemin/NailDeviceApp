package com.example.naildeviceapp.data

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sqrt

private const val LOG_COMPRESSION = 24f

/**
 * Turns a window of samples into a normalised magnitude spectrum for the spectrogram and the
 * frequency-power trace.
 *
 * Uses a plain DFT with precomputed twiddle tables rather than an FFT: at 128 points and 64 bins the
 * table lookup dominates anyway, and it keeps the maths readable. The result is scaled to 0..1
 * against the frame's own peak so that sensors with very different units — microphone counts around
 * 1450, accelerometer values around 1.0 — all render at a usable brightness.
 */
internal class SpectrumAnalyzer(private val windowSize: Int, private val bins: Int) {

    private val cosTable = FloatArray(bins * windowSize)
    private val sinTable = FloatArray(bins * windowSize)
    private val hann = FloatArray(windowSize)

    init {
        for (bin in 0 until bins) {
            for (n in 0 until windowSize) {
                val angle = 2.0 * PI * bin * n / windowSize
                cosTable[bin * windowSize + n] = cos(angle).toFloat()
                sinTable[bin * windowSize + n] = sin(angle).toFloat()
            }
        }
        for (n in 0 until windowSize) {
            hann[n] = (0.5 - 0.5 * cos(2.0 * PI * n / (windowSize - 1))).toFloat()
        }
    }

    /** Magnitudes in 0..1, bin 0 lowest. Returns silence until [window] holds a full frame. */
    fun magnitudes(window: FloatArray): FloatArray {
        if (window.size < windowSize) return FloatArray(bins)

        val centred = removeMean(window)
        val magnitudes = FloatArray(bins)
        var peak = 0f

        for (bin in 0 until bins) {
            var real = 0f
            var imaginary = 0f
            val offset = bin * windowSize
            for (n in 0 until windowSize) {
                val sample = centred[n] * hann[n]
                real += sample * cosTable[offset + n]
                imaginary -= sample * sinTable[offset + n]
            }
            val magnitude = sqrt(real * real + imaginary * imaginary)
            magnitudes[bin] = magnitude
            if (magnitude > peak) peak = magnitude
        }

        if (peak <= 1e-9f) return FloatArray(bins)

        val scale = 1f / ln(1f + LOG_COMPRESSION)
        for (bin in 0 until bins) {
            magnitudes[bin] = ln(1f + LOG_COMPRESSION * magnitudes[bin] / peak) * scale
        }
        return magnitudes
    }

    /** Mean energy of the lowest quarter of the spectrum — the "< 150 Hz" band of the iOS chart. */
    fun lowBandPower(spectrum: FloatArray): Float {
        val cutoff = (spectrum.size / 4).coerceAtLeast(1)
        var total = 0f
        for (bin in 0 until cutoff) total += spectrum[bin]
        return total / cutoff
    }

    private fun removeMean(window: FloatArray): FloatArray {
        var sum = 0f
        for (sample in window) sum += sample
        val mean = sum / window.size
        return FloatArray(windowSize) { window[it] - mean }
    }
}

package com.example.naildeviceapp.data

import kotlin.math.PI
import kotlin.math.sin
import kotlin.random.Random

/**
 * Produces plausible-looking sensor traces so the screens can be exercised without hardware.
 * Nothing here models the real device physics — it only needs to move like the real thing.
 */
internal class MockSignalGenerator(seed: Long = 20260721L) {

    private val random = Random(seed)
    private var elapsed = 0.0

    fun advance(deltaSeconds: Double) {
        elapsed += deltaSeconds
    }

    /** 0..1 envelope standing in for speech/singing bursts every few seconds. */
    private fun envelope(at: Double = elapsed): Float {
        val slow = sin(2 * PI * 0.18 * at)
        return ((slow - 0.15).coerceAtLeast(0.0) / 0.85).toFloat()
    }

    fun gyro(count: Int, sampleInterval: Double): XyzChunk = generateXyz(count, sampleInterval) { at ->
        val motion = envelope(at) * 60f
        floatArrayOf(
            (18 * sin(2 * PI * 1.1 * at)).toFloat() + motion * noise(),
            (12 * sin(2 * PI * 0.7 * at + 1.0)).toFloat() + motion * noise(),
            (9 * sin(2 * PI * 1.7 * at + 2.0)).toFloat() + motion * noise()
        )
    }

    fun accel(count: Int, sampleInterval: Double): XyzChunk = generateXyz(count, sampleInterval) { at ->
        val motion = envelope(at) * 0.08f
        floatArrayOf(
            (0.02 * sin(2 * PI * 1.3 * at)).toFloat() + motion * noise(),
            (0.03 * sin(2 * PI * 0.9 * at + 0.6)).toFloat() + motion * noise(),
            (0.98 + 0.02 * sin(2 * PI * 0.5 * at)).toFloat() + motion * noise()
        )
    }

    /** Raw microphone counts, centred on the ADC midpoint like the real 12-bit stream. */
    fun mic(count: Int, sampleInterval: Double): FloatArray {
        val samples = FloatArray(count)
        for (index in 0 until count) {
            val at = elapsed + index * sampleInterval
            val amplitude = 60f + envelope(at) * 520f
            val tone = sin(2 * PI * 47 * at) * 0.6 + sin(2 * PI * 31 * at + 0.8) * 0.4
            samples[index] = 1450f + amplitude * tone.toFloat() + noise() * 25f
        }
        return samples
    }

    fun temperature(): Float = 31.4f + 0.4f * sin(2 * PI * 0.05 * elapsed).toFloat() + noise() * 0.05f

    private fun noise(): Float = random.nextFloat() * 2f - 1f

    private inline fun generateXyz(
        count: Int,
        sampleInterval: Double,
        sample: (Double) -> FloatArray
    ): XyzChunk {
        val x = FloatArray(count)
        val y = FloatArray(count)
        val z = FloatArray(count)
        for (index in 0 until count) {
            val axes = sample(elapsed + index * sampleInterval)
            x[index] = axes[0]
            y[index] = axes[1]
            z[index] = axes[2]
        }
        return XyzChunk(x, y, z)
    }
}

internal class XyzChunk(val x: FloatArray, val y: FloatArray, val z: FloatArray)

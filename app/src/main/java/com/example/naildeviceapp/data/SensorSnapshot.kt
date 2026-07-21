package com.example.naildeviceapp.data

import androidx.compose.runtime.Immutable
import com.example.naildeviceapp.ui.chart.SpectrogramData

/** Three-axis sample window for the accelerometer and gyroscope charts. */
@Immutable
data class XyzWindow(
    val x: FloatArray,
    val y: FloatArray,
    val z: FloatArray
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is XyzWindow && x === other.x && y === other.y && z === other.z)

    override fun hashCode(): Int =
        31 * (31 * System.identityHashCode(x) + System.identityHashCode(y)) + System.identityHashCode(z)

    companion object {
        val EMPTY = XyzWindow(FloatArray(0), FloatArray(0), FloatArray(0))
    }
}

/** One frame of everything the monitor screen plots. */
@Immutable
data class SensorSnapshot(
    val power: FloatArray,
    val gyro: XyzWindow,
    val accel: XyzWindow,
    val mic: FloatArray,
    val spectrogram: SpectrogramData,
    val temperature: FloatArray,
    val sensorTimeMillis: Long
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is SensorSnapshot && sensorTimeMillis == other.sensorTimeMillis && power === other.power)

    override fun hashCode(): Int = 31 * sensorTimeMillis.hashCode() + System.identityHashCode(power)

    companion object {
        val EMPTY = SensorSnapshot(
            power = FloatArray(0),
            gyro = XyzWindow.EMPTY,
            accel = XyzWindow.EMPTY,
            mic = FloatArray(0),
            spectrogram = SpectrogramData(emptyList(), 0),
            temperature = FloatArray(0),
            sensorTimeMillis = 0L
        )
    }
}

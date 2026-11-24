package com.example.naildeviceapp.data.ble

import com.example.naildeviceapp.model.ChartWindow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The charts sweep left to right and restart, so their position on screen is only ever a count of
 * what was written. These tests pin that count to the frame rather than to the sensor rate — the
 * mismatch that made the microphone and IMU windows disagree.
 */
class TickResamplerTest {

    @Test
    fun `emits one frame of points regardless of how many samples arrived`() {
        val sampleCountsPerFrame = listOf(0, 1, 7, 30, 50, 333, 800)

        sampleCountsPerFrame.forEach { count ->
            val resampler = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)
            val out = resampler.resample(FloatArray(count) { it.toFloat() })

            assertEquals(
                "a frame carrying $count samples must still advance the buffer by one frame",
                ChartWindow.MIC_POINTS_PER_TICK,
                out.size
            )
        }
    }

    @Test
    fun `a full window takes exactly the advertised number of seconds`() {
        val resampler = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)
        // One second of a 16 kHz microphone, delivered as TICK_HZ frames.
        val samplesPerFrame = 16_000 / ChartWindow.TICK_HZ

        var written = 0
        repeat(ChartWindow.TICK_HZ * ChartWindow.SECONDS) {
            written += resampler.resample(FloatArray(samplesPerFrame)).size
        }

        assertEquals(
            "${ChartWindow.SECONDS}s of frames must fill the window exactly once",
            ChartWindow.MIC_CAPACITY,
            written
        )
    }

    @Test
    fun `imu window fills at the same rate whatever the sensor rate is`() {
        val everySelectableImuRate = listOf(104, 208, 416, 833, 1666, 3333, 6664)

        everySelectableImuRate.forEach { hz ->
            val resampler = TickResampler(ChartWindow.IMU_POINTS_PER_TICK)
            var written = 0
            repeat(ChartWindow.TICK_HZ * ChartWindow.SECONDS) {
                written += resampler.resample(FloatArray(hz / ChartWindow.TICK_HZ)).size
            }

            assertEquals("$hz Hz must sweep the window in ${ChartWindow.SECONDS}s", ChartWindow.IMU_CAPACITY, written)
        }
    }

    @Test
    fun `keeps the peak of a burst instead of sub-sampling past it`() {
        val resampler = TickResampler(4)
        val frame = FloatArray(40)
        frame[7] = -9f // a tap that plain sub-sampling would step over

        val out = resampler.resample(frame)

        assertTrue("the loudest sample in the frame must survive", out.any { abs(it - -9f) < 1e-6f })
    }

    @Test
    fun `an empty frame holds the last value rather than dropping to zero`() {
        val resampler = TickResampler(3)
        resampler.resample(floatArrayOf(2f, 2f, 2f))

        val out = resampler.resample(FloatArray(0))

        assertEquals(3, out.size)
        out.forEach { assertEquals(2f, it, 1e-6f) }
    }

    @Test
    fun `standing in for missed frames emits their points too`() {
        val resampler = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)

        val out = resampler.resample(FloatArray(320), points = ChartWindow.MIC_POINTS_PER_TICK * 4)

        assertEquals(
            "a frame covering four slots must advance the buffer by four frames' worth",
            ChartWindow.MIC_POINTS_PER_TICK * 4,
            out.size
        )
    }

    @Test
    fun `a stalled emitter still sweeps the window in real time`() {
        // The failure this pins: with a fixed point count per call, a thread that manages only
        // half the frames sweeps the window at half speed and the charts fall behind the clock
        // for good. Standing in for the missed slots is what keeps the two equal.
        val resampler = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)
        val samplesPerFrame = 16_000 / ChartWindow.TICK_HZ

        var written = 0
        val slots = ChartWindow.TICK_HZ * ChartWindow.SECONDS
        var slot = 0
        while (slot < slots) {
            // Every call is late by one frame, so each covers two slots' worth of time.
            val due = minOf(2, slots - slot)
            written += resampler.resample(
                FloatArray(samplesPerFrame * due),
                points = ChartWindow.MIC_POINTS_PER_TICK * due
            ).size
            slot += due
        }

        assertEquals(
            "half the frames must still sweep the full window in ${ChartWindow.SECONDS}s",
            ChartWindow.MIC_CAPACITY,
            written
        )
    }

    @Test
    fun `both sensors stay aligned through a stall`() {
        // Same multiplier to both is what stops a stall from sliding the traces against each other.
        val mic = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)
        val imu = TickResampler(ChartWindow.IMU_POINTS_PER_TICK)

        var micWritten = 0
        var imuWritten = 0
        listOf(1, 1, 5, 1, 3, 1).forEach { due ->
            micWritten += mic.resample(
                FloatArray(320 * due), points = ChartWindow.MIC_POINTS_PER_TICK * due
            ).size
            imuWritten += imu.resample(
                FloatArray(33 * due), points = ChartWindow.IMU_POINTS_PER_TICK * due
            ).size
        }

        assertEquals(
            "the two windows must cover the same span of time",
            micWritten.toFloat() / ChartWindow.MIC_DISPLAY_HZ,
            imuWritten.toFloat() / ChartWindow.IMU_DISPLAY_HZ,
            1e-6f
        )
    }
}

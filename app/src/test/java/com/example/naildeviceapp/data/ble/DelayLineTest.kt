package com.example.naildeviceapp.data.ble

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * The live charts are fed by arrival, so whichever stream the board started first was drawn ahead
 * of the other for the whole session. [DelayLine] holds the earlier one back by the board's
 * measured `SYNC,imu_minus_mic_us`.
 *
 * The two properties that matter: a frame must come out the same length it went in (the charts
 * advance by a fixed count per frame, so any change there moves the trace against the clock), and
 * the shift has to be exact rather than approximately right.
 */
class DelayLineTest {

    @Test
    fun `zero delay passes the frame straight through`() {
        val line = DelayLine()
        line.setDelay(0)
        val input = floatArrayOf(1f, 2f, 3f)

        assertSame("no delay must not copy or reshape the frame", input, line.process(input))
    }

    @Test
    fun `output length always equals input length`() {
        val line = DelayLine()
        line.setDelay(7)

        listOf(0, 1, 5, 30, 800).forEach { size ->
            assertEquals(
                "a $size-sample frame must stay $size long or the chart drifts",
                size,
                line.process(FloatArray(size)).size
            )
        }
    }

    @Test
    fun `shifts the waveform by exactly the requested number of points`() {
        val line = DelayLine()
        line.setDelay(3)

        // Prefilled with zeros, so the first three points out are the padding.
        assertArrayEquals(
            floatArrayOf(0f, 0f, 0f, 1f, 2f),
            line.process(floatArrayOf(1f, 2f, 3f, 4f, 5f)),
            1e-6f
        )
        // The tail carries across the frame boundary rather than being dropped.
        assertArrayEquals(
            floatArrayOf(3f, 4f, 5f, 6f, 7f),
            line.process(floatArrayOf(6f, 7f, 8f, 9f, 10f)),
            1e-6f
        )
    }

    @Test
    fun `a spike stays a spike, only later`() {
        val line = DelayLine()
        line.setDelay(2)
        val frame = FloatArray(10)
        frame[4] = 9f

        val out = line.process(frame)

        assertEquals("the spike must move by the delay, not blur", 9f, out[6], 1e-6f)
        assertEquals(0f, out[4], 1e-6f)
    }

    @Test
    fun `changing the delay clears the old contents`() {
        val line = DelayLine()
        line.setDelay(2)
        line.process(floatArrayOf(5f, 5f, 5f, 5f))

        line.setDelay(4)
        val out = line.process(floatArrayOf(1f, 2f, 3f, 4f))

        assertArrayEquals(
            "samples buffered at the old delay must not leak out at the new one",
            floatArrayOf(0f, 0f, 0f, 0f),
            out,
            1e-6f
        )
    }

    @Test
    fun `a garbled sync value cannot allocate an unbounded queue`() {
        val line = DelayLine()
        line.setDelay(Int.MAX_VALUE)

        // Must still behave: bounded, and length-preserving.
        assertEquals(4, line.process(FloatArray(4) { 1f }).size)
    }

    @Test
    fun `a negative delay is treated as none`() {
        val line = DelayLine()
        line.setDelay(-5)
        val input = floatArrayOf(1f, 2f)

        assertArrayEquals(input, line.process(input), 1e-6f)
    }
}

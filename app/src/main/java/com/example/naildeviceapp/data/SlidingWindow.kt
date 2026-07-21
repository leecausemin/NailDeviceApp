package com.example.naildeviceapp.data

import kotlin.math.min

/**
 * Fixed-length FIFO of the most recent samples. Unlike [RollingBuffer] it never resets — spectral
 * analysis needs a continuous window, not a sweep.
 */
class SlidingWindow(private val capacity: Int) {

    private val data = FloatArray(capacity)
    private var size = 0

    fun addAll(values: FloatArray) {
        if (values.isEmpty()) return

        if (values.size >= capacity) {
            values.copyInto(data, 0, values.size - capacity, values.size)
            size = capacity
            return
        }

        val keep = min(size, capacity - values.size)
        if (keep > 0) data.copyInto(data, 0, size - keep, size)
        values.copyInto(data, keep, 0, values.size)
        size = keep + values.size
    }

    fun snapshot(): FloatArray = data.copyOf(size)

    fun clear() {
        size = 0
    }
}

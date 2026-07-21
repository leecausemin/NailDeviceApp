package com.example.naildeviceapp.data

/**
 * Fixed-capacity sample window that fills left to right and starts over once full, reproducing the
 * sweep-and-reset behaviour of the iOS charts.
 */
class RollingBuffer(private val capacity: Int) {

    private val data = FloatArray(capacity)
    private var size = 0

    fun add(value: Float) {
        if (size == capacity) size = 0
        data[size++] = value
    }

    fun addAll(values: FloatArray) = values.forEach(::add)

    /** Defensive copy of the samples written so far; safe to hand to the UI. */
    fun snapshot(): FloatArray = data.copyOf(size)

    fun clear() {
        size = 0
    }
}

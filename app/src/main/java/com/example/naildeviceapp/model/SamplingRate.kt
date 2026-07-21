package com.example.naildeviceapp.model

/**
 * Sampling rates offered by the device, matching the iOS segmented control.
 * [divider] is the byte written after the `0xA0` command in the original firmware protocol.
 */
enum class SamplingRate(val label: String, val divider: Int, val kHz: Int) {
    Hz16k("16k", 0x10, 16),
    Hz8k("8k", 0x08, 8),
    Hz4k("4k", 0x04, 4),
    Hz2k("2k", 0x02, 2),
    Hz1k("1k", 0x01, 1)
}

enum class MemoryStatus { Inactive, Active, Saved }

enum class RecordingState { Off, Recording, Stopped }

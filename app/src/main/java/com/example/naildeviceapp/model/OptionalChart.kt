package com.example.naildeviceapp.model

import com.example.naildeviceapp.R

/**
 * A chart the user can hide from the monitor screen. The spectrogram and the frequency-power trace
 * are deliberately absent: those two are always shown.
 *
 * [titleRes] is shared by the monitor card and the settings checklist so the two can never drift.
 */
enum class OptionalChart(val titleRes: Int) {
    Gyroscope(R.string.chart_gyro),
    Imu(R.string.chart_imu),
    Microphone(R.string.chart_mic),
    Temperature(R.string.chart_temperature)
}

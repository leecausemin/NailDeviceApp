package com.example.naildeviceapp.data

import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.DeviceMode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * What the monitor screen shows: which sensor the spectral views analyse, and which charts are
 * visible. Chosen on the settings screen and read by the monitor screen and the device client, so
 * everything stays in sync through a single source of truth.
 */
class MonitoringPreferences {

    private val _sensor = MutableStateFlow(MonitoringSensor.Microphone)
    val sensor: StateFlow<MonitoringSensor> = _sensor.asStateFlow()

    private val _visibleCharts = MutableStateFlow(DEFAULT_VISIBLE_CHARTS)
    val visibleCharts: StateFlow<Set<OptionalChart>> = _visibleCharts.asStateFlow()

    fun select(sensor: MonitoringSensor) {
        _sensor.value = sensor
    }

    /** Reset hardware-dependent choices so an ERM session never waits for a microphone it lacks. */
    fun applyDeviceMode(mode: DeviceMode) {
        if (mode.hasMicrophone) {
            _sensor.value = MonitoringSensor.Microphone
            _visibleCharts.value = DEFAULT_VISIBLE_CHARTS
        } else {
            _sensor.value = MonitoringSensor.AccelZ
            _visibleCharts.value = DEFAULT_VISIBLE_CHARTS - OptionalChart.Microphone
        }
    }

    fun toggleChart(kind: OptionalChart) = _visibleCharts.update { visible ->
        if (kind in visible) visible - kind else visible + kind
    }

    private companion object {
        /**
         * The three signals a tap actually shows up in. Temperature drifts on a timescale of
         * minutes, so it starts hidden and costs nothing until someone asks for it.
         */
        val DEFAULT_VISIBLE_CHARTS = setOf(
            OptionalChart.Gyroscope,
            OptionalChart.Imu,
            OptionalChart.Microphone
        )
    }
}

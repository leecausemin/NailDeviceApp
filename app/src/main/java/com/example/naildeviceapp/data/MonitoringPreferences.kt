package com.example.naildeviceapp.data

import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.MonitoringSensor
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

    private val _visibleCharts = MutableStateFlow(OptionalChart.entries.toSet())
    val visibleCharts: StateFlow<Set<OptionalChart>> = _visibleCharts.asStateFlow()

    fun select(sensor: MonitoringSensor) {
        _sensor.value = sensor
    }

    fun toggleChart(kind: OptionalChart) = _visibleCharts.update { visible ->
        if (kind in visible) visible - kind else visible + kind
    }
}

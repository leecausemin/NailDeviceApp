package com.example.naildeviceapp.ui.settings

import androidx.lifecycle.ViewModel
import com.example.naildeviceapp.AppContainer
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.MonitoringSensor
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val preferences: MonitoringPreferences = AppContainer.monitoringPreferences
) : ViewModel() {

    val selectedSensor: StateFlow<MonitoringSensor> = preferences.sensor

    val sensorOptions: List<MonitoringSensor> = MonitoringSensor.entries

    val visibleCharts: StateFlow<Set<OptionalChart>> = preferences.visibleCharts

    val chartOptions: List<OptionalChart> = OptionalChart.entries

    fun selectSensor(sensor: MonitoringSensor) = preferences.select(sensor)

    fun toggleChart(kind: OptionalChart) = preferences.toggleChart(kind)
}

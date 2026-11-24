package com.example.naildeviceapp.ui.settings

import androidx.lifecycle.ViewModel
import com.example.naildeviceapp.AppContainer
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.StreamProfile
import kotlinx.coroutines.flow.StateFlow

class SettingsViewModel(
    private val preferences: MonitoringPreferences = AppContainer.monitoringPreferences,
    private val client: NailDeviceClient = AppContainer.deviceClient
) : ViewModel() {

    val selectedSensor: StateFlow<MonitoringSensor> = preferences.sensor

    val visibleCharts: StateFlow<Set<OptionalChart>> = preferences.visibleCharts

    fun sensorOptions(mode: DeviceMode): List<MonitoringSensor> =
        if (mode.hasMicrophone) MonitoringSensor.entries
        else MonitoringSensor.entries.filterNot { it == MonitoringSensor.Microphone }

    fun chartOptions(mode: DeviceMode): List<OptionalChart> =
        if (mode.hasMicrophone) OptionalChart.entries
        else OptionalChart.entries.filterNot { it == OptionalChart.Microphone }

    fun selectSensor(sensor: MonitoringSensor) = preferences.select(sensor)

    fun toggleChart(kind: OptionalChart) = preferences.toggleChart(kind)

    /** Compression lives on the device, not in preferences, so it is read back from the client. */
    val status: StateFlow<DeviceStatus> = client.status

    fun profileOptions(mode: DeviceMode): List<StreamProfile> = mode.streamProfiles

    fun selectStreamProfile(profile: StreamProfile) = client.setStreamProfile(profile)
}

package com.example.naildeviceapp.ui.monitor

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.naildeviceapp.AppContainer
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.SensorSnapshot
import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.SamplingRate
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/** Slider positions owned by the screen rather than reported by the device. */
data class ControlState(
    val gainPercent: Float = 62.5f,
    val calibrationMax: Float = 100f,
    val calibrationMin: Float = 45f
)

data class MonitorUiState(
    val status: DeviceStatus = DeviceStatus(),
    val sensors: SensorSnapshot = SensorSnapshot.EMPTY,
    val controls: ControlState = ControlState(),
    val monitoringSensor: MonitoringSensor = MonitoringSensor.Microphone,
    val visibleCharts: Set<OptionalChart> = OptionalChart.entries.toSet(),
    val isConnected: Boolean = false
)

/** The two display preferences folded together so [MonitorViewModel] stays within combine's arity. */
private data class DisplayPreferences(
    val sensor: MonitoringSensor,
    val visibleCharts: Set<OptionalChart>
)

class MonitorViewModel(
    private val client: NailDeviceClient = AppContainer.deviceClient,
    preferences: MonitoringPreferences = AppContainer.monitoringPreferences
) : ViewModel() {

    private val controls = MutableStateFlow(ControlState())

    private val display = combine(preferences.sensor, preferences.visibleCharts, ::DisplayPreferences)

    val uiState: StateFlow<MonitorUiState> =
        combine(
            client.status,
            client.sensors,
            client.connectionState,
            controls,
            display
        ) { status, sensors, connection, controlState, displayPreferences ->
            MonitorUiState(
                status = status,
                sensors = sensors,
                controls = controlState,
                monitoringSensor = displayPreferences.sensor,
                visibleCharts = displayPreferences.visibleCharts,
                isConnected = connection == ConnectionState.Connected
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorUiState())

    fun setSamplingRate(rate: SamplingRate) = client.setSamplingRate(rate)

    fun setRecording(state: RecordingState) = client.setRecording(state)

    fun toggleTimeSync() = client.setTimeSync(!uiState.value.status.timeSyncOn)

    fun downloadMemory() = client.downloadMemory()

    fun disconnect() = client.disconnect()

    fun setGain(percent: Float) = controls.update { it.copy(gainPercent = percent) }

    /** Pushes the gain to the device once the user releases the slider, as the iOS app does. */
    fun commitGain() = client.setGain(controls.value.gainPercent)

    fun setCalibrationMax(value: Float) = controls.update {
        it.copy(calibrationMax = value.coerceAtLeast(it.calibrationMin))
    }

    fun setCalibrationMin(value: Float) = controls.update {
        it.copy(calibrationMin = value.coerceAtMost(it.calibrationMax))
    }
}

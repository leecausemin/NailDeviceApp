package com.example.naildeviceapp.ui.monitor

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.naildeviceapp.AppContainer
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.RecordingLocation
import com.example.naildeviceapp.data.RecordingLocationPreferences
import com.example.naildeviceapp.data.SensorSnapshot
import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.model.ImuSamplingRate
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class MonitorUiState(
    val status: DeviceStatus = DeviceStatus(),
    val sensors: SensorSnapshot = SensorSnapshot.EMPTY,
    val monitoringSensor: MonitoringSensor = MonitoringSensor.Microphone,
    val visibleCharts: Set<OptionalChart> = OptionalChart.entries.toSet(),
    val saveLocation: RecordingLocation = RecordingLocation(),
    val isConnected: Boolean = false
)

/** The preferences folded together so [MonitorViewModel] stays within combine's arity. */
private data class ScreenPreferences(
    val sensor: MonitoringSensor,
    val visibleCharts: Set<OptionalChart>,
    val saveLocation: RecordingLocation
)

class MonitorViewModel(
    private val client: NailDeviceClient = AppContainer.deviceClient,
    preferences: MonitoringPreferences = AppContainer.monitoringPreferences,
    private val recordingLocation: RecordingLocationPreferences = AppContainer.recordingLocation
) : ViewModel() {

    private val screen = combine(
        preferences.sensor,
        preferences.visibleCharts,
        recordingLocation.location,
        ::ScreenPreferences
    )

    val uiState: StateFlow<MonitorUiState> =
        combine(
            client.status,
            client.sensors,
            client.connectionState,
            screen
        ) { status, sensors, connection, preferences ->
            MonitorUiState(
                status = status,
                sensors = sensors,
                monitoringSensor = preferences.sensor,
                visibleCharts = preferences.visibleCharts,
                saveLocation = preferences.saveLocation,
                isConnected = connection == ConnectionState.Connected
            )
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MonitorUiState())

    fun setSamplingRate(rate: SamplingRate) = client.setSamplingRate(rate)

    fun setImuSamplingRate(rate: ImuSamplingRate) = client.setImuSamplingRate(rate)

    fun setRecording(state: RecordingState) = client.setRecording(state)

    fun useDefaultSaveLocation() = recordingLocation.useDefault()

    fun useSaveFolder(treeUri: Uri) = recordingLocation.useFolder(treeUri)

    fun downloadMemory() = client.downloadMemory()

    fun disconnect() = client.disconnect()
}

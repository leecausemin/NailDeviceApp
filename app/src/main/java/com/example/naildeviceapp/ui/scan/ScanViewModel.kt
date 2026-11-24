package com.example.naildeviceapp.ui.scan

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.naildeviceapp.AppContainer
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

data class ScanUiState(
    val devices: List<ScannedDevice> = emptyList(),
    val connectionState: ConnectionState = ConnectionState.Idle,
    val marks: StreamMarks = StreamMarks(),
    val deviceMode: DeviceMode = DeviceMode.Nail
) {
    val isScanning: Boolean get() = connectionState == ConnectionState.Scanning
    val isConnecting: Boolean get() = connectionState == ConnectionState.Connecting
}

class ScanViewModel(
    private val client: NailDeviceClient = AppContainer.deviceClient
) : ViewModel() {

    val uiState: StateFlow<ScanUiState> =
        combine(
            client.discoveredDevices,
            client.connectionState,
            client.streamMarks,
            client.status
        ) { devices, connection, marks, status ->
            ScanUiState(devices, connection, marks, status.deviceMode)
        }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ScanUiState())

    fun toggleScan() {
        if (uiState.value.isScanning) client.stopScan() else client.startScan()
    }

    fun connect(device: ScannedDevice) = client.connect(device)

    fun selectDeviceMode(mode: DeviceMode) = client.selectDeviceMode(mode)
}

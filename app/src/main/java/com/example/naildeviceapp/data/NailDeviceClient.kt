package com.example.naildeviceapp.data

import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import kotlinx.coroutines.flow.StateFlow

/**
 * Everything the UI needs from the nail device. Implemented today by [MockNailDeviceClient]; a real
 * BLE GATT client can be dropped in behind the same contract without touching the screens.
 */
interface NailDeviceClient {

    val connectionState: StateFlow<ConnectionState>
    val discoveredDevices: StateFlow<List<ScannedDevice>>
    val streamMarks: StateFlow<StreamMarks>
    val status: StateFlow<DeviceStatus>
    val sensors: StateFlow<SensorSnapshot>

    fun startScan()
    fun stopScan()
    fun connect(device: ScannedDevice)
    fun disconnect()

    fun setSamplingRate(rate: SamplingRate)
    fun setImuSamplingRate(rate: ImuSamplingRate)
    fun setGain(percent: Float)
    fun setTimeSync(enabled: Boolean)
    fun setRecording(state: RecordingState)
    fun downloadMemory()
}

package com.example.naildeviceapp.model

/** Device-reported status shown across the top of the monitor screen. */
data class DeviceStatus(
    val deviceName: String = "",
    val timeSyncOn: Boolean = false,
    val memory: MemoryStatus = MemoryStatus.Inactive,
    val memoryUsedPercent: Float = 0f,
    val recording: RecordingState = RecordingState.Off,
    val elapsedSeconds: Int = 0,
    val samplingRate: SamplingRate = SamplingRate.Hz2k,
    val imuSamplingRate: ImuSamplingRate = ImuSamplingRate.Hz416,
    /** Non-null only while a memory dump is in flight; 0..100. */
    val downloadProgress: Int? = null,
    val savedFileName: String = ""
)

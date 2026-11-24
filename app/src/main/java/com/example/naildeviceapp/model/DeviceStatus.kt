package com.example.naildeviceapp.model

/** Device-reported status shown across the top of the monitor screen. */
data class DeviceStatus(
    val deviceMode: DeviceMode = DeviceMode.Nail,
    val deviceName: String = "",
    val boardName: String = "",
    val sensorName: String = "",
    val modeVerified: Boolean = false,
    val hasMicrophone: Boolean = deviceMode.hasMicrophone,
    val memory: MemoryStatus = MemoryStatus.Inactive,
    val memoryUsedPercent: Float = 0f,
    val recording: RecordingState = RecordingState.Off,
    val elapsedSeconds: Int = 0,
    /**
     * The microphone still defaults to the maximum the hardware offers. The IMU no longer does.
     *
     * 6664 Hz was the default because it is what the sensor can do, and every other setting was
     * then bent around making it fit: compression on both streams, and a full second of backlog
     * allowed on the board. Both of those are paid for in the two things that actually matter here
     * — how far behind real time the charts are, and how well the microphone and IMU line up.
     *
     * 1666 Hz inverts that trade. Uncompressed, mic 16 kHz plus IMU 1666 Hz is about 433 kbps
     * against a link measured at 700-770, so there is 40% headroom instead of none. Headroom is
     * what keeps the board's rings empty, and an empty ring is what makes the lag equal to the
     * connection interval rather than to the latency budget. The microphone also goes back to
     * lossless PCM16, dropping ADPCM's ~10% error.
     *
     * What it costs: the IMU now resolves to 833 Hz rather than 3.3 kHz. Anything above that in a
     * tap is still captured by the microphone. Raise this in Settings if the higher band turns out
     * to matter — 3333 Hz fits comfortably with [StreamProfile.Both].
     */
    val samplingRate: SamplingRate = SamplingRate.Hz16k,
    val imuSamplingRate: ImuSamplingRate = deviceMode.defaultImuRate,
    /**
     * Compression the board applies before transmitting.
     *
     * Defaults to [StreamProfile.Raw] — no compression at all — which is only possible because the
     * IMU default came down to 1666 Hz. At 6664 Hz this combination needed 929 kbps and lost about
     * a quarter of the IMU samples; at 1666 Hz it needs about 433 and loses none, so the ADPCM
     * microphone is no longer a price worth paying.
     *
     * If the IMU is raised in Settings, raise this too: 3333 Hz uncompressed is ~598 kbps, close
     * enough to the ceiling that a different phone or a longer distance will start dropping.
     * [StreamProfile.Both] at 3333 Hz is ~317 kbps and safe.
     */
    val streamProfile: StreamProfile = deviceMode.defaultStreamProfile,
    /**
     * False once the board reports `imu_ok=0`, meaning `lsm6_init()` could not reach the LSM6DSL and
     * every IMU sample will be zero. Without surfacing this, a dead sensor and a still sensor draw
     * the identical flat chart.
     */
    val imuOk: Boolean = true,
    /** Non-null only while a memory dump is in flight; 0..100. */
    val downloadProgress: Int? = null,
    val savedFileName: String = ""
) {
    companion object {
        fun forMode(mode: DeviceMode) = DeviceStatus(
            deviceMode = mode,
            hasMicrophone = mode.hasMicrophone,
            imuSamplingRate = mode.defaultImuRate,
            streamProfile = mode.defaultStreamProfile
        )
    }
}

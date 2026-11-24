package com.example.naildeviceapp.data

import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.MemoryStatus
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.ChartWindow
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import com.example.naildeviceapp.model.StreamProfile
import com.example.naildeviceapp.ui.chart.SpectrogramData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.random.Random

private const val TICK_MILLIS = ChartWindow.TICK_MILLIS
private const val TICK_SECONDS = TICK_MILLIS / 1000.0

/**
 * Kept in step with the real client: power, temperature and the spectrogram advance once per
 * analysis pass, not once per frame, so their capacities follow [ChartWindow.ANALYSIS_HZ].
 */
private const val SPECTROGRAM_COLUMNS = ChartWindow.SPECTROGRAM_COLUMNS
private const val SPECTROGRAM_BINS = 64

/** Generated straight at the display rates, so the mock needs no thinning stage of its own. */
private const val GYRO_PER_TICK = ChartWindow.IMU_DISPLAY_HZ / ChartWindow.TICK_HZ
private const val ACCEL_PER_TICK = ChartWindow.IMU_DISPLAY_HZ / ChartWindow.TICK_HZ
private const val MIC_PER_TICK = ChartWindow.MIC_DISPLAY_HZ / ChartWindow.TICK_HZ

/** Samples fed to the DFT; must be at least twice [SPECTROGRAM_BINS]. */
private const val ANALYSIS_WINDOW = 128

/** In-memory stand-in for the real device, driving every screen with synthetic traffic. */
class MockNailDeviceClient(
    private val scope: CoroutineScope,
    private val preferences: MonitoringPreferences
) : NailDeviceClient {

    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow(emptyList<ScannedDevice>())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    private val _streamMarks = MutableStateFlow(StreamMarks())
    override val streamMarks: StateFlow<StreamMarks> = _streamMarks.asStateFlow()

    private val _status = MutableStateFlow(DeviceStatus.forMode(DeviceMode.Nail))
    override val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _sensors = MutableStateFlow(SensorSnapshot.EMPTY)
    override val sensors: StateFlow<SensorSnapshot> = _sensors.asStateFlow()

    private val generator = MockSignalGenerator()
    private val powerBuffer = RollingBuffer(ChartWindow.POWER_CAPACITY)
    private val gyroBuffers = XyzBuffers(ChartWindow.IMU_CAPACITY)
    private val accelBuffers = XyzBuffers(ChartWindow.IMU_CAPACITY)
    private val micBuffer = RollingBuffer(ChartWindow.MIC_CAPACITY)
    private val temperatureBuffer = RollingBuffer(ChartWindow.TEMPERATURE_CAPACITY)
    private val spectrogram = ArrayDeque<FloatArray>(SPECTROGRAM_COLUMNS)
    private val analysisWindow = SlidingWindow(ANALYSIS_WINDOW)
    private val analyzer = SpectrumAnalyzer(ANALYSIS_WINDOW, SPECTROGRAM_BINS)
    private var analysedSensor = MonitoringSensor.Microphone

    /** Counts frames so the analyser runs on a fraction of them, as the real client does. */
    private var analysisFrame = 0

    private var scanJob: Job? = null
    private var streamJob: Job? = null
    private var downloadJob: Job? = null
    private var recordingTicks = 0

    override fun startScan() {
        scanJob?.cancel()
        _discoveredDevices.value = emptyList()
        _streamMarks.value = StreamMarks()
        _connectionState.value = ConnectionState.Scanning

        scanJob = scope.launch {
            MOCK_DEVICES.forEach { name ->
                delay(Random.nextLong(400, 1100))
                if (!isActive) return@launch
                _discoveredDevices.update { current ->
                    current + ScannedDevice(
                        address = "C0:4B:${"%02X".format(current.size)}:1A:9F:${"%02X".format(current.size * 7)}",
                        name = name,
                        rssi = Random.nextInt(-88, -42)
                    )
                }
            }
            _connectionState.value = ConnectionState.Idle
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    override fun connect(device: ScannedDevice) {
        stopScan()
        _connectionState.value = ConnectionState.Connecting

        scope.launch {
            delay(700)
            _status.update { it.copy(deviceName = device.name) }
            _streamMarks.value = StreamMarks(
                imuActive = true,
                micActive = _status.value.hasMicrophone
            )
            _connectionState.value = ConnectionState.Connected
            startStreaming()
        }
    }

    override fun disconnect() {
        streamJob?.cancel()
        streamJob = null
        downloadJob?.cancel()
        downloadJob = null
        recordingTicks = 0
        _connectionState.value = ConnectionState.Idle
        _streamMarks.value = StreamMarks()
        _status.value = DeviceStatus.forMode(_status.value.deviceMode)
        _sensors.value = SensorSnapshot.EMPTY
    }

    override fun selectDeviceMode(mode: DeviceMode) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) return
        preferences.applyDeviceMode(mode)
        _status.value = DeviceStatus.forMode(mode)
    }

    override fun setSamplingRate(rate: SamplingRate) {
        _status.update { it.copy(samplingRate = rate) }
    }

    override fun setImuSamplingRate(rate: ImuSamplingRate) {
        _status.update { it.copy(imuSamplingRate = rate) }
    }

    /** The mock generates already-decoded samples, so the profile only changes what is displayed. */
    override fun setStreamProfile(profile: StreamProfile) {
        _status.update { it.copy(streamProfile = profile) }
    }

    override fun setGain(percent: Float) = Unit

    override fun setRecording(state: RecordingState) {
        recordingTicks = 0
        _status.update { current ->
            when (state) {
                RecordingState.Off -> current.copy(
                    recording = state,
                    memory = MemoryStatus.Inactive,
                    elapsedSeconds = 0,
                    savedFileName = ""
                )

                RecordingState.Recording -> current.copy(
                    recording = state,
                    memory = MemoryStatus.Active,
                    elapsedSeconds = 0
                )

                RecordingState.Stopped -> current.copy(
                    recording = state,
                    memory = MemoryStatus.Saved,
                    memoryUsedPercent = current.memoryUsedPercent.coerceAtLeast(12.5f)
                )
            }
        }
    }

    override fun downloadMemory() {
        if (downloadJob?.isActive == true) return
        downloadJob = scope.launch {
            for (percent in 0..100 step 2) {
                _status.update { it.copy(downloadProgress = percent) }
                delay(80)
            }
            _status.update {
                it.copy(
                    downloadProgress = null,
                    savedFileName = "${it.deviceName}_0721_14_32_05.csv",
                    memory = MemoryStatus.Saved
                )
            }
        }
    }

    private fun startStreaming() {
        streamJob?.cancel()
        resetBuffers()

        streamJob = scope.launch {
            while (isActive) {
                appendTick()
                emitSnapshot()
                trackRecordingTime()
                generator.advance(TICK_SECONDS)
                delay(TICK_MILLIS)
            }
        }
    }

    private fun appendTick() {
        val gyroChunk = generator.gyro(GYRO_PER_TICK, TICK_SECONDS / GYRO_PER_TICK)
        val accelChunk = generator.accel(ACCEL_PER_TICK, TICK_SECONDS / ACCEL_PER_TICK)
        val micChunk = generator.mic(MIC_PER_TICK, TICK_SECONDS / MIC_PER_TICK)

        gyroBuffers.addAll(gyroChunk)
        accelBuffers.addAll(accelChunk)
        micBuffer.addAll(micChunk)
        if (isAnalysisFrame()) temperatureBuffer.add(generator.temperature())

        analyseSelectedSensor(gyroChunk, accelChunk, micChunk)
        analysisFrame++
    }

    /** Mirrors the real client so previews sweep at the same rate the device does. */
    private fun isAnalysisFrame() = analysisFrame % ChartWindow.ANALYSIS_EVERY == 0

    /**
     * Feeds the sensor chosen on the settings screen into the DFT, then derives both spectral views
     * from that one spectrum.
     */
    private fun analyseSelectedSensor(gyro: XyzChunk, accel: XyzChunk, mic: FloatArray) {
        val selected = preferences.sensor.value
        if (selected != analysedSensor) {
            analysedSensor = selected
            analysisWindow.clear()
            powerBuffer.clear()
            spectrogram.clear()
        }

        analysisWindow.addAll(
            when (selected) {
                MonitoringSensor.Microphone -> mic
                MonitoringSensor.AccelX -> accel.x
                MonitoringSensor.AccelY -> accel.y
                MonitoringSensor.AccelZ -> accel.z
                MonitoringSensor.GyroX -> gyro.x
                MonitoringSensor.GyroY -> gyro.y
                MonitoringSensor.GyroZ -> gyro.z
            }
        )

        if (!isAnalysisFrame()) return

        val spectrum = analyzer.magnitudes(analysisWindow.snapshot())
        powerBuffer.add(analyzer.lowBandPower(spectrum))

        if (spectrogram.size == SPECTROGRAM_COLUMNS) spectrogram.removeFirst()
        spectrogram.addLast(spectrum)
    }

    private fun emitSnapshot() {
        _sensors.value = SensorSnapshot(
            power = powerBuffer.snapshot(),
            gyro = gyroBuffers.snapshot(),
            accel = accelBuffers.snapshot(),
            mic = micBuffer.snapshot(),
            spectrogram = SpectrogramData(spectrogram.toList(), SPECTROGRAM_BINS),
            temperature = temperatureBuffer.snapshot(),
            sensorTimeMillis = System.currentTimeMillis()
        )
    }

    private fun trackRecordingTime() {
        if (_status.value.recording != RecordingState.Recording) return
        recordingTicks++
        val seconds = (recordingTicks * TICK_SECONDS).toInt()
        if (seconds != _status.value.elapsedSeconds) {
            _status.update { it.copy(elapsedSeconds = seconds, memoryUsedPercent = seconds * 0.42f) }
        }
    }

    private fun resetBuffers() {
        powerBuffer.clear()
        gyroBuffers.clear()
        accelBuffers.clear()
        micBuffer.clear()
        temperatureBuffer.clear()
        spectrogram.clear()
        analysisWindow.clear()
        analysisFrame = 0
    }

    private companion object {
        val MOCK_DEVICES = listOf("NAIL_A17", "NAIL_B03", "BAS_Sensor_02", "NAIL_C41")
    }
}

/** Three [RollingBuffer]s kept in lockstep for a tri-axial sensor. */
private class XyzBuffers(capacity: Int) {
    private val x = RollingBuffer(capacity)
    private val y = RollingBuffer(capacity)
    private val z = RollingBuffer(capacity)

    fun addAll(chunk: XyzChunk) {
        x.addAll(chunk.x)
        y.addAll(chunk.y)
        z.addAll(chunk.z)
    }

    fun snapshot() = XyzWindow(x.snapshot(), y.snapshot(), z.snapshot())

    fun clear() {
        x.clear()
        y.clear()
        z.clear()
    }
}

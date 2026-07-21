package com.example.naildeviceapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.util.Log
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.RollingBuffer
import com.example.naildeviceapp.data.SensorSnapshot
import com.example.naildeviceapp.data.SlidingWindow
import com.example.naildeviceapp.data.SpectrumAnalyzer
import com.example.naildeviceapp.data.XyzWindow
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.MemoryStatus
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import com.example.naildeviceapp.ui.chart.SpectrogramData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.abs

/**
 * Real BLE backend for [NailDeviceClient], talking to the nail firmware over Nordic UART.
 *
 * Streaming starts as soon as the link is up, so the charts are live the moment you connect;
 * "recording" only decides whether those same samples also go to disk. That is why starting a
 * recording is instant and its length is unbounded — see [StreamRecorder].
 *
 * Notifications arrive on a binder thread and are handed straight to [packets]. Everything
 * downstream — decoding, the display buffers, the spectral window and the recorder — runs on
 * [streamDispatcher], a single thread. The buffers are plain ArrayLists and RollingBuffers with no
 * locking, so confining them to one thread is what keeps them safe; the channel is the only
 * hand-off point that crosses threads.
 */
@SuppressLint("MissingPermission")
class NailBleClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: MonitoringPreferences
) : NailDeviceClient {

    private val _connectionState = MutableStateFlow(ConnectionState.Idle)
    override val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

    private val _discoveredDevices = MutableStateFlow(emptyList<ScannedDevice>())
    override val discoveredDevices: StateFlow<List<ScannedDevice>> = _discoveredDevices.asStateFlow()

    private val _streamMarks = MutableStateFlow(StreamMarks())
    override val streamMarks: StateFlow<StreamMarks> = _streamMarks.asStateFlow()

    private val _status = MutableStateFlow(DeviceStatus())
    override val status: StateFlow<DeviceStatus> = _status.asStateFlow()

    private val _sensors = MutableStateFlow(SensorSnapshot.EMPTY)
    override val sensors: StateFlow<SensorSnapshot> = _sensors.asStateFlow()

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val recorder = StreamRecorder(context)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    private val packets = Channel<NailPacket>(Channel.UNLIMITED)

    /**
     * Single thread owning every buffer below. Two coroutines touch them — the packet consumer and
     * the chart emitter — and on a multi-threaded dispatcher that races (an ArrayList read while
     * being appended throws ConcurrentModificationException).
     */
    private val streamDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nail-stream") }.asCoroutineDispatcher()

    private var consumerJob: Job? = null
    private var emitJob: Job? = null
    private var scanJob: Job? = null

    // ---- Display buffers (same shapes the mock used, so the charts behave identically) ----
    private val powerBuffer = RollingBuffer(POWER_CAPACITY)
    private val gyroBuffers = XyzBuffers(IMU_CAPACITY)
    private val accelBuffers = XyzBuffers(IMU_CAPACITY)
    private val micBuffer = RollingBuffer(MIC_CAPACITY)
    private val temperatureBuffer = RollingBuffer(TEMPERATURE_CAPACITY)
    private val spectrogram = ArrayDeque<FloatArray>(SPECTROGRAM_COLUMNS)
    private val analysisWindow = SlidingWindow(ANALYSIS_WINDOW)
    private val analyzer = SpectrumAnalyzer(ANALYSIS_WINDOW, SPECTROGRAM_BINS)
    private var analysedSensor = MonitoringSensor.Microphone

    /** Samples accumulated since the last chart frame. */
    private val micTickRaw = ArrayList<Float>(2048)
    private val gyroTick = XyzAccumulator()
    private val accelTick = XyzAccumulator()

    private var micSampleRate = NailProtocol.MIC_BASE_RATE_HZ
    private var micDecimation = 1
    private var micDecimAccumulator = 0
    private var micDecimPeak = 0f

    // ---- Link quality, logged rather than shown (DeviceStatus has no field for it) ----
    private var expectedMicIndex = -1L
    private var micReceived = 0L
    private var micLost = 0L
    private var recordingStartedAt = 0L

    private var pendingRate: SamplingRate? = null
    private var pendingImuRate: ImuSamplingRate? = null

    // =================================================================================
    //  Scanning
    // =================================================================================

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device ?: return
            val name = result.scanRecord?.deviceName ?: runCatching { device.name }.getOrNull()
            if (name.isNullOrBlank()) return

            _discoveredDevices.update { current ->
                val existing = current.indexOfFirst { it.address == device.address }
                val entry = ScannedDevice(device.address, name, result.rssi)
                if (existing >= 0) current.toMutableList().apply { set(existing, entry) }
                else current + entry
            }
        }

        override fun onScanFailed(errorCode: Int) {
            Log.e(TAG, "scan failed: $errorCode")
            _connectionState.value = ConnectionState.Idle
        }
    }

    override fun startScan() {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            Log.e(TAG, "no BLE scanner — bluetooth off or unsupported")
            return
        }

        stopScan()
        _discoveredDevices.value = emptyList()
        _streamMarks.value = StreamMarks()
        _connectionState.value = ConnectionState.Scanning

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        runCatching { scanner.startScan(null, settings, scanCallback) }
            .onFailure { Log.e(TAG, "startScan threw", it) }

        // Scanning forever drains the battery and Android throttles it anyway.
        scanJob = scope.launch {
            delay(SCAN_TIMEOUT_MILLIS)
            if (_connectionState.value == ConnectionState.Scanning) stopScan()
        }
    }

    override fun stopScan() {
        scanJob?.cancel()
        scanJob = null
        runCatching { adapter?.bluetoothLeScanner?.stopScan(scanCallback) }
        if (_connectionState.value == ConnectionState.Scanning) {
            _connectionState.value = ConnectionState.Idle
        }
    }

    // =================================================================================
    //  Connection
    // =================================================================================

    override fun connect(device: ScannedDevice) {
        val adapter = adapter ?: return
        stopScan()
        _connectionState.value = ConnectionState.Connecting

        val remote: BluetoothDevice = runCatching { adapter.getRemoteDevice(device.address) }
            .getOrElse {
                Log.e(TAG, "bad address ${device.address}", it)
                _connectionState.value = ConnectionState.Idle
                return
            }

        _status.update { it.copy(deviceName = device.name) }
        gatt = remote.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    override fun disconnect() {
        runCatching { writeCommand(NailProtocol.CMD_STOP) }

        consumerJob?.cancel(); consumerJob = null
        emitJob?.cancel(); emitJob = null

        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rxCharacteristic = null

        // Close the file and clear the buffers on their owning thread, after the jobs are gone.
        scope.launch(streamDispatcher) {
            finishRecordingIfAny()
            resetBuffers()
        }

        _connectionState.value = ConnectionState.Idle
        _streamMarks.value = StreamMarks()
        _status.value = DeviceStatus()
        _sensors.value = SensorSnapshot.EMPTY
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothGatt.STATE_CONNECTED) {
                Log.i(TAG, "connected, negotiating link")
                // Ask for the fastest connection interval Android will grant (~11.25 ms).
                gatt.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
                gatt.discoverServices()
            } else if (newState == BluetoothGatt.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected (status=$status)")
                scope.launch { disconnect() }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            val service = gatt.getService(NailProtocol.NUS_SERVICE)
            if (service == null) {
                Log.e(TAG, "NUS service missing — wrong device?")
                scope.launch { disconnect() }
                return
            }
            rxCharacteristic = service.getCharacteristic(NailProtocol.NUS_RX)
            // Bigger MTU means fewer packets for the same data; 247 matches the firmware.
            gatt.requestMtu(REQUESTED_MTU)
        }

        override fun onMtuChanged(gatt: BluetoothGatt, mtu: Int, status: Int) {
            Log.i(TAG, "MTU = $mtu")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // 2M PHY doubles the on-air rate. Declined silently if unsupported.
                gatt.setPreferredPhy(BluetoothDevice.PHY_LE_2M_MASK, BluetoothDevice.PHY_LE_2M_MASK, 0)
            }
            enableNotifications(gatt)
        }

        override fun onPhyUpdate(gatt: BluetoothGatt, txPhy: Int, rxPhy: Int, status: Int) {
            Log.i(TAG, "PHY tx=$txPhy rx=$rxPhy (2 = 2M)")
        }

        override fun onDescriptorWrite(
            gatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            Log.i(TAG, "notifications enabled (status=$status)")
            onLinkReady()
        }

        // API 33+
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleNotification(value)
        }

        @Deprecated("Required for API < 33")
        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            characteristic.value?.let { handleNotification(it) }
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val tx = gatt.getService(NailProtocol.NUS_SERVICE)
            ?.getCharacteristic(NailProtocol.NUS_TX) ?: return

        gatt.setCharacteristicNotification(tx, true)
        val cccd = tx.getDescriptor(NailProtocol.CCCD) ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
    }

    private fun onLinkReady() {
        // Called on a binder thread, so the buffer reset has to be queued onto the stream thread
        // like every other touch of that state. It lands before the consumer's first packet
        // because the dispatcher runs its queue in order.
        scope.launch(streamDispatcher) {
            resetBuffers()
            micReceived = 0
            micLost = 0
            expectedMicIndex = -1
        }

        startConsumer()
        startEmitter()

        _connectionState.value = ConnectionState.Connected
        _streamMarks.value = StreamMarks(imuActive = true, micActive = true)

        // Apply a rate chosen before we were connected, then go live.
        pendingRate?.let { writeCommand(NailProtocol.cmdSetRate(it.kHz)) }
        pendingRate = null
        pendingImuRate?.let { writeCommand(NailProtocol.cmdSetImuRate(it.hz)) }
        pendingImuRate = null
        writeCommand(NailProtocol.CMD_START)
    }

    private fun handleNotification(value: ByteArray) {
        decodeNailPacket(value)?.let { packets.trySend(it) }
    }

    private fun writeCommand(bytes: ByteArray) {
        val gatt = gatt ?: return
        val rx = rxCharacteristic ?: return

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE)
        } else {
            @Suppress("DEPRECATION")
            run {
                rx.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
                rx.value = bytes
                gatt.writeCharacteristic(rx)
            }
        }
    }

    // =================================================================================
    //  Packet processing
    // =================================================================================

    private fun startConsumer() {
        consumerJob?.cancel()
        consumerJob = scope.launch(streamDispatcher) {
            // Anything queued by a previous session would be replayed with stale sample indices.
            while (packets.tryReceive().isSuccess) { /* drain */ }

            while (isActive) {
                val packet = packets.receive()
                // One malformed packet must not take the app down mid-recording.
                runCatching {
                    when (packet) {
                        is NailPacket.Mic -> onMic(packet)
                        is NailPacket.Imu -> onImu(packet)
                        is NailPacket.Text -> onText(packet.line)
                    }
                }.onFailure { Log.e(TAG, "packet handling failed", it) }
            }
        }
    }

    private fun onMic(packet: NailPacket.Mic) {
        // A jump in the absolute index is exactly how many samples never arrived.
        if (expectedMicIndex >= 0 && packet.index > expectedMicIndex) {
            micLost += packet.index - expectedMicIndex
        }
        expectedMicIndex = packet.index + packet.samples.size
        micReceived += packet.samples.size

        if (recorder.isRecording) recorder.appendMic(packet.index, packet.samples)

        for (raw in packet.samples) {
            val value = raw / NailProtocol.MIC_FULL_SCALE
            micTickRaw += value

            // Peak-preserving decimation: plain sub-sampling makes a waveform look quieter
            // than it is, because it keeps missing the peaks.
            if (abs(value) > abs(micDecimPeak)) micDecimPeak = value
            if (++micDecimAccumulator >= micDecimation) {
                micBuffer.add(micDecimPeak)
                micDecimAccumulator = 0
                micDecimPeak = 0f
            }
        }
    }

    private fun onImu(packet: NailPacket.Imu) {
        if (recorder.isRecording) recorder.appendImu(packet.index, packet.samples)

        for (s in packet.samples) {
            accelBuffers.add(s.ax, s.ay, s.az)
            gyroBuffers.add(s.gx, s.gy, s.gz)
            accelTick.add(s.ax, s.ay, s.az)
            gyroTick.add(s.gx, s.gy, s.gz)
        }
    }

    private fun onText(line: String) {
        Log.i(TAG, "board: $line")

        if (line.startsWith("RATE,")) {
            // imu_ok=0 means the board could not talk to the LSM6DSL at boot, so every IMU
            // sample will be zero. Without this the charts just look flat for no visible reason.
            if (line.contains("imu_ok=0")) {
                Log.e(TAG, "IMU init FAILED on the board — IMU data will be all zeros. Reset the board.")
            }
            line.removePrefix("RATE,").split(",").forEach { field ->
                if (field.startsWith("mic_hz=")) {
                    field.removePrefix("mic_hz=").toIntOrNull()?.let { hz ->
                        micSampleRate = hz
                        val rate = SamplingRate.entries.minByOrNull { abs(it.kHz * 1000 - hz) }
                        if (rate != null) {
                            _status.update { it.copy(samplingRate = rate) }
                        }
                        // Aim for roughly MIC_DISPLAY_HZ points per second on the waveform.
                        micDecimation = (hz / MIC_DISPLAY_HZ).coerceAtLeast(1)
                        micDecimAccumulator = 0
                        micDecimPeak = 0f
                        micBuffer.clear()
                        analysisWindow.clear()
                        expectedMicIndex = -1
                        Log.i(TAG, "mic rate $hz Hz, display decimation $micDecimation")
                    }
                }
                if (field.startsWith("imu_hz=")) {
                    field.removePrefix("imu_hz=").toIntOrNull()?.let { hz ->
                        val rate = ImuSamplingRate.entries.minByOrNull { abs(it.hz - hz) }
                        if (rate != null) {
                            _status.update { it.copy(imuSamplingRate = rate) }
                        }
                    }
                }
            }
        }
    }

    // =================================================================================
    //  Chart frames
    // =================================================================================

    private fun startEmitter() {
        emitJob?.cancel()
        emitJob = scope.launch(streamDispatcher) {
            while (isActive) {
                runCatching {
                    analyseTick()
                    emitSnapshot()
                    trackRecordingTime()
                }.onFailure { Log.e(TAG, "chart frame failed", it) }
                delay(TICK_MILLIS)
            }
        }
    }

    /**
     * Feeds the selected sensor into the DFT. The analysis window gets *full-rate* mic samples, not
     * the decimated ones — at 600 Hz the spectrogram could only show 0-300 Hz, which throws away
     * almost everything the microphone hears.
     */
    private fun analyseTick() {
        val selected = preferences.sensor.value
        if (selected != analysedSensor) {
            analysedSensor = selected
            analysisWindow.clear()
            powerBuffer.clear()
            spectrogram.clear()
        }

        val source = when (selected) {
            MonitoringSensor.Microphone -> micTickRaw.toFloatArray()
            MonitoringSensor.AccelX -> accelTick.x()
            MonitoringSensor.AccelY -> accelTick.y()
            MonitoringSensor.AccelZ -> accelTick.z()
            MonitoringSensor.GyroX -> gyroTick.x()
            MonitoringSensor.GyroY -> gyroTick.y()
            MonitoringSensor.GyroZ -> gyroTick.z()
        }

        micTickRaw.clear()
        accelTick.clear()
        gyroTick.clear()

        if (source.isEmpty()) return

        analysisWindow.addAll(source)
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
        val seconds = ((System.currentTimeMillis() - recordingStartedAt) / 1000L).toInt()
        if (seconds != _status.value.elapsedSeconds) {
            _status.update { it.copy(elapsedSeconds = seconds) }
            if (seconds % LOSS_LOG_PERIOD_SECONDS == 0 && seconds > 0) logLinkQuality()
        }
    }

    private fun logLinkQuality() {
        val expected = micReceived + micLost
        val lossPercent = if (expected > 0) 100.0 * micLost / expected else 0.0
        val kbps = micReceived * 2 * 8 / 1000.0 /
            ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(1) / 1000.0)
        Log.i(TAG, "link: mic %d received, %d lost (%.2f%%), ~%.0f kbps"
            .format(micReceived, micLost, lossPercent, kbps))
    }

    // =================================================================================
    //  Controls
    // =================================================================================

    override fun setSamplingRate(rate: SamplingRate) {
        _status.update { it.copy(samplingRate = rate) }
        if (_connectionState.value == ConnectionState.Connected) {
            writeCommand(NailProtocol.cmdSetRate(rate.kHz))
        } else {
            pendingRate = rate
        }
    }

    override fun setImuSamplingRate(rate: ImuSamplingRate) {
        _status.update { it.copy(imuSamplingRate = rate) }
        if (_connectionState.value == ConnectionState.Connected) {
            writeCommand(NailProtocol.cmdSetImuRate(rate.hz))
        } else {
            pendingImuRate = rate
        }
    }

    /** Not supported by this firmware. */
    override fun setGain(percent: Float) = Unit

    /** Not supported by this firmware. */
    override fun setTimeSync(enabled: Boolean) = Unit

    override fun setRecording(state: RecordingState) {
        when (state) {
            RecordingState.Recording -> {
                recordingStartedAt = System.currentTimeMillis()
                // The recorder is written to from the stream thread, so open it there too —
                // otherwise start() races with an in-flight appendMic().
                scope.launch(streamDispatcher) {
                    micReceived = 0
                    micLost = 0
                    recorder.start(micSampleRate)
                }
                _status.update {
                    it.copy(
                        recording = state,
                        memory = MemoryStatus.Active,
                        elapsedSeconds = 0,
                        savedFileName = ""
                    )
                }
            }

            RecordingState.Stopped -> {
                _status.update { it.copy(recording = state, memory = MemoryStatus.Saved) }
                // File name is reported after both CSV streams are flushed and published.
                scope.launch(streamDispatcher) {
                    val result = recorder.stop()
                    logLinkQuality()
                    _status.update { it.copy(savedFileName = result?.fileName.orEmpty()) }
                }
            }

            RecordingState.Off -> {
                scope.launch(streamDispatcher) { finishRecordingIfAny() }
                _status.update {
                    it.copy(
                        recording = state,
                        memory = MemoryStatus.Inactive,
                        elapsedSeconds = 0,
                        savedFileName = ""
                    )
                }
            }
        }
    }

    /** The device has no on-board storage — recordings already land on the phone. */
    override fun downloadMemory() = Unit

    private fun finishRecordingIfAny() {
        if (recorder.isRecording) recorder.stop()
    }

    private fun resetBuffers() {
        powerBuffer.clear()
        gyroBuffers.clear()
        accelBuffers.clear()
        micBuffer.clear()
        temperatureBuffer.clear()
        spectrogram.clear()
        analysisWindow.clear()
        micTickRaw.clear()
        accelTick.clear()
        gyroTick.clear()
        micDecimAccumulator = 0
        micDecimPeak = 0f
    }

    private companion object {
        const val TAG = "NailBleClient"

        const val REQUESTED_MTU = 247
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val TICK_MILLIS = 50L
        const val LOSS_LOG_PERIOD_SECONDS = 5

        /** Waveform points per second; the chart cannot show 16 000. */
        const val MIC_DISPLAY_HZ = 600

        const val POWER_CAPACITY = 200
        const val TEMPERATURE_CAPACITY = 200
        const val IMU_CAPACITY = 500
        const val MIC_CAPACITY = 600
        const val SPECTROGRAM_COLUMNS = 140
        const val SPECTROGRAM_BINS = 64
        const val ANALYSIS_WINDOW = 128
    }
}

/** Three [RollingBuffer]s kept in lockstep for a tri-axial sensor. */
private class XyzBuffers(capacity: Int) {
    private val x = RollingBuffer(capacity)
    private val y = RollingBuffer(capacity)
    private val z = RollingBuffer(capacity)

    fun add(xv: Float, yv: Float, zv: Float) {
        x.add(xv); y.add(yv); z.add(zv)
    }

    fun snapshot() = XyzWindow(x.snapshot(), y.snapshot(), z.snapshot())

    fun clear() {
        x.clear(); y.clear(); z.clear()
    }
}

/** Per-frame scratch for the spectral analyser. */
private class XyzAccumulator {
    private val xs = ArrayList<Float>(64)
    private val ys = ArrayList<Float>(64)
    private val zs = ArrayList<Float>(64)

    fun add(x: Float, y: Float, z: Float) {
        xs += x; ys += y; zs += z
    }

    fun x() = xs.toFloatArray()
    fun y() = ys.toFloatArray()
    fun z() = zs.toFloatArray()

    fun clear() {
        xs.clear(); ys.clear(); zs.clear()
    }
}

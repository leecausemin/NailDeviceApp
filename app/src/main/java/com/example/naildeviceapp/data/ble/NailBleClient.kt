package com.example.naildeviceapp.data.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.RecordingLocationPreferences
import com.example.naildeviceapp.data.RollingBuffer
import com.example.naildeviceapp.data.SensorSnapshot
import com.example.naildeviceapp.data.SlidingWindow
import com.example.naildeviceapp.data.SpectrumAnalyzer
import com.example.naildeviceapp.data.XyzWindow
import com.example.naildeviceapp.model.ChartWindow
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.MemoryStatus
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import com.example.naildeviceapp.model.StreamProfile
import com.example.naildeviceapp.ui.chart.SpectrogramData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
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
 * Notifications arrive on a binder thread, are decoded and counted there, and then fan out into
 * three queues with three different jobs:
 *
 *  - [chartPackets] -> [streamDispatcher]. Bounded, drops the oldest. The display must be current;
 *    a packet that waited in a queue is already history.
 *  - [recorderPackets] -> [recorderDispatcher]. Unbounded, drops nothing. The file must be
 *    complete; when it lands does not matter.
 *  - [textPackets] -> [streamDispatcher]. Unbounded. Status lines are rare and each carries state.
 *
 * Splitting them is the point. While all three shared one unbounded queue on one thread, each got
 * whichever property was worse: the recording made the charts late, and the charts' queue made the
 * recording's delay unbounded. Neither could be fixed without giving up the other.
 *
 * The display buffers are plain arrays with no locking, so confining them to [streamDispatcher] is
 * what keeps them safe; the channels are the only hand-off points that cross threads.
 */
@SuppressLint("MissingPermission")
class NailBleClient(
    private val context: Context,
    private val scope: CoroutineScope,
    private val preferences: MonitoringPreferences,
    recordingLocation: RecordingLocationPreferences
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

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter

    private val recorder = StreamRecorder(context, recordingLocation)

    private var gatt: BluetoothGatt? = null
    private var rxCharacteristic: BluetoothGattCharacteristic? = null

    /**
     * Sensor packets on their way to the **charts**.
     *
     * Bounded, and it drops the *oldest* when full. That is the opposite of what a recording wants
     * and exactly what a live display wants: a packet that has been waiting in this queue is, by
     * definition, older than what the chart should be showing, and drawing it first only pushes
     * everything after it further into the past.
     *
     * It used to be `Channel.UNLIMITED`, shared with the recording. That made the queue itself an
     * unbounded source of delay *inside the phone* — invisible to the board's `ilag`/`mlag`,
     * because the notifications had already been received and acknowledged. The board looked
     * healthy while the charts ran seconds behind, which is why lowering the board's latency budget
     * never fixed it.
     */
    private val chartPackets = Channel<NailPacket>(
        capacity = CHART_QUEUE_DEPTH,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * The same packets on their way to the **recording**, which must see every one of them in
     * order. Unlimited on purpose: dropping here would put a hole in the CSV, and the consumer only
     * hands work to [StreamRecorder]'s writer thread, so it cannot fall far behind.
     */
    private val recorderPackets = Channel<NailPacket>(Channel.UNLIMITED)

    /**
     * Status lines. Kept off [chartPackets] so a chart-side drop can never lose a SYNC, RATE or
     * HEALTH line — those are rare, cheap, and each one carries state the app has to act on.
     */
    private val textPackets = Channel<NailPacket.Text>(Channel.UNLIMITED)

    /** Depth of [chartPackets], for the `lag:` log. A [Channel] does not expose its own size. */
    private val chartQueued = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Packets [chartPackets] discarded because the charts were behind.
     *
     * Not a fault, and deliberately not counted as link loss: the radio delivered these and the
     * recording still has them. It is the charts choosing to be current instead of complete. Worth
     * logging because a number that climbs steadily means the app cannot keep up at these rates,
     * which is a different problem from the link not carrying them.
     */
    private val chartDropped = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Single thread owning every buffer below. Two coroutines touch them — the packet consumer and
     * the chart emitter — and on a multi-threaded dispatcher that races (an ArrayList read while
     * being appended throws ConcurrentModificationException).
     */
    private val streamDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nail-stream") }.asCoroutineDispatcher()

    /**
     * Owns the recording. Separate from [streamDispatcher] so that the two paths cannot delay each
     * other: the recording must see every packet and does not care when, the charts must be current
     * and can skip. Sharing one thread forced both to have whichever property was worse.
     */
    private val recorderDispatcher =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nail-record") }.asCoroutineDispatcher()

    private var consumerJob: Job? = null
    private var recorderJob: Job? = null
    private var textJob: Job? = null
    private var emitJob: Job? = null
    private var scanJob: Job? = null

    // ---- Display buffers (same shapes the mock used, so the charts behave identically) ----
    private val powerBuffer = RollingBuffer(ChartWindow.POWER_CAPACITY)
    private val gyroBuffers = XyzBuffers(ChartWindow.IMU_CAPACITY)
    private val accelBuffers = XyzBuffers(ChartWindow.IMU_CAPACITY)
    private val micBuffer = RollingBuffer(ChartWindow.MIC_CAPACITY)
    private val temperatureBuffer = RollingBuffer(ChartWindow.TEMPERATURE_CAPACITY)

    /** Rewrite each frame's arrivals as a fixed number of points, so every chart shares one clock. */
    private val micResampler = TickResampler(ChartWindow.MIC_POINTS_PER_TICK)
    private val accelResampler = XyzTickResampler(ChartWindow.IMU_POINTS_PER_TICK)
    private val gyroResampler = XyzTickResampler(ChartWindow.IMU_POINTS_PER_TICK)

    /** Counts chart frames so the analyser can run on a fraction of them. */
    private var analysisFrame = 0

    /** Applies the board's SYNC offset to the live charts. See [DelayLine]. */
    private val micDelay = DelayLine()
    private val accelDelay = XyzDelayLine()
    private val gyroDelay = XyzDelayLine()
    private var appliedOffsetUs: Int? = null
    private val spectrogram = ArrayDeque<FloatArray>(ChartWindow.SPECTROGRAM_COLUMNS)
    private val analysisWindow = SlidingWindow(ANALYSIS_WINDOW)
    private val analyzer = SpectrumAnalyzer(ANALYSIS_WINDOW, SPECTROGRAM_BINS)
    private var analysedSensor = MonitoringSensor.Microphone

    /** Samples accumulated since the last chart frame. */
    private val micTickRaw = FloatAccumulator(4096)
    private val gyroTick = XyzAccumulator()
    private val accelTick = XyzAccumulator()

    private var micSampleRate = NailProtocol.MIC_BASE_RATE_HZ

    /** Board-measured gap between the two streams' sample 0; null until it reports one. */
    private var streamOriginOffsetUs: Int? = null

    /** Held so an empty frame parks the power trace instead of stalling its sweep. */
    private var lastPower = 0f

    // ---- Link quality, logged rather than shown (DeviceStatus has no field for it) ----
    //
    // Written only by [countArrival] on the GATT callback thread, which sees every packet once and
    // in order. Read from the stream thread when logging and cleared from whichever thread starts a
    // recording, hence @Volatile — a torn 64-bit read here would print nonsense. The clears do race
    // with an arrival in flight, which can cost one packet's worth of counting at the instant a
    // recording starts; that is a diagnostic off by a few samples, not a decision anything is made
    // on, and the alternative is a lock on the hot path.
    @Volatile
    private var expectedMicIndex = -1L
    @Volatile
    private var micReceived = 0L
    @Volatile
    private var micLost = 0L
    // IMU loss is tracked separately because the two streams fail independently: raising the IMU
    // rate can starve the microphone, or the IMU can be the one that gets dropped. Watching only
    // the microphone would make a saturated link look healthy from the wrong side.
    @Volatile
    private var expectedImuIndex = -1L
    @Volatile
    private var imuReceived = 0L
    @Volatile
    private var imuLost = 0L
    private var recordingStartedAt = 0L

    /** Commands are serialised through here; see [writeCommand]. */
    private val commands = Channel<ByteArray>(Channel.UNLIMITED)
    private var commandJob: Job? = null
    @Volatile
    private var writeAck: CompletableDeferred<Unit>? = null

    /** Written from the GATT callback thread, read from the stream thread — hence atomic. */
    private val wireBytes = java.util.concurrent.atomic.AtomicLong(0)
    private val wirePackets = java.util.concurrent.atomic.AtomicLong(0)

    /**
     * Profile writes queued but not yet reflected in a RATE line. Queued from the connection and UI
     * threads, consumed on the stream thread; see [onProfile].
     */
    private val profileWritesInFlight = java.util.concurrent.atomic.AtomicInteger(0)

    /** One resend per connection, so a rejected profile cannot ping-pong forever. */
    @Volatile
    private var profileResent = false

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
        // Stop the writer and drop anything still queued before the final command: a stale
        // profile or rate write must not outlive this connection, and CMD_STOP has to go out
        // now rather than after a coroutine hop, because the GATT is closed a few lines below.
        commandJob?.cancel(); commandJob = null
        @Suppress("ControlFlowWithEmptyBody")
        while (commands.tryReceive().isSuccess) { }
        runCatching { writeNow(NailProtocol.CMD_STOP) }

        consumerJob?.cancel(); consumerJob = null
        recorderJob?.cancel(); recorderJob = null
        textJob?.cancel(); textJob = null
        emitJob?.cancel(); emitJob = null

        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
        rxCharacteristic = null

        // Close the file and clear the buffers on their owning threads, after the jobs are gone.
        scope.launch(recorderDispatcher) { finishRecordingIfAny() }
        scope.launch(streamDispatcher) { resetBuffers() }

        _connectionState.value = ConnectionState.Idle
        _streamMarks.value = StreamMarks()
        _status.value = DeviceStatus.forMode(_status.value.deviceMode)
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e(TAG, "MTU negotiation failed (status=$status)")
                scope.launch { disconnect() }
                return
            }
            Log.i(TAG, "MTU = $mtu")
            // The peripheral already requests 2M PHY when the link opens. Starting another PHY
            // procedure here and immediately writing the CCCD overlaps two asynchronous BLE
            // operations on some Samsung stacks and intermittently strands the descriptor write.
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
            if (descriptor.uuid != NailProtocol.CCCD) return
            if (status == BluetoothGatt.GATT_SUCCESS) {
                Log.i(TAG, "notifications enabled")
                onLinkReady()
            } else {
                // Never send rate/profile/START when CCCD setup failed. Previously status 133 was
                // logged as success, so the UI entered Monitor even though no packet could arrive.
                Log.e(TAG, "notification CCCD write failed (status=$status)")
                scope.launch { disconnect() }
            }
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

        /** Releases the next queued command; see [writeCommand]. */
        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            writeAck?.complete(Unit)
        }
    }

    private fun enableNotifications(gatt: BluetoothGatt) {
        val tx = gatt.getService(NailProtocol.NUS_SERVICE)
            ?.getCharacteristic(NailProtocol.NUS_TX) ?: return

        if (!gatt.setCharacteristicNotification(tx, true)) {
            Log.e(TAG, "local notification registration failed")
            scope.launch { disconnect() }
            return
        }
        val cccd = tx.getDescriptor(NailProtocol.CCCD)
        if (cccd == null) {
            Log.e(TAG, "notification CCCD missing")
            scope.launch { disconnect() }
            return
        }

        val queued = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) ==
                BluetoothStatusCodes.SUCCESS
        } else {
            @Suppress("DEPRECATION")
            run {
                cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                gatt.writeDescriptor(cccd)
            }
        }
        if (!queued) {
            Log.e(TAG, "notification CCCD write was not queued")
            scope.launch { disconnect() }
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
            imuReceived = 0
            imuLost = 0
            expectedImuIndex = -1
        }

        startConsumer()
        startEmitter()
        startCommandWriter()   // must be running before anything below is queued

        _connectionState.value = ConnectionState.Connected
        _streamMarks.value = StreamMarks(
            imuActive = true,
            micActive = _status.value.hasMicrophone
        )

        // All three are sent every time, not just when the user changed one. The board comes up on
        // its own defaults and announces them in a RATE line, which [onText] writes into _status —
        // so staying silent here does not leave the app's settings alone, it replaces them with the
        // board's. Pushing first makes _status the side that wins.
        val settings = _status.value
        profileWritesInFlight.set(0)
        profileResent = false
        if (settings.hasMicrophone) {
            writeCommand(NailProtocol.cmdSetRate(settings.samplingRate.kHz))
        }
        writeCommand(NailProtocol.cmdSetImuRate(settings.imuSamplingRate.hz))
        writeProfile(settings.streamProfile)
        // Connecting always lands in monitoring: the stream starts here and a recording can only
        // be armed afterwards. Sent explicitly rather than relying on the board's power-on default
        // so that a board left in recording mode by a previous session does not carry a second of
        // lag into this one.
        writeCommand(NailProtocol.cmdSetLatency(NailProtocol.LATENCY_MONITOR_MS))
        writeCommand(NailProtocol.CMD_START)
    }

    private fun handleNotification(value: ByteArray) {
        // Measured on the GATT callback thread, before decoding, so this is what actually crossed
        // the air. Under a compressed profile the decoded sample count says nothing about link
        // load — that is the whole point of compressing — so throughput has to be counted here.
        wireBytes.addAndGet(value.size.toLong())
        wirePackets.incrementAndGet()

        val packet = decodeNailPacket(
            value,
            gyroDpsPerLsb = _status.value.deviceMode.gyroDpsPerLsb
        ) ?: return

        // Loss accounting lives here, on the GATT thread, rather than in the chart consumer.
        // [chartPackets] is allowed to drop, and a counter fed from a lossy queue would report the
        // app's own drops as packets the radio never delivered. This thread sees every packet that
        // arrived, exactly once, in order.
        countArrival(packet)

        when (packet) {
            is NailPacket.Text -> textPackets.trySend(packet)
            else -> {
                // trySend on a DROP_OLDEST channel always reports success — it discards rather than
                // refuses — so the result says nothing and the depth has to be tracked by hand.
                // Reading it before sending is what keeps the count from drifting upward every time
                // a packet is silently dropped.
                if (chartQueued.get() >= CHART_QUEUE_DEPTH) {
                    chartDropped.incrementAndGet()
                } else {
                    chartQueued.incrementAndGet()
                }
                chartPackets.trySend(packet)

                if (recorder.isRecording) recorderPackets.trySend(packet)
            }
        }
    }

    /** Absolute indices are contiguous, so a jump is exactly the number of samples that were lost. */
    private fun countArrival(packet: NailPacket) {
        when (packet) {
            is NailPacket.Mic -> {
                if (expectedMicIndex >= 0 && packet.index > expectedMicIndex) {
                    micLost += packet.index - expectedMicIndex
                }
                expectedMicIndex = packet.index + packet.samples.size
                micReceived += packet.samples.size
            }

            is NailPacket.Imu -> {
                if (expectedImuIndex >= 0 && packet.index > expectedImuIndex) {
                    imuLost += packet.index - expectedImuIndex
                }
                expectedImuIndex = packet.index + packet.samples.size
                imuReceived += packet.samples.size
            }

            is NailPacket.Text -> Unit
        }
    }

    /**
     * Queues a command. **Never write to GATT directly** — Android allows one outstanding
     * operation per connection and silently discards the rest, so two calls in a row lose the
     * second one. That is not hypothetical: sending the stream profile immediately before
     * CMD_START dropped the start, and the board sat connected but streaming nothing while every
     * status line still came through, which looks like a dead sensor rather than a lost write.
     */
    private fun writeCommand(bytes: ByteArray) {
        commands.trySend(bytes)
    }

    /** Drains [commands] one at a time, waiting for each write to be acknowledged. */
    private fun startCommandWriter() {
        commandJob?.cancel()
        commandJob = scope.launch {
            for (bytes in commands) {
                writeAck = CompletableDeferred()
                if (!writeNow(bytes)) {
                    writeAck = null
                    continue
                }
                // The callback is the normal path; the timeout only keeps a missing one from
                // wedging the queue forever, which would strand CMD_START behind it.
                withTimeoutOrNull(COMMAND_ACK_TIMEOUT_MS) { writeAck?.await() }
                writeAck = null
            }
        }
    }

    private fun writeNow(bytes: ByteArray): Boolean {
        val gatt = gatt ?: return false
        val rx = rxCharacteristic ?: return false

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeCharacteristic(
                rx, bytes, BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            ) == BluetoothStatusCodes.SUCCESS
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
            while (chartPackets.tryReceive().isSuccess) { /* drain */ }
            chartQueued.set(0)

            while (isActive) {
                val packet = chartPackets.receive()
                chartQueued.updateAndGet { (it - 1).coerceAtLeast(0) }
                // One malformed packet must not take the app down mid-recording.
                runCatching {
                    when (packet) {
                        is NailPacket.Mic -> onMic(packet)
                        is NailPacket.Imu -> onImu(packet)
                        is NailPacket.Text -> Unit   // routed to [textPackets] instead
                    }
                }.onFailure { Log.e(TAG, "packet handling failed", it) }
            }
        }

        recorderJob?.cancel()
        recorderJob = scope.launch(recorderDispatcher) {
            while (recorderPackets.tryReceive().isSuccess) { /* drain */ }
            while (isActive) {
                val packet = recorderPackets.receive()
                runCatching {
                    when (packet) {
                        is NailPacket.Mic -> recorder.appendMic(packet.index, packet.samples)
                        is NailPacket.Imu ->
                            recorder.appendImu(packet.index, packet.samples, packet.hasGyro)
                        is NailPacket.Text -> Unit
                    }
                }.onFailure { Log.e(TAG, "recording a packet failed", it) }
            }
        }

        textJob?.cancel()
        textJob = scope.launch(streamDispatcher) {
            while (textPackets.tryReceive().isSuccess) { /* drain */ }
            while (isActive) {
                val packet = textPackets.receive()
                runCatching { onText(packet.line) }
                    .onFailure { Log.e(TAG, "status line handling failed", it) }
            }
        }
    }

    private fun onMic(packet: NailPacket.Mic) {
        // Chart buffers are filled by the frame tick, not here: see [frameTick].
        for (raw in packet.samples) {
            // Raw int16 PCM, not normalised to +-1. The chart is read next to the CSV, and the CSV's
            // `pcm` column is this number — dividing by full scale here meant the axis said 0.6 for
            // a sample the file recorded as 19661, and neither figure could be checked against the
            // other. The spectrogram and the power trace are unaffected: [SpectrumAnalyzer] divides
            // the spectrum by its own peak, so both are scale-invariant.
            micTickRaw.add(raw.toFloat())
        }
    }

    private fun onImu(packet: NailPacket.Imu) {
        // Chart buffers are filled by the frame tick, not here: see [frameTick].
        for (s in packet.samples) {
            accelTick.add(s.ax, s.ay, s.az)
            gyroTick.add(s.gx, s.gy, s.gz)
        }
    }

    private fun onText(line: String) {
        Log.i(TAG, "board: $line")

        if (line.startsWith("HEALTH,")) {
            onHealth(line)
            return
        }

        if (line.startsWith("SYNC,")) {
            // Measured on the board with one clock: how much later the IMU's sample 0 was taken
            // than the microphone's. The two streams are started back to back, so what is left is
            // hardware start-up latency — small, but the only way to align the CSVs exactly.
            line.removePrefix("SYNC,").split(",").forEach { field ->
                if (field.startsWith("imu_minus_mic_us=")) {
                    streamOriginOffsetUs = field.removePrefix("imu_minus_mic_us=").toIntOrNull()
                    Log.i(TAG, "stream origin offset: $streamOriginOffsetUs us")
                }
            }
            return
        }

        if (line.startsWith("RATE,")) {
            val fields = parseRateFields(line)
            // imu_ok=0 means the board could not talk to the LSM6DSL at boot, so every IMU
            // sample will be zero. Without this the charts just look flat for no visible reason.
            val imuOk = fields["imu_ok"] != "0"
            if (!imuOk) {
                Log.e(TAG, "IMU init FAILED on the board — IMU data will be all zeros. Power-cycle the Nail (a soft reset leaves the sensor rail up).")
            }
            if (imuOk != _status.value.imuOk) _status.update { it.copy(imuOk = imuOk) }
            applyReportedIdentity(fields, imuOk)

            line.removePrefix("RATE,").split(",").forEach { field ->
                if (field.startsWith("mic_hz=")) {
                    field.removePrefix("mic_hz=").toIntOrNull()?.let { hz ->
                        micSampleRate = hz
                        val rate = SamplingRate.entries.minByOrNull { abs(it.kHz * 1000 - hz) }
                        if (rate != null) {
                            _status.update { it.copy(samplingRate = rate) }
                        }
                        micBuffer.clear()
                        analysisWindow.clear()
                        expectedMicIndex = -1
                        Log.i(TAG, "mic rate $hz Hz")
                    }
                }
                if (field.startsWith("imu_hz=")) {
                    field.removePrefix("imu_hz=").toIntOrNull()?.let { hz ->
                        val rate = ImuSamplingRate.entries.minByOrNull { abs(it.hz - hz) }
                        if (rate != null) {
                            _status.update { it.copy(imuSamplingRate = rate) }
                        }
                        accelBuffers.clear()
                        gyroBuffers.clear()
                        // The board restarts IMU absolute numbering at 0 on a rate change, so a
                        // stale expectation here would misread the restart as a gap.
                        expectedImuIndex = -1
                        Log.i(TAG, "imu rate $hz Hz")
                    }
                }
                if (field.startsWith("profile=")) {
                    field.removePrefix("profile=").toIntOrNull()?.let { onProfile(it) }
                }
            }
        }
    }

    /**
     * Trusts explicit identity reported by current firmware, while leaving old Nail firmware on
     * the mode selected by the user. A wrong manual choice is corrected before streaming settles.
     */
    private fun applyReportedIdentity(fields: Map<String, String>, imuOk: Boolean) {
        val reportedMode = inferDeviceMode(fields)
        val current = _status.value
        val boardName = fields["board"].orEmpty()
        val sensorName = fields["sensor"].orEmpty()
        val reportedHasMic = fields["has_mic"]?.toIntOrNull()?.let { it != 0 }

        if (reportedMode != null && reportedMode != current.deviceMode) {
            Log.w(
                TAG,
                "selected ${current.deviceMode.label}, but board reports ${reportedMode.label}; " +
                    "switching to hardware defaults"
            )
            preferences.applyDeviceMode(reportedMode)
            _status.value = DeviceStatus.forMode(reportedMode).copy(
                deviceName = current.deviceName,
                boardName = boardName,
                sensorName = sensorName,
                modeVerified = true,
                hasMicrophone = reportedHasMic ?: reportedMode.hasMicrophone,
                imuOk = imuOk
            )
            _streamMarks.value = StreamMarks(
                imuActive = true,
                micActive = _status.value.hasMicrophone
            )

            profileWritesInFlight.set(0)
            profileResent = false
            writeCommand(NailProtocol.cmdSetImuRate(reportedMode.defaultImuRate.hz))
            writeProfile(reportedMode.defaultStreamProfile)
            return
        }

        _status.update {
            it.copy(
                boardName = boardName.ifEmpty { it.boardName },
                sensorName = sensorName.ifEmpty { it.sensorName },
                modeVerified = reportedMode != null || it.modeVerified,
                hasMicrophone = reportedHasMic ?: it.hasMicrophone,
                imuOk = imuOk
            )
        }
        if (!_status.value.hasMicrophone) {
            _streamMarks.update { it.copy(micActive = false) }
        }
    }

    /**
     * Reconciles the profile the board says it is using with the one we asked for.
     *
     * This has to be checked, because the board rejects an unknown profile code silently: it keeps
     * whatever it had — Raw, after a boot — and only mentions it in this field. Believing our own
     * value instead is how the app ends up expecting a compressed stream while the board sends raw
     * at 929 kbps, and since the firmware drains the microphone ring before touching the IMU, the
     * entire shortfall comes out of the IMU. The charts then show a working microphone next to an
     * IMU that produces one burst and stops, with no error logged anywhere — it reads exactly like
     * a dead sensor. That cost a day once already.
     *
     * A mismatch is normal in flight, though: the board emits a RATE line for the microphone-rate
     * command too, before it has applied the profile that is still queued behind it. So a write we
     * know is outstanding absorbs one mismatch. Only an unexplained one is acted on, and the first
     * response is to send the command again rather than to give up — a resend also makes the board
     * emit a fresh RATE line, which is what confirms the retry. Adopting the board's value happens
     * only after that retry has also failed, so the app never silently downgrades itself, and never
     * loops either.
     */
    private fun onProfile(code: Int) {
        val onBoard = StreamProfile.entries.firstOrNull { it.code == code }
        if (onBoard == null) {
            Log.e(TAG, "board reports unknown profile code $code — firmware newer than the app?")
            return
        }

        val wanted = _status.value.streamProfile
        if (onBoard == wanted) {
            profileWritesInFlight.set(0)
            profileResent = false
            return
        }

        if (profileWritesInFlight.get() > 0) {
            profileWritesInFlight.decrementAndGet()
            return   // our own write is still on its way; this line predates it
        }

        if (!profileResent) {
            profileResent = true
            Log.w(
                TAG,
                "profile mismatch: app wants ${wanted.code} (${wanted.label}) but board reports" +
                    " $code — 0xA2 was rejected or lost. Resending."
            )
            writeProfile(wanted)
            return
        }

        Log.e(
            TAG,
            "profile still $code after a resend; the board does not accept ${wanted.code}." +
                " Following the board (${onBoard.label}). Expect heavy IMU loss if this is Raw" +
                " at high rates — reflash the firmware or lower the rates."
        )
        _status.update { it.copy(streamProfile = onBoard) }
        expectedMicIndex = -1
        expectedImuIndex = -1
    }

    /**
     * Reads the board's once-a-second HEALTH line for the two numbers that say where the lag is.
     *
     * `ilag`/`mlag` are how many milliseconds of data are sitting in the board's rings waiting to
     * go out, which is the dominant term in what the user sees: a tap is already that old before it
     * reaches the phone at all. `lat` is the budget currently in force, so it is also the ceiling
     * those two can reach.
     *
     * That relationship is the whole diagnosis, and it reads directly off the log:
     *
     *   - lag well under lat        the link is keeping up. Remaining delay is the connection
     *                               interval and the chart frame, roughly 40 ms, and there is
     *                               nothing left to win here.
     *   - lag pinned at lat         demand exceeds what the link carries, so the rings stay full
     *                               and the budget alone decides the delay. Lower the IMU rate or
     *                               pick a compressed profile — lowering the budget further only
     *                               trades lag for loss.
     *   - lat is not what we sent   the 0xA3 write was lost or rejected. Same failure mode as a
     *                               lost profile write, and the same reason to surface it.
     *
     * Logged rather than shown in the UI: this is a bring-up instrument, and the numbers only mean
     * something next to each other over time.
     */
    private fun onHealth(line: String) {
        val fields = line.removePrefix("HEALTH,").split(",")
            .mapNotNull { field ->
                val i = field.indexOf('=')
                if (i <= 0) null else field.substring(0, i) to field.substring(i + 1)
            }.toMap()

        val budget = fields["lat"]?.toIntOrNull() ?: return
        val imuLag = fields["ilag"]?.toIntOrNull() ?: return
        val micLag = fields["mlag"]?.toIntOrNull() ?: return

        val expected =
            if (_status.value.recording == RecordingState.Recording) NailProtocol.LATENCY_RECORD_MS
            else NailProtocol.LATENCY_MONITOR_MS
        if (budget != expected) {
            Log.w(
                TAG,
                "latency budget mismatch: app wants $expected ms but board reports $budget ms" +
                    " — 0xA3 was lost or rejected. Resending."
            )
            writeCommand(NailProtocol.cmdSetLatency(expected))
        }

        // The gap between the two is what shows up on screen as the streams sliding against each
        // other, so it is worth naming rather than leaving to be subtracted by eye.
        val skew = imuLag - micLag

        // The board's figures only cover delay before the packets crossed the air. Everything
        // after that is the phone's, and the first version of this log had no view of it at all —
        // which is how a queue growing inside the app went unnoticed while the board reported
        // itself healthy. `q` is the chart queue's depth; each packet in it is roughly 7 ms of
        // extra delay, so `qms` is what it is costing the display.
        val queued = chartQueued.get()
        val queueMs = queued * APPROX_PACKET_MILLIS
        val dropped = chartDropped.get()

        val boardBound = imuLag >= budget * 9 / 10 || micLag >= budget * 9 / 10
        val phoneBound = queued >= CHART_QUEUE_DEPTH / 2
        val verdict = when {
            boardBound -> "BOARD - link cannot carry this rate/profile"
            phoneBound -> "PHONE - the app cannot consume as fast as packets arrive"
            else -> "ok"
        }
        Log.i(
            TAG,
            "lag: imu=${imuLag}ms mic=${micLag}ms skew=${skew}ms budget=${budget}ms " +
                "q=$queued (~${queueMs}ms, $dropped skipped) [$verdict]"
        )
    }

    /** Every 0xA2 goes through here so [onProfile] can tell its own writes from a stale report. */
    private fun writeProfile(profile: StreamProfile) {
        profileWritesInFlight.incrementAndGet()
        writeCommand(NailProtocol.cmdSetProfile(profile.code))
    }

    // =================================================================================
    //  Chart frames
    // =================================================================================

    /**
     * Drives the chart frames on a fixed *period* rather than a fixed *gap*.
     *
     * This used to be `frameTick(); delay(TICK_MILLIS)`, which sleeps 20 ms **after** however long
     * the frame took. Every waveform buffer advances by a fixed number of points per frame and its
     * position on screen is purely a count of what was written to it, so the sweep rate is the
     * frame rate. Make the frame cost 15 ms of work and the sweep silently slows to 28 Hz while
     * the clock keeps running at 50 — the charts then fall behind real time by 40% of every second
     * and never recover, on top of any delay in the data itself.
     *
     * That is invisible while monitoring and severe while recording, because recording is what put
     * work on this thread in the first place (see [StreamRecorder]). Both fixes are needed: moving
     * the file writing off the thread stops the frames from being late, and this stops a late frame
     * from turning into permanent drift against the clock.
     *
     * When frames *are* missed, the tick is not simply skipped — [frameTick] is told how many are
     * due and emits that many frames' worth of points from what accumulated, so the sweep stays
     * locked to the clock. Both streams get the same multiplier, which is what keeps them aligned
     * with each other through a stall.
     */
    private fun startEmitter() {
        emitJob?.cancel()
        emitJob = scope.launch(streamDispatcher) {
            var nextFrameAt = SystemClock.elapsedRealtime()
            while (isActive) {
                val late = SystemClock.elapsedRealtime() - nextFrameAt
                val due = (1L + late / TICK_MILLIS).coerceIn(1L, MAX_CATCHUP_FRAMES).toInt()

                runCatching {
                    frameTick(due)
                    emitSnapshot()
                    trackRecordingTime()
                }.onFailure { Log.e(TAG, "chart frame failed", it) }

                nextFrameAt += due * TICK_MILLIS
                val sleep = nextFrameAt - SystemClock.elapsedRealtime()
                if (sleep > 0) {
                    delay(sleep)
                } else {
                    // Further behind than [MAX_CATCHUP_FRAMES] can absorb. Catching all of it up
                    // would dump a large part of the window in one frame, which reads as a glitch
                    // rather than as history. Re-anchor and carry on from now.
                    if (late > MAX_CATCHUP_FRAMES * TICK_MILLIS) {
                        Log.w(TAG, "chart frames ${late}ms behind; re-anchoring to the clock")
                    }
                    nextFrameAt = SystemClock.elapsedRealtime()
                }
            }
        }
    }

    /**
     * One chart frame: drain what arrived since the last one, then advance every waveform buffer by
     * exactly one frame's worth of points.
     *
     * The buffers have to be filled here rather than as packets land. They sweep left to right and
     * restart at the end, so a buffer's position on screen is purely a count of what was written to
     * it — fill it at the sensor's own rate and it sweeps at that rate. With the old integer
     * decimation the microphone swept its 10 s window in 9.75 s at 16 kHz (and 6 s at 1 kHz) while
     * the IMU took 9.6 s, so the traces slid apart from each other and from the clock.
     */
    private fun frameTick(frames: Int = 1) {
        val mic = micTickRaw.snapshot()
        val accel = accelTick.snapshot()
        val gyro = gyroTick.snapshot()

        micTickRaw.clear()
        accelTick.clear()
        gyroTick.clear()

        applyStreamOffset()


        // [frames] is how many 20 ms slots this call is standing in for. Every buffer is advanced
        // by that many frames' worth of points, from the samples that accumulated over the same
        // span, so a late frame costs resolution rather than time. The same multiplier goes to both
        // sensors, which is what stops a stall from sliding them against each other.
        val micPoints = ChartWindow.MIC_POINTS_PER_TICK * frames
        val imuPoints = ChartWindow.IMU_POINTS_PER_TICK * frames

        micBuffer.addAll(micDelay.process(micResampler.resample(mic, micPoints)))
        accelBuffers.addAll(accelDelay.process(accelResampler.resample(accel, imuPoints)))
        gyroBuffers.addAll(gyroDelay.process(gyroResampler.resample(gyro, imuPoints)))

        // Fed the undelayed frame on purpose: the analyser measures one sensor at a time, so an
        // alignment shift would only move its spectrogram against the clock for no benefit.
        analyse(mic, accel, gyro)
        analysisFrame++
    }

    /**
     * Converts the board's SYNC offset into whole chart points and hands it to the delay lines.
     *
     * A positive `imu_minus_mic_us` means the IMU's sample 0 was captured that much later than the
     * microphone's, so at any frame the IMU trace is showing a slightly more recent moment — the
     * IMU is what gets held back. A negative value is the mirror case.
     *
     * The two waveforms are drawn at different rates, so the same offset is a different number of
     * points on each: at 600 Hz the microphone resolves 1.7 ms per point, while the IMU's 100 Hz
     * only resolves 10 ms. An offset smaller than one point of a given chart rounds to zero there,
     * which is correct — it is below what that chart can show.
     */
    private fun applyStreamOffset() {
        val offsetUs = streamOriginOffsetUs
        if (offsetUs == appliedOffsetUs) return
        appliedOffsetUs = offsetUs

        val seconds = (offsetUs ?: 0) / 1_000_000.0
        if (seconds >= 0) {
            micDelay.setDelay(0)
            val points = Math.round(seconds * ChartWindow.IMU_DISPLAY_HZ).toInt()
            accelDelay.setDelay(points)
            gyroDelay.setDelay(points)
        } else {
            micDelay.setDelay(Math.round(-seconds * ChartWindow.MIC_DISPLAY_HZ).toInt())
            accelDelay.setDelay(0)
            gyroDelay.setDelay(0)
        }
        Log.i(TAG, "live chart alignment: offset=${offsetUs}us")
    }

    /**
     * Feeds the selected sensor into the DFT. The analysis window gets *full-rate* mic samples, not
     * the resampled ones — at 600 Hz the spectrogram could only show 0-300 Hz, which throws away
     * almost everything the microphone hears.
     */
    private fun analyse(mic: FloatArray, accel: XyzWindow, gyro: XyzWindow) {
        val selected = preferences.sensor.value
        if (selected != analysedSensor) {
            analysedSensor = selected
            analysisWindow.clear()
            powerBuffer.clear()
            spectrogram.clear()
            lastPower = 0f
        }

        val source = when (selected) {
            MonitoringSensor.Microphone -> mic
            MonitoringSensor.AccelX -> accel.x
            MonitoringSensor.AccelY -> accel.y
            MonitoringSensor.AccelZ -> accel.z
            MonitoringSensor.GyroX -> gyro.x
            MonitoringSensor.GyroY -> gyro.y
            MonitoringSensor.GyroZ -> gyro.z
        }

        // The power trace advances once per analysis pass, so an empty frame has to hold its last
        // value on exactly those frames — adding on every frame would sweep it faster than its
        // capacity assumes and mislabel its time axis.
        if (source.isEmpty()) {
            if (isAnalysisFrame()) powerBuffer.add(lastPower)
            return
        }

        // The window is fed every frame — appending to a ring is nearly free, and skipping frames
        // here would throw away half the samples the FFT is supposed to see. Only the transform
        // itself, which is the expensive part, runs at the reduced rate.
        analysisWindow.addAll(source)
        if (!isAnalysisFrame()) return

        val spectrum = analyzer.magnitudes(analysisWindow.snapshot())
        lastPower = analyzer.lowBandPower(spectrum)
        powerBuffer.add(lastPower)

        if (spectrogram.size == ChartWindow.SPECTROGRAM_COLUMNS) spectrogram.removeFirst()
        spectrogram.addLast(spectrum)
    }

    /** True on the frames the spectrum analyser runs; see [ChartWindow.ANALYSIS_EVERY]. */
    private fun isAnalysisFrame() = analysisFrame % ChartWindow.ANALYSIS_EVERY == 0

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
        val seconds = ((System.currentTimeMillis() - recordingStartedAt).coerceAtLeast(1)) / 1000.0
        val micExpected = micReceived + micLost
        val imuExpected = imuReceived + imuLost
        val micLossPercent = if (micExpected > 0) 100.0 * micLost / micExpected else 0.0
        val imuLossPercent = if (imuExpected > 0) 100.0 * imuLost / imuExpected else 0.0
        // Throughput counts both streams — the microphone alone understates a link that is
        // saturated precisely because the IMU rate was raised.
        //
        // Packets per second matters as much as kbps here: measurements at 7.5 ms and 15 ms
        // intervals both landed near 300 packets/s, which points at a per-event packet limit in
        // the phone rather than a byte-rate one. If that holds, a profile only helps when it
        // also reduces packet count, not merely bytes.
        val kbps = wireBytes.get() * 8 / 1000.0 / seconds
        val uncompressed = (micReceived * 2 + imuReceived * 12) * 8 / 1000.0 / seconds
        Log.i(
            TAG,
            ("link: mic %d rx / %d lost (%.2f%%), imu %d rx / %d lost (%.2f%%) @ %.0f Hz, " +
                "~%.0f kbps wire (%.0f pkt/s, %.0f kbps uncompressed), profile %s")
                .format(
                    micReceived, micLost, micLossPercent,
                    imuReceived, imuLost, imuLossPercent, imuExpected / seconds,
                    kbps, wirePackets.get() / seconds, uncompressed,
                    _status.value.streamProfile.label
                )
        )
    }

    // =================================================================================
    //  Controls
    // =================================================================================

    override fun selectDeviceMode(mode: DeviceMode) {
        if (_connectionState.value == ConnectionState.Connecting ||
            _connectionState.value == ConnectionState.Connected
        ) return
        preferences.applyDeviceMode(mode)
        _status.value = DeviceStatus.forMode(mode)
    }

    override fun setSamplingRate(rate: SamplingRate) {
        if (!_status.value.hasMicrophone) return
        _status.update { it.copy(samplingRate = rate) }
        if (_connectionState.value == ConnectionState.Connected) {
            writeCommand(NailProtocol.cmdSetRate(rate.kHz))
        }
        // When disconnected there is nothing to do: connect() sends whatever is in _status.
    }

    override fun setImuSamplingRate(rate: ImuSamplingRate) {
        if (rate !in _status.value.deviceMode.imuRates) {
            Log.w(TAG, "ignoring ${rate.hz} Hz for ${_status.value.deviceMode.label}")
            return
        }
        _status.update { it.copy(imuSamplingRate = rate) }
        if (_connectionState.value == ConnectionState.Connected) {
            writeCommand(NailProtocol.cmdSetImuRate(rate.hz))
        }
        // When disconnected there is nothing to do: connect() sends whatever is in _status.
    }

    /**
     * Switching profile changes the wire format, so the board restarts both streams' absolute
     * sample numbering. Resetting the expected indices here keeps that from being counted as a
     * huge loss burst; without it the next packet looks like millions of missing samples.
     */
    override fun setStreamProfile(profile: StreamProfile) {
        if (profile !in _status.value.deviceMode.streamProfiles) {
            Log.w(TAG, "ignoring profile ${profile.code} for ${_status.value.deviceMode.label}")
            return
        }
        _status.update { it.copy(streamProfile = profile) }
        if (_connectionState.value == ConnectionState.Connected) {
            // A fresh user choice gets its own retry budget: the last mismatch was about the old
            // value and says nothing about whether the board will take this one.
            profileResent = false
            writeProfile(profile)
            scope.launch(streamDispatcher) {
                expectedMicIndex = -1
                expectedImuIndex = -1
            }
        }
        // When disconnected there is nothing to do: connect() sends whatever is in _status.
    }

    /** Not supported by this firmware. */
    override fun setGain(percent: Float) = Unit

    /** Label for the next recording only; consumed (and cleared) by [setRecording]. */
    @Volatile
    private var nextRecordingLabel: String? = null

    override fun startLabeledRecording(label: String) {
        nextRecordingLabel = label
        setRecording(RecordingState.Recording)
    }

    override fun setRecording(state: RecordingState) {
        when (state) {
            RecordingState.Recording -> {
                recordingStartedAt = System.currentTimeMillis()
                val label = nextRecordingLabel
                nextRecordingLabel = null
                writeCommand(NailProtocol.cmdSetLatency(NailProtocol.LATENCY_RECORD_MS))
                // Opened on the recorder's own thread, the one that will be writing to it, so
                // start() cannot race with an append already in flight.
                scope.launch(recorderDispatcher) { recorder.start(recordingSettings(), label) }
                micReceived = 0
                micLost = 0
                imuReceived = 0
                imuLost = 0
                // Throughput is reported per recording, so the wire counters restart with it.
                wireBytes.set(0)
                wirePackets.set(0)
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
                // Back to monitoring: nothing is being written to a file any more, so holding a
                // second of backlog would only push the charts behind for no gain.
                writeCommand(NailProtocol.cmdSetLatency(NailProtocol.LATENCY_MONITOR_MS))
                _status.update { it.copy(recording = state, memory = MemoryStatus.Saved) }
                // File name is reported after both CSV streams are flushed and published. Queued
                // behind every append already on this thread, so the tail of the recording is in
                // the file before it closes.
                scope.launch(recorderDispatcher) {
                    val result = recorder.stop()
                    logLinkQuality()
                    _status.update { it.copy(savedFileName = result?.fileName.orEmpty()) }
                }
            }

            RecordingState.Off -> {
                // Off can be reached without passing through Stopped, so the budget has to come
                // back down here too or a cancelled recording leaves the charts lagging.
                writeCommand(NailProtocol.cmdSetLatency(NailProtocol.LATENCY_MONITOR_MS))
                scope.launch(recorderDispatcher) { finishRecordingIfAny() }
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

    /**
     * Settings captured for the session note. The microphone rate comes from the board's own RATE
     * line rather than from the picker, so the file records what the hardware actually ran at even
     * if the two ever disagree.
     */
    private fun recordingSettings(): StreamRecorder.Settings {
        val status = _status.value
        return StreamRecorder.Settings(
            deviceName = status.deviceName,
            micRateHz = micSampleRate,
            imuRateHz = status.imuSamplingRate.hz,
            compression = status.streamProfile.label,
            compressionCode = status.streamProfile.code,
            lossless = status.streamProfile.lossless,
            originOffsetUs = streamOriginOffsetUs
        )
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
        lastPower = 0f

        // The delay lines hold real samples, so leaving them filled would leak the previous
        // session's tail into the start of the next one. Forcing appliedOffsetUs to a value the
        // board can never report makes the next frame re-arm them from the current SYNC.
        micDelay.setDelay(0)
        accelDelay.setDelay(0)
        gyroDelay.setDelay(0)
        appliedOffsetUs = Int.MIN_VALUE
        analysisFrame = 0
    }

    private companion object {
        const val TAG = "NailBleClient"

        const val REQUESTED_MTU = 247
        const val SCAN_TIMEOUT_MILLIS = 15_000L
        const val TICK_MILLIS = ChartWindow.TICK_MILLIS
        const val LOSS_LOG_PERIOD_SECONDS = 5

        /** Long enough for a real acknowledgement, short enough not to stall the queue. */
        const val COMMAND_ACK_TIMEOUT_MS = 250L

        const val SPECTROGRAM_BINS = 64
        const val ANALYSIS_WINDOW = 128

        /**
         * Most frames one late frame may stand in for. 25 frames is half a second; catching up
         * more than that in a single tick would write a large part of the visible window at once,
         * which reads as a glitch rather than as history. Past this the emitter re-anchors to the
         * clock and accepts that the missed span is gone.
         */
        const val MAX_CATCHUP_FRAMES = 25L

        /**
         * Packets the chart queue may hold before it starts dropping the oldest.
         *
         * This is a latency budget in disguise, the phone-side twin of the board's: at roughly 220
         * packets a second, 32 is about 150 ms — deliberately the same figure the board uses while
         * monitoring, because a delay is a delay wherever it accumulates. Large enough to ride out
         * a scheduling hiccup, small enough that it can never become the reason the charts are
         * behind.
         */
        const val CHART_QUEUE_DEPTH = 32

        /** Rough wall time one packet represents, for turning a queue depth into milliseconds. */
        const val APPROX_PACKET_MILLIS = 7
    }
}

/**
 * Holds a resampled waveform back by a whole number of chart points.
 *
 * The board reports `SYNC,imu_minus_mic_us` — the gap between the two streams' sample 0, measured
 * against one clock on the board. Until now that value only reached [StreamRecorder], so recorded
 * CSVs were aligned but the live charts were not: whichever stream started first was drawn that
 * much ahead of the other for the whole session.
 *
 * Delaying is the only correct direction. Pulling a stream forward would mean drawing samples that
 * have not arrived yet, so the stream that starts earlier is the one held back.
 */
internal class DelayLine {
    private val queue = ArrayDeque<Float>()
    private var points = 0

    /** Prefilled with zeros so the delay is in effect from the first frame, not faded in. */
    fun setDelay(newPoints: Int) {
        if (newPoints == points) return
        points = newPoints.coerceIn(0, MAX_POINTS)
        queue.clear()
        repeat(points) { queue.addLast(0f) }
    }

    fun process(input: FloatArray): FloatArray {
        if (points == 0) return input
        val out = FloatArray(input.size)
        for (i in input.indices) {
            queue.addLast(input[i])
            out[i] = queue.removeFirst()
        }
        return out
    }

    private companion object {
        /** A garbled SYNC line must not be able to allocate an unbounded queue. */
        const val MAX_POINTS = ChartWindow.MIC_DISPLAY_HZ * 2
    }
}

/** Three [DelayLine]s kept in lockstep for a tri-axial sensor. */
private class XyzDelayLine {
    private val x = DelayLine()
    private val y = DelayLine()
    private val z = DelayLine()

    fun setDelay(points: Int) {
        x.setDelay(points); y.setDelay(points); z.setDelay(points)
    }

    fun process(window: XyzWindow) =
        XyzWindow(x.process(window.x), y.process(window.y), z.process(window.z))
}

/** Three [RollingBuffer]s kept in lockstep for a tri-axial sensor. */
private class XyzBuffers(capacity: Int) {
    private val x = RollingBuffer(capacity)
    private val y = RollingBuffer(capacity)
    private val z = RollingBuffer(capacity)

    fun add(xv: Float, yv: Float, zv: Float) {
        x.add(xv); y.add(yv); z.add(zv)
    }

    fun addAll(window: XyzWindow) {
        x.addAll(window.x); y.addAll(window.y); z.addAll(window.z)
    }

    fun snapshot() = XyzWindow(x.snapshot(), y.snapshot(), z.snapshot())

    fun clear() {
        x.clear(); y.clear(); z.clear()
    }
}

/**
 * Rewrites one frame's arrivals as exactly [pointsPerTick] chart points, whatever rate the sensor
 * runs at and however unevenly the packets landed.
 *
 * Peak-preserving rather than plain sub-sampling: at 16 kHz a frame carries 800 mic samples for 30
 * points, and a tap whose peak fell in the discarded samples would be drawn as if it never happened.
 * An empty frame repeats the last point, so a stalled link parks the trace instead of letting it
 * fall behind the clock.
 */
internal class TickResampler(private val pointsPerTick: Int) {

    private var hold = 0f

    /**
     * [points] defaults to one frame's worth. The emitter passes a multiple when it is standing in
     * for frames it could not run on time, so that a stall costs resolution instead of sliding the
     * trace against the clock — see [NailBleClient.startEmitter].
     */
    fun resample(source: FloatArray, points: Int = pointsPerTick): FloatArray {
        val out = FloatArray(points)
        if (source.isEmpty()) {
            out.fill(hold)
            return out
        }
        for (i in out.indices) {
            val from = i * source.size / points
            val to = ((i + 1) * source.size / points)
                .coerceAtLeast(from + 1)
                .coerceAtMost(source.size)
            var peak = source[from]
            for (j in from until to) {
                if (abs(source[j]) > abs(peak)) peak = source[j]
            }
            out[i] = peak
        }
        hold = out[out.size - 1]
        return out
    }
}

/** Three [TickResampler]s kept in lockstep for a tri-axial sensor. */
private class XyzTickResampler(private val pointsPerTick: Int) {
    private val x = TickResampler(pointsPerTick)
    private val y = TickResampler(pointsPerTick)
    private val z = TickResampler(pointsPerTick)

    fun resample(window: XyzWindow, points: Int = pointsPerTick) =
        XyzWindow(
            x.resample(window.x, points),
            y.resample(window.y, points),
            z.resample(window.z, points)
        )
}

/** Per-frame scratch for the spectral analyser. */
private class XyzAccumulator {
    private val xs = FloatAccumulator()
    private val ys = FloatAccumulator()
    private val zs = FloatAccumulator()

    fun add(x: Float, y: Float, z: Float) {
        xs.add(x); ys.add(y); zs.add(z)
    }

    fun snapshot() = XyzWindow(xs.snapshot(), ys.snapshot(), zs.snapshot())

    fun clear() {
        xs.clear(); ys.clear(); zs.clear()
    }
}

/**
 * Growable primitive float buffer.
 *
 * These held `ArrayList<Float>`, which boxes. `Float.valueOf` has no small-value cache the way
 * `Integer.valueOf` does, so every sample was a heap allocation: about 26,000 a second at mic
 * 16 kHz plus a six-axis IMU at 1666 Hz, all of it on the thread that also has to keep draining
 * BLE notifications. The garbage is short-lived and cheap to collect individually, but the pauses
 * land exactly where they hurt — on the packet consumer.
 */
internal class FloatAccumulator(initial: Int = 64) {
    private var data = FloatArray(initial)
    private var count = 0

    fun add(value: Float) {
        if (count == data.size) data = data.copyOf(data.size * 2)
        data[count++] = value
    }

    /** Fresh array each frame: the charts compare snapshots by identity to detect a new frame. */
    fun snapshot(): FloatArray = data.copyOf(count)

    fun clear() {
        count = 0
    }
}

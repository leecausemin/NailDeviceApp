package com.example.naildeviceapp.data.ble

import com.example.naildeviceapp.model.DeviceMode
import java.util.UUID

/**
 * Wire format shared with the nail firmware (`ble_app_uart/main.c`).
 *
 * One BLE notification carries exactly one packet — ATT preserves message boundaries, so there is
 * no stream to reassemble.
 *
 * ```
 * [0]     magic: 0xA5 mic, 0x5A imu, 0x01 text
 * [1..4]  absolute index of this packet's first sample (uint32 little-endian)
 * [5]     sample count (uint8)
 * [6..]   mic: int16 LE PCM      imu: int16 LE x 6 axes (12 bytes per sample)
 * ```
 *
 * The absolute index is what makes loss detectable: if it jumps ahead of what we expected, exactly
 * that many samples went missing. Without it a dropped packet would silently shorten the recording.
 */
object NailProtocol {

    val NUS_SERVICE: UUID = UUID.fromString("6e400001-b5a3-f393-e0a9-e50e24dcca9e")

    /** Phone writes commands here. */
    val NUS_RX: UUID = UUID.fromString("6e400002-b5a3-f393-e0a9-e50e24dcca9e")

    /** Board notifies data here. */
    val NUS_TX: UUID = UUID.fromString("6e400003-b5a3-f393-e0a9-e50e24dcca9e")

    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    const val MAGIC_MIC = 0xA5
    const val MAGIC_IMU = 0x5A
    const val MAGIC_TEXT = 0x01

    /* Compressed variants, sent only when a profile above 0 is selected.
     *
     * Why they exist: mic 16 kHz (269 kbps) plus IMU 6664 Hz (660 kbps) needs 929 kbps, and this
     * link tops out near 600 kbps on a Galaxy A15 — interval, queue depth and PHY were already
     * exhausted, so the only lever left is sending fewer bytes.
     *
     * These use an 8-byte header because ADPCM fits more than 255 samples in one packet:
     * ```
     * [0]     magic
     * [1..4]  absolute index (uint32 LE)
     * [5..6]  sample count (uint16 LE)
     * [7]     imu: pack format (0 = 6 axes, 1 = accel only)   mic: ADPCM step index
     * [8..]   imu: 12-bit values packed back to back
     *         mic: [8..9] predictor (int16 LE), then 4-bit codes, low nibble first
     * ```
     */
    const val MAGIC_MIC_ADPCM = 0xA6

    /**
     * G.711 u-law, one byte per sample. Coarser than raw but ten times finer than the 4-bit
     * ADPCM on loud signals (1.4% vs 15% error on a real tap recording), for twice its bandwidth
     * — cheap, because the microphone is only about a tenth of the traffic.
     *
     * Unlike ADPCM it carries no state, so a dropped packet costs exactly its own samples.
     */
    const val MAGIC_MIC_ULAW = 0xA7
    const val MAGIC_IMU_PACK = 0x5B

    const val HEADER_BYTES = 6
    const val HEADER2_BYTES = 8
    const val ADPCM_HDR_BYTES = 10
    const val IMU_SAMPLE_BYTES = 12

    const val IMU_PACK_FMT_6AXIS = 0
    const val IMU_PACK_FMT_ACCEL = 1

    /* Streaming profiles, selected with 0xA2. Bandwidth at mic 16 kHz + IMU 6664 Hz:
     *   0 RAW         929 kbps  lossless, what the app used before
     *   1 MIC_ADPCM   732 kbps
     *   2 IMU_PACK    769 kbps
     *   3 BOTH        572 kbps  the one that is supposed to fit
     *   4 BOTH_ACCEL  372 kbps  drops the gyroscope entirely, for headroom
     */
    const val PROFILE_RAW = 0
    const val PROFILE_MIC_ADPCM = 1
    const val PROFILE_IMU_PACK = 2
    const val PROFILE_BOTH = 3
    const val PROFILE_BOTH_ACCEL = 4
    const val PROFILE_BOTH_MIC8 = 5

    val CMD_START = byteArrayOf('s'.code.toByte())
    val CMD_STOP = byteArrayOf('e'.code.toByte())

    /** `0xA0 <kHz>` — kHz is one of 16, 8, 4, 2, 1. */
    fun cmdSetRate(kHz: Int) = byteArrayOf(0xA0.toByte(), kHz.toByte())

    /** `0xA1 <Hz uint16 little-endian>` changes both accelerometer and gyroscope ODR. */
    fun cmdSetImuRate(hz: Int) = byteArrayOf(
        0xA1.toByte(),
        (hz and 0xFF).toByte(),
        ((hz shr 8) and 0xFF).toByte()
    )

    /** `0xA2 <profile>` — see the PROFILE_* constants. Resets both streams' absolute indices. */
    fun cmdSetProfile(profile: Int) = byteArrayOf(0xA2.toByte(), profile.toByte())

    /**
     * `0xA3 <ms uint16 little-endian>` sets how long the board may hold data in its rings before
     * throwing the oldest away.
     *
     * The value means two things at once, and which one matters flips with what the user is doing:
     * it is both the longest link stall the board can absorb losslessly and the largest lag that
     * can appear on screen. So the app, which is the only side that knows whether a recording is
     * running, tells the board which of the two to optimise for. See [LATENCY_MONITOR_MS] and
     * [LATENCY_RECORD_MS].
     *
     * Unlike [cmdSetRate], [cmdSetImuRate] and [cmdSetProfile], this does not reset the streams'
     * absolute indices, so it is safe to send in the middle of a recording — which is exactly what
     * [NailBleClient.setRecording] does.
     */
    fun cmdSetLatency(ms: Int) = byteArrayOf(
        0xA3.toByte(),
        (ms and 0xFF).toByte(),
        ((ms shr 8) and 0xFF).toByte()
    )

    /**
     * Live monitoring: the charts are the product, so old samples are worth less than fresh ones.
     * Anything the board is still holding after this long would be drawn in the past anyway.
     */
    const val LATENCY_MONITOR_MS = 150

    /**
     * Recording. Still larger than [LATENCY_MONITOR_MS], because a hole in a CSV cannot be
     * recovered and Android stretches the connection interval to hundreds of milliseconds when it
     * feels like it — with link headroom those stalls are caught up losslessly, but only if the
     * board was allowed to hold the backlog that long.
     *
     * Lowered 1000 -> 300 on 2026-07-28. 1000 ms was sized for a recording running with the screen
     * off, where nothing is watching and only the file matters. That is not how this is used: the
     * charts are being read *while* recording, and a second of permitted backlog is a second the
     * display can be behind. 300 ms still absorbs an ordinary scheduling hiccup and bounds the
     * worst case at something that reads as immediate.
     *
     * This is the knob to turn if the trade is wrong. `itrim`/`mtrim` in the board's HEALTH line
     * are non-zero exactly when this budget is costing samples; if they stay at zero, it could go
     * lower still, and if a session shows real loss, put it back up.
     */
    const val LATENCY_RECORD_MS = 300

    /** PDM hardware rate; every offered rate is this divided by a power of two. */
    const val MIC_BASE_RATE_HZ = 16000

    /** Advertised names of the two firmware variants. */
    const val NAIL_DEVICE_NAME = "NailDevice"
    const val ERM_DEVICE_NAME = "ERM"

    /* ---- Raw LSB -> physical units ----
     * Must match the ranges lsm6_init() writes to CTRL1_XL/CTRL2_G. Ranges were widened
     * from ±2g/±245dps because tap impacts clipped (12.4% of accel Y sat at the rail),
     * and clipped samples cannot be recovered. Change one side without the other and
     * every reading is off by a constant factor — silently, since it still looks plausible. */
    const val ACCEL_G_PER_LSB = 0.000488f   // ±16 g
    const val GYRO_DPS_PER_LSB = 0.07f      // ±2000 dps
    const val MIC_FULL_SCALE = 32768f
}

/** One IMU sample in physical units. */
data class ImuSample(
    val ax: Float,
    val ay: Float,
    val az: Float,
    val gx: Float,
    val gy: Float,
    val gz: Float
)

/** A decoded notification. */
sealed interface NailPacket {

    /** [index] is the absolute sample number of `samples[0]`. */
    data class Mic(val index: Long, val samples: ShortArray) : NailPacket {
        override fun equals(other: Any?) = this === other
        override fun hashCode() = System.identityHashCode(this)
    }

    /**
     * [hasGyro] is false under profile 4, where the firmware never sends the gyroscope. The gyro
     * fields are then 0, which is indistinguishable from a genuinely still sensor — anything that
     * writes or plots gyro data has to check this flag rather than trust the zeros.
     */
    data class Imu(
        val index: Long,
        val samples: List<ImuSample>,
        val hasGyro: Boolean = true
    ) : NailPacket

    /** `STAT,...` / `LINK,...` / `RATE,...` / `END`. */
    data class Text(val line: String) : NailPacket
}

/**
 * Decodes one notification. Returns null for anything malformed rather than throwing — a corrupt
 * packet must not tear down a live recording.
 */
fun decodeNailPacket(
    data: ByteArray,
    gyroDpsPerLsb: Float = NailProtocol.GYRO_DPS_PER_LSB
): NailPacket? {
    if (data.isEmpty()) return null

    val magic = data[0].toInt() and 0xFF

    if (magic == NailProtocol.MAGIC_TEXT) {
        return NailPacket.Text(String(data, 1, data.size - 1, Charsets.US_ASCII).trim())
    }

    if (magic == NailProtocol.MAGIC_MIC_ADPCM ||
        magic == NailProtocol.MAGIC_MIC_ULAW ||
        magic == NailProtocol.MAGIC_IMU_PACK
    ) {
        return decodePackedPacket(magic, data, gyroDpsPerLsb)
    }

    if (data.size < NailProtocol.HEADER_BYTES) return null

    // uint32 LE, widened to Long so it never reads back negative.
    val index = ((data[1].toLong() and 0xFF) or
        ((data[2].toLong() and 0xFF) shl 8) or
        ((data[3].toLong() and 0xFF) shl 16) or
        ((data[4].toLong() and 0xFF) shl 24))
    val count = data[5].toInt() and 0xFF
    val payload = data.size - NailProtocol.HEADER_BYTES

    return when (magic) {
        NailProtocol.MAGIC_MIC -> {
            if (payload < count * 2) return null
            val samples = ShortArray(count)
            var at = NailProtocol.HEADER_BYTES
            for (i in 0 until count) {
                samples[i] = ((data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)).toShort()
                at += 2
            }
            NailPacket.Mic(index, samples)
        }

        NailProtocol.MAGIC_IMU -> {
            if (payload < count * NailProtocol.IMU_SAMPLE_BYTES) return null
            val samples = ArrayList<ImuSample>(count)
            var at = NailProtocol.HEADER_BYTES
            for (i in 0 until count) {
                // Wire order is accel xyz then gyro xyz.
                //
                // The sensor's registers really do read gyro first (0x22 onwards), and this used
                // to be written that way round — but the firmware stores those bytes into
                // `imu_sample_t { ax, ay, az, gx, gy, gz }` and memcpy's the whole struct, so the
                // struct's declaration order is what reaches the wire, not the register order.
                // Decoding it the register way swaps the two sensors: gravity then shows up in
                // the gyro columns and the accel columns read ~0.03 g. Both still look like
                // plausible sensor data, which is why it survived until someone checked that
                // |accel| is 1 g at rest. Do that check after any change here.
                val ax = readShort(data, at)
                val ay = readShort(data, at + 2)
                val az = readShort(data, at + 4)
                val gx = readShort(data, at + 6)
                val gy = readShort(data, at + 8)
                val gz = readShort(data, at + 10)
                samples += ImuSample(
                    ax = ax * NailProtocol.ACCEL_G_PER_LSB,
                    ay = ay * NailProtocol.ACCEL_G_PER_LSB,
                    az = az * NailProtocol.ACCEL_G_PER_LSB,
                    gx = gx * gyroDpsPerLsb,
                    gy = gy * gyroDpsPerLsb,
                    gz = gz * gyroDpsPerLsb
                )
                at += NailProtocol.IMU_SAMPLE_BYTES
            }
            NailPacket.Imu(index, samples)
        }

        else -> null
    }
}

private fun readShort(data: ByteArray, at: Int): Int =
    ((data[at].toInt() and 0xFF) or (data[at + 1].toInt() shl 8)).toShort().toInt()

private fun readU32(data: ByteArray, at: Int): Long =
    ((data[at].toLong() and 0xFF) or
        ((data[at + 1].toLong() and 0xFF) shl 8) or
        ((data[at + 2].toLong() and 0xFF) shl 16) or
        ((data[at + 3].toLong() and 0xFF) shl 24))

/** Standard IMA/DVI ADPCM tables. Must stay identical to the firmware's copy. */
private val IMA_STEP_TABLE = intArrayOf(
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17,
    19, 21, 23, 25, 28, 31, 34, 37, 41, 45,
    50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307,
    337, 371, 408, 449, 494, 544, 598, 658, 724, 796,
    876, 963, 1060, 1166, 1282, 1411, 1552, 1707, 1878, 2066,
    2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358,
    5894, 6484, 7132, 7845, 8630, 9493, 10442, 11487, 12635, 13899,
    15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767
)

private val IMA_INDEX_TABLE = intArrayOf(
    -1, -1, -1, -1, 2, 4, 6, 8,
    -1, -1, -1, -1, 2, 4, 6, 8
)

/**
 * Decodes the compressed packet formats.
 *
 * Both are stateless from the receiver's point of view: the ADPCM predictor and step index travel
 * in every packet's header, so a dropped packet costs exactly its own samples and never desyncs
 * the ones after it. That matters here because loss is detected by absolute index, and a decoder
 * that carried state across packets would silently corrupt everything downstream of a gap.
 */
private fun decodePackedPacket(
    magic: Int,
    data: ByteArray,
    gyroDpsPerLsb: Float
): NailPacket? {
    if (data.size < NailProtocol.HEADER2_BYTES) return null

    val index = readU32(data, 1)
    val count = (data[5].toInt() and 0xFF) or ((data[6].toInt() and 0xFF) shl 8)
    val aux = data[7].toInt() and 0xFF

    if (magic == NailProtocol.MAGIC_MIC_ULAW) {
        if (data.size < NailProtocol.HEADER2_BYTES + count) return null
        val samples = ShortArray(count)
        for (i in 0 until count) {
            // Standard G.711 expansion. Verified byte for byte against Python's audioop.
            val u = data[NailProtocol.HEADER2_BYTES + i].toInt().inv() and 0xFF
            var t = ((u and 0x0F) shl 3) + 0x84
            t = t shl ((u and 0x70) shr 4)
            samples[i] = (if (u and 0x80 != 0) 0x84 - t else t - 0x84).toShort()
        }
        return NailPacket.Mic(index, samples)
    }

    if (magic == NailProtocol.MAGIC_MIC_ADPCM) {
        if (data.size < NailProtocol.ADPCM_HDR_BYTES + (count + 1) / 2) return null

        var pred = readShort(data, 8)
        var stepIndex = aux.coerceIn(0, 88)
        val samples = ShortArray(count)
        val body = NailProtocol.ADPCM_HDR_BYTES

        for (i in 0 until count) {
            val byte = data[body + (i shr 1)].toInt() and 0xFF
            val code = if (i and 1 == 0) byte and 0x0F else byte shr 4

            val step = IMA_STEP_TABLE[stepIndex]
            var delta = step shr 3
            if (code and 4 != 0) delta += step
            if (code and 2 != 0) delta += step shr 1
            if (code and 1 != 0) delta += step shr 2

            pred = if (code and 8 != 0) pred - delta else pred + delta
            pred = pred.coerceIn(-32768, 32767)
            stepIndex = (stepIndex + IMA_INDEX_TABLE[code and 0x0F]).coerceIn(0, 88)
            samples[i] = pred.toShort()
        }
        return NailPacket.Mic(index, samples)
    }

    // MAGIC_IMU_PACK: 12-bit values, back to back, no padding between samples.
    val accelOnly = aux == NailProtocol.IMU_PACK_FMT_ACCEL
    val perSample = if (accelOnly) 3 else 6
    val nValues = count * perSample
    if (data.size < NailProtocol.HEADER2_BYTES + (nValues * 12 + 7) / 8) return null

    // The firmware dropped the low 4 bits; shifting back up keeps the same LSB scale as the raw
    // format, so ACCEL_G_PER_LSB / GYRO_DPS_PER_LSB apply unchanged.
    val values = IntArray(nValues)
    var pos = NailProtocol.HEADER2_BYTES
    var i = 0
    while (i + 1 < nValues) {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        val b2 = data[pos + 2].toInt() and 0xFF
        pos += 3
        values[i] = signExtend12(b0 or ((b1 and 0x0F) shl 8)) shl 4
        values[i + 1] = signExtend12((b1 shr 4) or (b2 shl 4)) shl 4
        i += 2
    }
    if (i < nValues) {
        val b0 = data[pos].toInt() and 0xFF
        val b1 = data[pos + 1].toInt() and 0xFF
        values[i] = signExtend12(b0 or ((b1 and 0x0F) shl 8)) shl 4
    }

    val samples = ArrayList<ImuSample>(count)
    for (s in 0 until count) {
        val at = s * perSample
        samples += ImuSample(
            ax = values[at] * NailProtocol.ACCEL_G_PER_LSB,
            ay = values[at + 1] * NailProtocol.ACCEL_G_PER_LSB,
            az = values[at + 2] * NailProtocol.ACCEL_G_PER_LSB,
            gx = if (accelOnly) 0f else values[at + 3] * gyroDpsPerLsb,
            gy = if (accelOnly) 0f else values[at + 4] * gyroDpsPerLsb,
            gz = if (accelOnly) 0f else values[at + 5] * gyroDpsPerLsb
        )
    }
    return NailPacket.Imu(index, samples, hasGyro = !accelOnly)
}

private fun signExtend12(v: Int): Int = if (v and 0x800 != 0) v - 0x1000 else v

/** Turns a firmware RATE line into named fields without depending on field order. */
internal fun parseRateFields(line: String): Map<String, String> =
    line.removePrefix("RATE,")
        .split(',')
        .mapNotNull { field ->
            val equals = field.indexOf('=')
            if (equals <= 0) null else field.substring(0, equals) to field.substring(equals + 1)
        }
        .toMap()

/**
 * New firmware identifies the board explicitly. Old Nail firmware has no identity fields, so null
 * deliberately means "keep the mode the user picked" rather than guessing from its sample rate.
 */
internal fun inferDeviceMode(fields: Map<String, String>): DeviceMode? {
    val board = fields["board"].orEmpty().uppercase()
    val sensor = fields["sensor"].orEmpty().uppercase()
    return when {
        fields["has_mic"] == "0" || "DUAL_ERM" in board || "BMI160" in sensor ->
            DeviceMode.DualErm
        fields["has_mic"] == "1" || "NAIL" in board || "LSM6" in sensor ->
            DeviceMode.Nail
        else -> null
    }
}

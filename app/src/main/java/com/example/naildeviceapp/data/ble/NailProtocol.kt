package com.example.naildeviceapp.data.ble

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

    const val HEADER_BYTES = 6
    const val IMU_SAMPLE_BYTES = 12

    val CMD_START = byteArrayOf('s'.code.toByte())
    val CMD_STOP = byteArrayOf('e'.code.toByte())

    /** `0xA0 <kHz>` — kHz is one of 16, 8, 4, 2, 1. */
    fun cmdSetRate(kHz: Int) = byteArrayOf(0xA0.toByte(), kHz.toByte())

    /** PDM hardware rate; every offered rate is this divided by a power of two. */
    const val MIC_BASE_RATE_HZ = 16125

    /** Advertised name of the firmware's NUS peripheral. */
    const val DEVICE_NAME = "Nordic_UART"

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

    data class Imu(val index: Long, val samples: List<ImuSample>) : NailPacket

    /** `STAT,...` / `LINK,...` / `RATE,...` / `END`. */
    data class Text(val line: String) : NailPacket
}

/**
 * Decodes one notification. Returns null for anything malformed rather than throwing — a corrupt
 * packet must not tear down a live recording.
 */
fun decodeNailPacket(data: ByteArray): NailPacket? {
    if (data.isEmpty()) return null

    val magic = data[0].toInt() and 0xFF

    if (magic == NailProtocol.MAGIC_TEXT) {
        return NailPacket.Text(String(data, 1, data.size - 1, Charsets.US_ASCII).trim())
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
                // Firmware order is gyro xyz then accel xyz (lsm6_read_sample, register 0x22).
                val gx = readShort(data, at)
                val gy = readShort(data, at + 2)
                val gz = readShort(data, at + 4)
                val ax = readShort(data, at + 6)
                val ay = readShort(data, at + 8)
                val az = readShort(data, at + 10)
                samples += ImuSample(
                    ax = ax * NailProtocol.ACCEL_G_PER_LSB,
                    ay = ay * NailProtocol.ACCEL_G_PER_LSB,
                    az = az * NailProtocol.ACCEL_G_PER_LSB,
                    gx = gx * NailProtocol.GYRO_DPS_PER_LSB,
                    gy = gy * NailProtocol.GYRO_DPS_PER_LSB,
                    gz = gz * NailProtocol.GYRO_DPS_PER_LSB
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

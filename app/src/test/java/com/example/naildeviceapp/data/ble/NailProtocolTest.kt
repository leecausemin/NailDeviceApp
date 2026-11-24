package com.example.naildeviceapp.data.ble

import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.StreamProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Verifies the decoder against packets built exactly as `ble_app_uart/main.c` emits them.
 * Endianness, the uint32 index and the gyro-before-accel field order are the easy things to get
 * silently wrong — a mistake there yields plausible-looking but wrong data.
 */
class NailProtocolTest {

    private fun header(magic: Int, index: Long, count: Int) = byteArrayOf(
        magic.toByte(),
        (index and 0xFF).toByte(),
        ((index shr 8) and 0xFF).toByte(),
        ((index shr 16) and 0xFF).toByte(),
        ((index shr 24) and 0xFF).toByte(),
        count.toByte()
    )

    private fun le16(value: Int) = byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte()
    )

    @Test
    fun `mic packet decodes index and little-endian samples`() {
        val packet = header(NailProtocol.MAGIC_MIC, 1000L, 3) +
            le16(1) + le16(-2) + le16(32767)

        val decoded = decodeNailPacket(packet) as NailPacket.Mic

        assertEquals(1000L, decoded.index)
        assertEquals(3, decoded.samples.size)
        assertEquals(1.toShort(), decoded.samples[0])
        assertEquals((-2).toShort(), decoded.samples[1])
        assertEquals(32767.toShort(), decoded.samples[2])
    }

    @Test
    fun `large index stays positive`() {
        // 3 billion overflows a signed Int; the decoder widens to Long for exactly this reason.
        val big = 3_000_000_000L
        val packet = header(NailProtocol.MAGIC_MIC, big, 1) + le16(0)

        val decoded = decodeNailPacket(packet) as NailPacket.Mic

        assertEquals(big, decoded.index)
        assertTrue(decoded.index > 0)
    }

    @Test
    fun `imu packet reads accel first then gyro, converted to physical units`() {
        // Accel comes first on the wire. The sensor's registers from 0x22 really do read gyro
        // first, and this test used to assert that — but the firmware copies those bytes into
        // `imu_sample_t { ax, ay, az, gx, gy, gz }` and memcpy's the struct, so declaration order
        // is what reaches the wire. The decoder was corrected in fa2d309; this test was not, and
        // kept failing while asserting the bug.
        val packet = header(NailProtocol.MAGIC_IMU, 7L, 1) +
            le16(1000) + le16(-2000) + le16(2048) + // accel
            le16(100) + le16(200) + le16(300)       // gyro

        val decoded = decodeNailPacket(packet) as NailPacket.Imu
        val sample = decoded.samples.single()

        assertEquals(7L, decoded.index)
        assertEquals(1000 * NailProtocol.ACCEL_G_PER_LSB, sample.ax, 1e-6f)
        assertEquals(-2000 * NailProtocol.ACCEL_G_PER_LSB, sample.ay, 1e-6f)
        assertEquals(100 * NailProtocol.GYRO_DPS_PER_LSB, sample.gx, 1e-6f)
        assertEquals(200 * NailProtocol.GYRO_DPS_PER_LSB, sample.gy, 1e-6f)
        assertEquals(300 * NailProtocol.GYRO_DPS_PER_LSB, sample.gz, 1e-6f)

        // 2048 LSB at +-16g is 1 g — the gravity check the firmware notes rely on, and the only
        // thing that catches accel and gyro being swapped, since swapped data still looks real.
        assertEquals(1.0f, sample.az, 0.01f)
    }

    @Test
    fun `imu packet decodes multiple samples in order`() {
        val packet = header(NailProtocol.MAGIC_IMU, 40L, 2) +
            le16(1) + le16(2) + le16(3) + le16(4) + le16(5) + le16(6) +
            le16(7) + le16(8) + le16(9) + le16(10) + le16(11) + le16(12)

        val decoded = decodeNailPacket(packet) as NailPacket.Imu

        assertEquals(2, decoded.samples.size)
        assertEquals(1 * NailProtocol.ACCEL_G_PER_LSB, decoded.samples[0].ax, 1e-6f)
        assertEquals(4 * NailProtocol.GYRO_DPS_PER_LSB, decoded.samples[0].gx, 1e-6f)
        assertEquals(7 * NailProtocol.ACCEL_G_PER_LSB, decoded.samples[1].ax, 1e-6f)
        assertEquals(10 * NailProtocol.GYRO_DPS_PER_LSB, decoded.samples[1].gx, 1e-6f)
    }

    @Test
    fun `text packet strips magic and whitespace`() {
        val packet = byteArrayOf(NailProtocol.MAGIC_TEXT.toByte()) +
            "RATE,mic_hz=4031,imu_hz=10\n".toByteArray(Charsets.US_ASCII)

        val decoded = decodeNailPacket(packet) as NailPacket.Text

        assertEquals("RATE,mic_hz=4031,imu_hz=10", decoded.line)
    }

    @Test
    fun `truncated payload is rejected rather than throwing`() {
        // Header claims 10 samples but only one is present.
        val packet = header(NailProtocol.MAGIC_MIC, 0L, 10) + le16(1)

        assertNull(decodeNailPacket(packet))
    }

    @Test
    fun `unknown magic and empty input are rejected`() {
        assertNull(decodeNailPacket(byteArrayOf()))
        assertNull(decodeNailPacket(header(0x77, 0L, 0)))
    }

    @Test
    fun `rate command matches firmware encoding`() {
        val command = NailProtocol.cmdSetRate(4)

        assertEquals(2, command.size)
        assertEquals(0xA0, command[0].toInt() and 0xFF)
        assertEquals(4, command[1].toInt())
    }

    /* ---- Compressed profiles -------------------------------------------------------------
     *
     * These byte vectors were produced by the firmware's own encoder (transcribed and then
     * checked against Python's audioop, which agreed byte for byte) and the expected values by
     * the PC receiver, which was in turn confirmed against real hardware: a still board reads
     * 1.019 g through the 12-bit path, matching what the raw path reports. A decoder that
     * disagrees with these vectors is wrong, however plausible its output looks.
     */

    @Test
    fun `adpcm mic packet decodes against the firmware encoder`() {
        val packet = byteArrayOf(
            -90, 7, 0, 0, 0, 16, 0, 0, 0, 0,
            112, 119, 119, 87, -54, -69, -101, 9
        )
        val expected = intArrayOf(
            0, 11, 41, 104, 240, 533, 1164, 2159,
            1497, 414, -605, -1532, -2373, -2701, -2999, -2909
        )

        val decoded = decodeNailPacket(packet) as NailPacket.Mic

        assertEquals(7L, decoded.index)
        assertEquals(16, decoded.samples.size)
        for (i in expected.indices) {
            assertEquals("sample $i", expected[i], decoded.samples[i].toInt())
        }
    }

    @Test
    fun `packed imu packet keeps scale and loses only the low four bits`() {
        val packet = byteArrayOf(
            91, 9, 0, 0, 0, 3, 0, 0,
            -128, 0, -4, -24, 3, -128, -1, 7, 0, 6,
            -64, 0, 18, -112, 1, 31, 80, 2,
            -1, -17, -1, -2, -17, -1, -2, -17, -1
        )
        // Raw LSB the firmware started from, after dropping four bits.
        val expected = intArrayOf(
            2048, -1024, 16000, -32768, 32752, 0,
            96, 192, 288, 400, 496, 592,
            -16, -32, -32, -32, -32, -32
        )

        val decoded = decodeNailPacket(packet) as NailPacket.Imu

        assertEquals(9L, decoded.index)
        assertEquals(3, decoded.samples.size)
        assertTrue(decoded.hasGyro)
        val g = NailProtocol.ACCEL_G_PER_LSB
        val d = NailProtocol.GYRO_DPS_PER_LSB
        for (s in 0 until 3) {
            val sample = decoded.samples[s]
            assertEquals(expected[s * 6] * g, sample.ax, 1e-6f)
            assertEquals(expected[s * 6 + 1] * g, sample.ay, 1e-6f)
            assertEquals(expected[s * 6 + 2] * g, sample.az, 1e-6f)
            assertEquals(expected[s * 6 + 3] * d, sample.gx, 1e-4f)
            assertEquals(expected[s * 6 + 4] * d, sample.gy, 1e-4f)
            assertEquals(expected[s * 6 + 5] * d, sample.gz, 1e-4f)
        }
    }

    @Test
    fun `accel-only packet reports that the gyro is absent`() {
        // Format byte 1 means three accel values per sample, so 9 twelve-bit values in all.
        // Note the odd count: 9 values is not a whole number of 3-byte pairs, and the final
        // value occupies a byte and a half with the top nibble left as padding.
        val packet = byteArrayOf(
            91, 0, 0, 0, 0, 3, 0, 1,
            -128, 0, -4, -24, 99, 0, 12, 32, 1, -1, -17, -1, -2, 15
        )
        val expected = intArrayOf(2048, -1024, 16000, 96, 192, 288, -16, -32, -32)

        val decoded = decodeNailPacket(packet) as NailPacket.Imu

        assertEquals(3, decoded.samples.size)
        assertTrue("gyro must be flagged missing, not reported as zero", !decoded.hasGyro)
        val g = NailProtocol.ACCEL_G_PER_LSB
        for (s in 0 until 3) {
            assertEquals(expected[s * 3] * g, decoded.samples[s].ax, 1e-6f)
            assertEquals(expected[s * 3 + 1] * g, decoded.samples[s].ay, 1e-6f)
            assertEquals(expected[s * 3 + 2] * g, decoded.samples[s].az, 1e-6f)
        }
        assertEquals(0f, decoded.samples[0].gx, 0f)
    }

    @Test
    fun `truncated compressed packets are rejected rather than decoded short`() {
        assertNull(decodeNailPacket(byteArrayOf(-90, 0, 0, 0, 0, 100, 0, 0, 0, 0, 1, 2)))
        assertNull(decodeNailPacket(byteArrayOf(91, 0, 0, 0, 0, 26, 0, 0, 1, 2, 3)))
    }

    @Test
    fun `profile command is 0xA2 followed by the profile code`() {
        val command = NailProtocol.cmdSetProfile(3)
        assertEquals(2, command.size)
        assertEquals(0xA2, command[0].toInt() and 0xFF)
        assertEquals(3, command[1].toInt())
    }

    @Test
    fun `dual erm identity selects bmi160 defaults`() {
        val fields = parseRateFields(
            "RATE,mic_hz=16000,imu_hz=1600,profile=2," +
                "board=DUAL_ERM,sensor=BMI160,has_mic=0"
        )

        val mode = inferDeviceMode(fields)

        assertEquals(DeviceMode.DualErm, mode)
        assertFalse(mode!!.hasMicrophone)
        assertEquals(ImuSamplingRate.Hz1600, mode.defaultImuRate)
        assertEquals(StreamProfile.ImuPacked, mode.defaultStreamProfile)
    }

    @Test
    fun `old firmware without identity keeps the manual mode`() {
        val fields = parseRateFields("RATE,mic_hz=16000,imu_hz=1666,profile=0")

        assertNull(inferDeviceMode(fields))
    }

    @Test
    fun `raw imu decoder accepts bmi160 gyro scale`() {
        val packet = header(NailProtocol.MAGIC_IMU, 0L, 1) +
            le16(0) + le16(0) + le16(2048) +
            le16(164) + le16(-164) + le16(0)

        val decoded = decodeNailPacket(
            packet,
            gyroDpsPerLsb = DeviceMode.DualErm.gyroDpsPerLsb
        ) as NailPacket.Imu

        assertEquals(10f, decoded.samples.single().gx, 1e-4f)
        assertEquals(-10f, decoded.samples.single().gy, 1e-4f)
    }
}

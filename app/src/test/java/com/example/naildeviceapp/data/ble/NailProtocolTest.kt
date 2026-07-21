package com.example.naildeviceapp.data.ble

import org.junit.Assert.assertEquals
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
    fun `imu packet reads gyro first then accel, converted to physical units`() {
        // Firmware sends registers from 0x22: gyro xyz, then accel xyz.
        val packet = header(NailProtocol.MAGIC_IMU, 7L, 1) +
            le16(100) + le16(200) + le16(300) +      // gyro
            le16(1000) + le16(-2000) + le16(16384)   // accel

        val decoded = decodeNailPacket(packet) as NailPacket.Imu
        val sample = decoded.samples.single()

        assertEquals(7L, decoded.index)
        assertEquals(100 * NailProtocol.GYRO_DPS_PER_LSB, sample.gx, 1e-6f)
        assertEquals(200 * NailProtocol.GYRO_DPS_PER_LSB, sample.gy, 1e-6f)
        assertEquals(300 * NailProtocol.GYRO_DPS_PER_LSB, sample.gz, 1e-6f)
        assertEquals(1000 * NailProtocol.ACCEL_G_PER_LSB, sample.ax, 1e-6f)
        assertEquals(-2000 * NailProtocol.ACCEL_G_PER_LSB, sample.ay, 1e-6f)

        // 16384 LSB at +-2g is 1 g — the gravity check the firmware notes rely on.
        assertEquals(1.0f, sample.az, 0.01f)
    }

    @Test
    fun `imu packet decodes multiple samples in order`() {
        val packet = header(NailProtocol.MAGIC_IMU, 40L, 2) +
            le16(1) + le16(2) + le16(3) + le16(4) + le16(5) + le16(6) +
            le16(7) + le16(8) + le16(9) + le16(10) + le16(11) + le16(12)

        val decoded = decodeNailPacket(packet) as NailPacket.Imu

        assertEquals(2, decoded.samples.size)
        assertEquals(1 * NailProtocol.GYRO_DPS_PER_LSB, decoded.samples[0].gx, 1e-6f)
        assertEquals(7 * NailProtocol.GYRO_DPS_PER_LSB, decoded.samples[1].gx, 1e-6f)
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
}

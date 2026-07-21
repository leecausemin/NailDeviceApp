package com.example.naildeviceapp.data.ble

import android.content.Context
import android.util.Log
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Writes the live stream to disk at full rate while the charts show a decimated view.
 *
 * Recording is phone-side by design: the firmware streams continuously and keeps no on-board
 * storage, so a recording is simply the span of the stream the user chose to keep. That makes
 * start instant and the length unbounded.
 *
 * Gaps matter. If packets are lost the sample indices jump, and we pad the WAV with silence so the
 * audio stays aligned to real time instead of quietly shrinking.
 */
class StreamRecorder(private val context: Context) {

    data class Result(val fileName: String, val micSamples: Long, val micGapSamples: Long)

    private var wavFile: File? = null
    private var wavStream: BufferedOutputStream? = null
    private var csvWriter: BufferedWriter? = null

    private var micSampleRate = NailProtocol.MIC_BASE_RATE_HZ
    private var micWritten = 0L
    private var micGaps = 0L

    /** Absolute index we expect next; -1 until the first packet anchors the recording. */
    private var expectedMicIndex = -1L
    private var baseName = ""

    val isRecording: Boolean get() = wavStream != null

    fun start(sampleRate: Int) {
        if (isRecording) return

        micSampleRate = sampleRate
        micWritten = 0
        micGaps = 0
        expectedMicIndex = -1

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        baseName = "nail_$stamp"

        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

        val wav = File(dir, "$baseName.wav")
        val stream = BufferedOutputStream(FileOutputStream(wav))
        stream.write(ByteArray(WAV_HEADER_BYTES))   // patched with real sizes on stop
        wavFile = wav
        wavStream = stream

        csvWriter = File(dir, "$baseName.csv").bufferedWriter().apply {
            write("index,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps\n")
        }

        Log.i(TAG, "recording started: $baseName at $sampleRate Hz")
    }

    fun appendMic(index: Long, samples: ShortArray) {
        val stream = wavStream ?: return

        if (expectedMicIndex < 0) expectedMicIndex = index

        // Pad the hole so playback stays time-aligned; a lost packet must not shift audio earlier.
        val gap = index - expectedMicIndex
        if (gap > 0) {
            writeSilence(stream, gap)
            micGaps += gap
            micWritten += gap
        }

        val bytes = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            val value = samples[i].toInt()
            bytes[i * 2] = (value and 0xFF).toByte()
            bytes[i * 2 + 1] = ((value shr 8) and 0xFF).toByte()
        }
        stream.write(bytes)

        micWritten += samples.size
        expectedMicIndex = index + samples.size
    }

    fun appendImu(index: Long, samples: List<ImuSample>) {
        val writer = csvWriter ?: return
        samples.forEachIndexed { offset, s ->
            writer.write("${index + offset},${s.ax},${s.ay},${s.az},${s.gx},${s.gy},${s.gz}\n")
        }
    }

    fun stop(): Result? {
        val stream = wavStream ?: return null
        val file = wavFile ?: return null

        return try {
            stream.flush()
            stream.close()
            csvWriter?.flush()
            csvWriter?.close()
            patchWavHeader(file, micWritten, micSampleRate)
            Log.i(TAG, "recording stopped: $baseName, $micWritten samples, $micGaps padded")
            Result(baseName, micWritten, micGaps)
        } catch (e: Exception) {
            Log.e(TAG, "failed to finalise recording", e)
            null
        } finally {
            wavStream = null
            wavFile = null
            csvWriter = null
        }
    }

    private fun writeSilence(stream: BufferedOutputStream, samples: Long) {
        var left = samples
        val chunk = ByteArray(2 * SILENCE_CHUNK_SAMPLES)
        while (left > 0) {
            val n = minOf(left, SILENCE_CHUNK_SAMPLES.toLong()).toInt()
            stream.write(chunk, 0, n * 2)
            left -= n
        }
    }

    /** 16-bit mono PCM header, written last because the sizes are only known at the end. */
    private fun patchWavHeader(file: File, sampleCount: Long, sampleRate: Int) {
        val dataBytes = (sampleCount * 2).toInt()
        val byteRate = sampleRate * 2

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write("RIFF".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe(36 + dataBytes)
            raf.write("WAVE".toByteArray(Charsets.US_ASCII))
            raf.write("fmt ".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe(16)          // PCM chunk size
            raf.writeShortLe(1)         // format = PCM
            raf.writeShortLe(1)         // channels = mono
            raf.writeIntLe(sampleRate)
            raf.writeIntLe(byteRate)
            raf.writeShortLe(2)         // block align
            raf.writeShortLe(16)        // bits per sample
            raf.write("data".toByteArray(Charsets.US_ASCII))
            raf.writeIntLe(dataBytes)
        }
    }

    private companion object {
        const val TAG = "StreamRecorder"
        const val WAV_HEADER_BYTES = 44
        const val SILENCE_CHUNK_SAMPLES = 4096
    }
}

private fun RandomAccessFile.writeIntLe(value: Int) {
    write(byteArrayOf(
        (value and 0xFF).toByte(),
        ((value shr 8) and 0xFF).toByte(),
        ((value shr 16) and 0xFF).toByte(),
        ((value shr 24) and 0xFF).toByte()
    ))
}

private fun RandomAccessFile.writeShortLe(value: Int) {
    write(byteArrayOf((value and 0xFF).toByte(), ((value shr 8) and 0xFF).toByte()))
}

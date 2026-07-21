package com.example.naildeviceapp.data.ble

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import java.io.BufferedWriter
import java.io.File
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
 * Microphone and IMU samples are stored as separate CSV files. Absolute sample indices preserve
 * packet-loss information without inventing replacement samples.
 */
class StreamRecorder(private val context: Context) {

    data class Result(val fileName: String, val micSamples: Long, val micGapSamples: Long)

    private var micFile: File? = null
    private var imuFile: File? = null
    private var micWriter: BufferedWriter? = null
    private var imuWriter: BufferedWriter? = null

    private var micWritten = 0L
    private var micGaps = 0L

    /** Absolute index we expect next; -1 until the first packet anchors the recording. */
    private var expectedMicIndex = -1L
    private var baseName = ""

    val isRecording: Boolean get() = micWriter != null

    fun start(sampleRate: Int) {
        if (isRecording) return

        micWritten = 0
        micGaps = 0
        expectedMicIndex = -1

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        baseName = "nail_$stamp"

        val dir = File(context.getExternalFilesDir(null), "recordings").apply { mkdirs() }

        val mic = File(dir, "${baseName}_mic.csv")
        micFile = mic
        micWriter = mic.bufferedWriter().apply {
            write("index,pcm\n")
        }

        val imu = File(dir, "${baseName}_imu.csv")
        imuFile = imu
        imuWriter = imu.bufferedWriter().apply {
            write("index,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps\n")
        }

        Log.i(TAG, "recording started: $baseName at $sampleRate Hz")
    }

    fun appendMic(index: Long, samples: ShortArray) {
        val writer = micWriter ?: return

        if (expectedMicIndex < 0) expectedMicIndex = index

        // Keep the gap visible in the indices instead of silently closing it.
        val gap = index - expectedMicIndex
        if (gap > 0) {
            micGaps += gap
        }

        val rows = StringBuilder(samples.size * 16)
        for (i in samples.indices) {
            rows.append(index + i).append(',').append(samples[i]).append('\n')
        }
        writer.write(rows.toString())

        micWritten += samples.size
        expectedMicIndex = index + samples.size
    }

    fun appendImu(index: Long, samples: List<ImuSample>) {
        val writer = imuWriter ?: return
        samples.forEachIndexed { offset, s ->
            writer.write("${index + offset},${s.ax},${s.ay},${s.az},${s.gx},${s.gy},${s.gz}\n")
        }
    }

    fun stop(): Result? {
        val mic = micFile ?: return null
        val imu = imuFile ?: return null

        return try {
            micWriter?.flush()
            micWriter?.close()
            imuWriter?.flush()
            imuWriter?.close()
            publishToDownloads(mic, imu)
            Log.i(TAG, "recording stopped: $baseName, $micWritten samples, $micGaps lost")
            Result(baseName, micWritten, micGaps)
        } catch (e: Exception) {
            Log.e(TAG, "failed to finalise recording", e)
            null
        } finally {
            micWriter = null
            micFile = null
            imuFile = null
            imuWriter = null
        }
    }

    /** Makes completed recordings visible in My Files > Downloads > NailDeviceApp. */
    private fun publishToDownloads(mic: File, imu: File) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Public Downloads export requires Android 10 or newer")
            return
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIRECTORY"
        val pendingUris = mutableListOf<Uri>()

        try {
            listOf(
                Triple(mic, "${baseName}_mic.csv", "text/csv"),
                Triple(imu, "${baseName}_imu.csv", "text/csv")
            ).forEach { (source, displayName, mimeType) ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(collection, values)) {
                    "Failed to create $displayName in Downloads"
                }
                pendingUris += uri
                source.inputStream().use { input ->
                    checkNotNull(resolver.openOutputStream(uri, "w")).use { output ->
                        input.copyTo(output)
                    }
                }
            }

            val ready = ContentValues().apply {
                put(MediaStore.MediaColumns.IS_PENDING, 0)
            }
            pendingUris.forEach { uri -> resolver.update(uri, ready, null, null) }

            mic.delete()
            imu.delete()
            Log.i(TAG, "recording published to Downloads/$DOWNLOAD_SUBDIRECTORY/$baseName")
        } catch (e: Exception) {
            pendingUris.forEach { uri -> resolver.delete(uri, null, null) }
            throw e
        }
    }

    private companion object {
        const val TAG = "StreamRecorder"
        const val DOWNLOAD_SUBDIRECTORY = "NailDeviceApp"
    }
}

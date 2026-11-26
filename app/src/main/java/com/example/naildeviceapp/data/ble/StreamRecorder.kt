package com.example.naildeviceapp.data.ble

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.util.Log
import com.example.naildeviceapp.data.RecordingLocationPreferences
import java.io.BufferedWriter
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

/**
 * Writes the live stream to disk at full rate while the charts show a resampled view.
 *
 * Recording is phone-side by design: the firmware streams continuously and keeps no on-board
 * storage, so a recording is simply the span of the stream the user chose to keep. That makes
 * start instant and the length unbounded.
 *
 * One recording is one folder: the two CSVs and a session note travel together, because the CSVs
 * alone cannot be interpreted later. Nothing in them records the sample rate, so a bare
 * `index,pcm` file is unreadable once the settings are forgotten — the note is what makes the
 * numbers mean something.
 *
 * Absolute sample indices preserve packet-loss information without inventing replacement samples.
 */
class StreamRecorder(
    private val context: Context,
    private val destination: RecordingLocationPreferences
) {

    /** The settings in force when recording began; copied into the session note verbatim. */
    data class Settings(
        val deviceName: String,
        val micRateHz: Int,
        val imuRateHz: Int,
        val compression: String,
        val compressionCode: Int,
        val lossless: Boolean,
        /** Board-measured gap between the two streams' sample 0; null if it never reported one. */
        val originOffsetUs: Int?
    )

    data class Result(val fileName: String, val micSamples: Long, val micGapSamples: Long)

    private var folder: File? = null
    private var micFile: File? = null
    private var imuFile: File? = null
    private var micWriter: BufferedWriter? = null
    private var imuWriter: BufferedWriter? = null

    private var micWritten = 0L
    private var micGaps = 0L
    private var imuWritten = 0L
    private var imuGaps = 0L

    /** Absolute index we expect next; -1 until the first packet anchors the recording. */
    private var expectedMicIndex = -1L
    private var expectedImuIndex = -1L

    /** True once any packet arrived without gyroscope data, i.e. under an accel-only profile. */
    private var gyroMissing = false

    /**
     * Times the board restarted its sample numbering mid-recording, per stream.
     *
     * The index column then runs twice, with real time missing in between, and nothing about the
     * file says so. Silently renumbering to hide it would be worse — the note has to say it.
     */
    private var micRenumbered = 0
    private var imuRenumbered = 0

    private var baseName = ""
    private var settings: Settings? = null
    private var startedAt = 0L
    private var label: String? = null

    /**
     * Phone wall-clock time (epoch ms) when the first packet of each stream reached [appendMic] /
     * [appendImu]. Written into the session note: with the PC's clock offset (measured by the
     * remote-control PING) this places sample 0 on the PC's timeline to within BLE latency.
     */
    @Volatile
    private var firstMicArrivalMs = 0L
    @Volatile
    private var firstImuArrivalMs = 0L

    /**
     * Samples added to every IMU index so both files share one origin.
     *
     * The board starts the two streams a fraction of a second apart and measures the gap on its own
     * clock. Folding that gap in here means `index / rate` is the same timeline in both CSVs, rather
     * than two timelines that happen to look alike. Loss is unaffected: a constant shift moves the
     * gaps along with the samples.
     */
    @Volatile
    private var imuIndexShift = 0L

    /**
     * Formats rows and writes them, off the caller's thread.
     *
     * [appendMic] and [appendImu] are called from `NailBleClient`'s single stream thread — the same
     * one that drains BLE notifications and draws every chart frame. Doing the file work there is
     * what made recording, and only recording, both lag and pull the two traces apart: at mic
     * 16 kHz plus IMU 1666 Hz this is 17,666 rows a second to format, and a BufferedWriter reaching
     * disk about twenty times a second, all of it blocking the thread that was supposed to be
     * consuming packets and ticking the charts. The board's rings then filled while the phone was
     * busy, and chart frames arrived late and unevenly.
     *
     * Sized and configured so that recording can never lose a sample:
     *  - one thread, so rows keep their order and the writers need no locking;
     *  - a bounded queue, so a stalled disk cannot grow the backlog until the app is killed;
     *  - `CallerRunsPolicy`, so when that bound is reached the stream thread does the work itself.
     *    That reintroduces the stall, but only under real disk saturation, and it throttles rather
     *    than drops. A recording with a silent hole in it is worse than a recording that lagged.
     */
    private var writerPool: ThreadPoolExecutor? = null

    val isRecording: Boolean get() = micWriter != null

    /** Hands one formatted-and-write job to [writerPool], or runs it here if there is no pool. */
    private fun offer(job: Runnable) {
        val pool = writerPool
        if (pool == null) job.run() else pool.execute(job)
    }

    /**
     * [label] is appended to the folder and file names (`nail_<stamp>_<label>`) so a recording
     * started by the PC carries the same trial id as the load-cell file. The stamp stays first and
     * the label is sanitised to letters, digits, `-` and `_`, so `nail_<stamp>` parsing still works.
     */
    fun start(settings: Settings, label: String? = null) {
        if (isRecording) return

        micWritten = 0
        micGaps = 0
        imuWritten = 0
        imuGaps = 0
        expectedMicIndex = -1
        expectedImuIndex = -1
        micRenumbered = 0
        imuRenumbered = 0
        gyroMissing = false
        this.settings = settings
        startedAt = System.currentTimeMillis()

        // Positive offset means the IMU's sample 0 was taken later than the microphone's, so its
        // samples belong that much further along the shared timeline.
        imuIndexShift = settings.originOffsetUs?.let { offsetUs ->
            Math.round(offsetUs / 1_000_000.0 * settings.imuRateHz)
        } ?: 0L

        firstMicArrivalMs = 0L
        firstImuArrivalMs = 0L
        this.label = sanitizeLabel(label)
        baseName = "nail_${fileStamp().format(Date(startedAt))}" +
            (this.label?.let { "_$it" } ?: "")

        // One directory per recording, named after it, so the three files cannot be separated by
        // sorting a flat Downloads folder.
        val dir = File(File(context.getExternalFilesDir(null), "recordings"), baseName)
            .apply { mkdirs() }
        folder = dir

        // 64 KB rather than the 8 KB default: at 16 kHz the microphone alone produces roughly
        // 180 KB of text a second, so the default buffer reaches the disk about twenty times a
        // second. Eight times fewer, larger writes is the cheaper shape for the same bytes.
        val mic = File(dir, "${baseName}_mic.csv")
        micFile = mic
        micWriter = mic.bufferedWriter(bufferSize = WRITER_BUFFER_BYTES).apply {
            write("index,pcm\n")
        }

        val imu = File(dir, "${baseName}_imu.csv")
        imuFile = imu
        imuWriter = imu.bufferedWriter(bufferSize = WRITER_BUFFER_BYTES).apply {
            write("index,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps\n")
        }

        // Started here and shut down in stop(), so the thread exists exactly as long as a recording
        // does and an idle app carries nothing.
        writerPool = ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            ArrayBlockingQueue(WRITER_QUEUE_DEPTH),
            { r -> Thread(r, "nail-recorder") },
            ThreadPoolExecutor.CallerRunsPolicy()
        )

        Log.i(TAG, "recording started: $baseName at ${settings.micRateHz} Hz")
    }

    fun appendMic(index: Long, samples: ShortArray) {
        val writer = micWriter ?: return

        if (expectedMicIndex < 0) {
            expectedMicIndex = index
            firstMicArrivalMs = System.currentTimeMillis()
        }

        // Keep the gap visible in the indices instead of silently closing it.
        val gap = index - expectedMicIndex
        if (gap > 0) {
            micGaps += gap
        } else if (gap < 0) {
            // Not loss: the board went back to numbering from zero.
            micRenumbered++
            Log.w(TAG, "mic stream renumbered mid-recording at index $index")
        }

        // Index arithmetic and the counters stay on the caller's thread: they are a few additions,
        // and keeping them here means gap accounting still sees packets in arrival order no matter
        // what the writer thread is doing. Only the formatting and the write are handed off.
        micWritten += samples.size
        expectedMicIndex = index + samples.size

        offer {
            val rows = StringBuilder(samples.size * 16)
            for (i in samples.indices) {
                rows.append(index + i).append(',').append(samples[i]).append('\n')
            }
            // append(CharSequence) instead of write(rows.toString()): the same characters reach the
            // buffer without allocating a second full copy of every packet.
            writer.append(rows)
        }
    }

    /**
     * [hasGyro] is false under the accel-only profile, where the board never sends gyro data.
     * Writing zeros there would read back as a perfectly still gyroscope, so the columns are left
     * empty instead — an empty cell is obviously absent, a zero is not.
     */
    fun appendImu(index: Long, samples: List<ImuSample>, hasGyro: Boolean = true) {
        val writer = imuWriter ?: return

        if (expectedImuIndex < 0) {
            expectedImuIndex = index
            firstImuArrivalMs = System.currentTimeMillis()
        }
        val gap = index - expectedImuIndex
        if (gap > 0) {
            imuGaps += gap
        } else if (gap < 0) {
            imuRenumbered++
            Log.w(TAG, "imu stream renumbered mid-recording at index $index")
        }
        if (!hasGyro) gyroMissing = true

        imuWritten += samples.size
        expectedImuIndex = index + samples.size

        val shift = imuIndexShift
        offer {
            // Batched into one buffer like [appendMic]. This used to be one string template and one
            // writer.write() per sample — 1,666 of each per second at the default rate, on the
            // stream thread.
            val rows = StringBuilder(samples.size * 48)
            samples.forEachIndexed { offset, s ->
                rows.append(index + offset + shift).append(',')
                    .append(s.ax).append(',').append(s.ay).append(',').append(s.az).append(',')
                if (hasGyro) {
                    rows.append(s.gx).append(',').append(s.gy).append(',').append(s.gz)
                } else {
                    rows.append(",,")
                }
                rows.append('\n')
            }
            writer.append(rows)
        }
    }

    fun stop(): Result? {
        val dir = folder ?: return null
        val mic = micFile ?: return null
        val imu = imuFile ?: return null

        return try {
            // Everything already queued has to reach the writers before they are closed, or the
            // tail of the recording is lost at exactly the moment the user thinks it is safe.
            // shutdown() lets the queue finish; only then are the files consistent.
            writerPool?.let { pool ->
                pool.shutdown()
                if (!pool.awaitTermination(WRITER_DRAIN_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                    Log.e(TAG, "recorder queue did not drain in time; the file tail may be short")
                }
            }
            writerPool = null

            micWriter?.flush()
            micWriter?.close()
            imuWriter?.flush()
            imuWriter?.close()

            val note = File(dir, "${baseName}_session.txt")
            note.writeText(sessionNote(System.currentTimeMillis()))

            publish(listOf(mic, imu, note))
            Log.i(TAG, "recording stopped: $baseName, $micWritten samples, $micGaps lost")
            Result(baseName, micWritten, micGaps)
        } catch (e: Exception) {
            Log.e(TAG, "failed to finalise recording", e)
            null
        } finally {
            // Also cleared here: if the drain above threw, the pool must not survive into the next
            // recording holding a thread and a queue of jobs aimed at closed writers.
            writerPool?.shutdownNow()
            writerPool = null
            micWriter = null
            imuWriter = null
            micFile = null
            imuFile = null
            folder = null
            settings = null
        }
    }

    /**
     * Everything needed to read the CSVs back, in plain text.
     *
     * Written for someone opening the folder months later with no memory of the session, so it
     * states the units and the index-to-time rule rather than assuming either.
     */
    private fun sessionNote(stoppedAt: Long): String {
        val s = settings
        val clock = timestamp()
        val durationSeconds = (stoppedAt - startedAt) / 1000.0
        val gyroNote = if (gyroMissing) {
            "absent - recorded under an accel-only profile, gyro columns are empty"
        } else {
            "present"
        }

        return buildString {
            appendLine("Nail device recording")
            appendLine("=====================")
            appendLine()
            appendLine("Recording      $baseName")
            appendLine("Device         ${s?.deviceName?.ifEmpty { "(unnamed)" } ?: "(unknown)"}")
            appendLine("Started        ${clock.format(Date(startedAt))}")
            appendLine("Stopped        ${clock.format(Date(stoppedAt))}")
            appendLine("Duration       ${"%.1f".format(durationSeconds)} s")
            appendLine("Label          ${label ?: "(none)"}")
            appendLine()
            appendLine("Phone clock (epoch ms, System.currentTimeMillis)")
            appendLine("  started            $startedAt")
            appendLine("  stopped            $stoppedAt")
            appendLine("  first mic packet   $firstMicArrivalMs")
            appendLine("  first IMU packet   $firstImuArrivalMs")
            appendLine("               Packet arrival, not sample time: sample 0 was taken one")
            appendLine("               BLE latency (tens of ms) earlier.")
            appendLine()
            appendLine("Sampling rates")
            appendLine("  Microphone   ${s?.micRateHz ?: 0} Hz")
            appendLine("  IMU          ${s?.imuRateHz ?: 0} Hz")
            appendLine("               The IMU rate is an LSM6DSL ODR and is deliberately not a")
            appendLine("               round number; the app's \"6.6k\" style labels are rounded.")
            appendLine("               Use the exact value above for any time calculation.")
            appendLine()
            appendLine("Compression    ${s?.compression ?: "(unknown)"} (profile ${s?.compressionCode ?: -1})")
            if (s?.lossless == true) {
                appendLine("               Lossless - these are the sensors' original values.")
            } else {
                appendLine("               Lossy. The board compresses before transmitting, so the")
                appendLine("               CSVs hold decompressed values, not the sensors' original")
                appendLine("               resolution. The sample rate is unaffected.")
            }
            appendLine()
            appendLine("Microphone     ${baseName}_mic.csv")
            appendLine("  columns      index,pcm")
            appendLine("  units        raw int16 PCM, full scale ${NailProtocol.MIC_FULL_SCALE.toInt()}")
            appendLine("  written      ${"%,d".format(micWritten)} samples")
            appendLine("  lost         ${"%,d".format(micGaps)} samples")
            appendLine()
            appendLine("IMU            ${baseName}_imu.csv")
            appendLine("  columns      index,ax_g,ay_g,az_g,gx_dps,gy_dps,gz_dps")
            appendLine("  units        accelerometer g (+/-16 g), gyroscope dps (+/-2000 dps)")
            appendLine("  written      ${"%,d".format(imuWritten)} samples")
            appendLine("  lost         ${"%,d".format(imuGaps)} samples")
            appendLine("  gyroscope    $gyroNote")
            appendLine()
            appendLine("Stream alignment")
            when (val offset = s?.originOffsetUs) {
                null -> {
                    appendLine("  NOT APPLIED - the board reported no origin offset for this")
                    appendLine("  recording, so the IMU index below is the board's raw index. The")
                    appendLine("  two streams start a few ms apart, so treat any timing measured")
                    appendLine("  between the microphone and the IMU as approximate.")
                }
                else -> {
                    appendLine("  Already applied - nothing to correct when reading these files.")
                    appendLine("  measured offset  $offset us (IMU sample 0 taken this much later")
                    appendLine("                   than mic sample 0, timed on the board's clock)")
                    appendLine("  applied shift    $imuIndexShift samples, added to every IMU index")
                    appendLine("  accurate to      ~61 us (the board's 16384 Hz clock)")
                    appendLine("  So index / rate is the same timeline in both files, and the two")
                    appendLine("  can be overlaid directly. Note this makes the IMU index column a")
                    appendLine("  shifted one; the microphone's is the board's raw index.")
                }
            }
            appendLine()
            if (micRenumbered > 0 || imuRenumbered > 0) {
                appendLine("WARNING - the index columns restart inside this recording")
                appendLine("  microphone   $micRenumbered restart(s)")
                appendLine("  IMU          $imuRenumbered restart(s)")
                appendLine("  The board renumbers from zero when the streaming profile or a")
                appendLine("  sampling rate changes, and that happened while this was recording.")
                appendLine("  Real time is missing across each restart, and the two halves cannot")
                appendLine("  be joined by index. Treat the parts before and after as separate")
                appendLine("  recordings, or discard this one and record again without touching")
                appendLine("  the settings.")
                appendLine()
            }
            appendLine("Reading the CSVs")
            appendLine("  seconds = index / sampling rate, using the exact rates above.")
            appendLine("  The index column is absolute and skips over samples lost in transit, so")
            appendLine("  row number is not time. A jump in the index is missing data, not a pause;")
            appendLine("  no replacement samples were invented to fill it.")
        }
    }

    /**
     * Moves the finished folder to wherever the user chose.
     *
     * A failure on the custom path falls back to Downloads rather than propagating: by this point
     * the samples are already captured, and losing a recording because a folder was deleted or an
     * SD card pulled is a far worse outcome than filing it somewhere unexpected.
     */
    private fun publish(files: List<File>) {
        val target = destination.location.value
        val treeUri = target.treeUri

        if (target.usesCustomFolder && treeUri != null) {
            val published = runCatching { publishToTree(treeUri, files) }
                .onFailure { Log.e(TAG, "could not write to the chosen folder; using Downloads", it) }
                .isSuccess
            if (published) return
        }

        publishToDownloads(files)
    }

    /** Writes into a folder the user granted through the system picker. */
    private fun publishToTree(treeUri: Uri, files: List<File>) {
        val resolver = context.contentResolver
        val parent = DocumentsContract.buildDocumentUriUsingTree(
            treeUri,
            DocumentsContract.getTreeDocumentId(treeUri)
        )

        val recordingDir = checkNotNull(
            DocumentsContract.createDocument(
                resolver,
                parent,
                DocumentsContract.Document.MIME_TYPE_DIR,
                baseName
            )
        ) { "Failed to create $baseName in the chosen folder" }

        files.forEach { source ->
            val document = checkNotNull(
                DocumentsContract.createDocument(
                    resolver,
                    recordingDir,
                    mimeTypeOf(source),
                    source.name
                )
            ) { "Failed to create ${source.name} in the chosen folder" }

            source.inputStream().use { input ->
                checkNotNull(resolver.openOutputStream(document, "w")).use { output ->
                    input.copyTo(output)
                }
            }
        }

        files.forEach { it.delete() }
        folder?.delete()
        Log.i(TAG, "recording published to ${target()} / $baseName")
    }

    private fun target(): String = destination.location.value.folderLabel

    /** Makes completed recordings visible in My Files > Downloads > NailDeviceApp > <recording>. */
    private fun publishToDownloads(files: List<File>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            Log.w(TAG, "Public Downloads export requires Android 10 or newer")
            return
        }

        val resolver = context.contentResolver
        val collection = MediaStore.Downloads.EXTERNAL_CONTENT_URI
        val relativePath = "${Environment.DIRECTORY_DOWNLOADS}/$DOWNLOAD_SUBDIRECTORY/$baseName"
        val pendingUris = mutableListOf<Uri>()

        try {
            files.forEach { source ->
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, source.name)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeTypeOf(source))
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }
                val uri = checkNotNull(resolver.insert(collection, values)) {
                    "Failed to create ${source.name} in Downloads"
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

            files.forEach { it.delete() }
            folder?.delete()
            Log.i(TAG, "recording published to Downloads/$DOWNLOAD_SUBDIRECTORY/$baseName")
        } catch (e: Exception) {
            pendingUris.forEach { uri -> resolver.delete(uri, null, null) }
            throw e
        }
    }

    private fun mimeTypeOf(file: File): String =
        if (file.extension.equals("csv", ignoreCase = true)) "text/csv" else "text/plain"

    private companion object {
        const val TAG = "StreamRecorder"
        const val DOWNLOAD_SUBDIRECTORY = "NailDeviceApp"

        /** See the buffer note in [start]. */
        const val WRITER_BUFFER_BYTES = 64 * 1024

        /**
         * Packets the writer may fall behind by before the stream thread starts doing the work
         * itself. At the default rates a packet arrives roughly every 7 ms, so this is about four
         * seconds of slack — long enough to ride out any plausible disk hiccup, small enough that
         * the queue cannot quietly hold a large fraction of a recording in RAM.
         */
        const val WRITER_QUEUE_DEPTH = 512

        /** Generous: this only has to cover a queue that is already bounded and nearly drained. */
        const val WRITER_DRAIN_TIMEOUT_SECONDS = 10L

        /** Letters, digits, `-`, `_` only, at most 64 chars; null when nothing is left. */
        fun sanitizeLabel(raw: String?): String? =
            raw?.trim()
                ?.map { if (it.isLetterOrDigit() && it.code < 128 || it == '-' || it == '_') it else '_' }
                ?.joinToString("")
                ?.take(64)
                ?.trim('_')
                ?.ifEmpty { null }

        // Built per call rather than shared: SimpleDateFormat is not thread safe.
        fun fileStamp() = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
        fun timestamp() = SimpleDateFormat("yyyy-MM-dd HH:mm:ss z", Locale.US)
    }
}

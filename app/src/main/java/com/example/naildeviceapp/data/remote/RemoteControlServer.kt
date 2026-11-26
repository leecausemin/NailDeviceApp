package com.example.naildeviceapp.data.remote

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.RecordingLocationPreferences
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.RecordingState
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Lets the experiment PC start and stop recordings, so the phone files line up with the load cell
 * and linear stage without anyone pressing a button.
 *
 * Bound to 127.0.0.1 only: the PC reaches it through USB with `adb forward tcp:8765 tcp:8765`, and
 * nothing on the Wi-Fi network can. One client at a time, one text line per command, one line back.
 *
 *     PING              -> PONG <phone epoch ms>            (PC estimates the clock offset from this)
 *     STATUS            -> STATUS conn=<state> rec=<state> file=<last saved name or ->
 *     START <label>     -> OK <phone epoch ms>             (recording nail_<stamp>_<label>)
 *     STOP              -> SAVED <name> <downloads|custom> <phone epoch ms of the stop command>
 *     anything wrong    -> ERR <reason>
 *
 * The client calls arrive on the main thread, like a button press would, so the remote path cannot
 * exercise any threading the UI path does not.
 */
class RemoteControlServer(
    private val client: () -> NailDeviceClient,
    private val location: () -> RecordingLocationPreferences,
    private val port: Int = DEFAULT_PORT
) {

    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread({ serve() }, "nail-remote").apply { isDaemon = true }.start()
    }

    private fun serve() {
        try {
            val socket = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(InetAddress.getLoopbackAddress(), port))
            }
            server = socket
            Log.i(TAG, "remote control listening on 127.0.0.1:$port")
            while (true) {
                val conn = socket.accept()
                // Sequential on purpose: two PCs driving one recorder at once is never intended.
                runCatching { handle(conn) }.onFailure { Log.w(TAG, "remote client ended", it) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "remote control server stopped", e)
        } finally {
            server = null
        }
    }

    private fun handle(conn: Socket) {
        conn.use { s ->
            s.tcpNoDelay = true
            val reader = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.UTF_8))
            val writer = BufferedWriter(OutputStreamWriter(s.getOutputStream(), Charsets.UTF_8))
            Log.i(TAG, "remote client connected")
            while (true) {
                val line = try {
                    reader.readLine()
                } catch (e: SocketException) {
                    null
                } ?: break
                val reply = runCatching { execute(line.trim()) }
                    .getOrElse { "ERR ${it.message ?: it.javaClass.simpleName}" }
                writer.write(reply.replace('\n', ' '))
                writer.write("\n")
                writer.flush()
            }
            Log.i(TAG, "remote client disconnected")
        }
    }

    private fun execute(line: String): String {
        val cmd = line.substringBefore(' ').uppercase()
        val arg = line.substringAfter(' ', "").trim()
        return when (cmd) {
            "PING" -> "PONG ${System.currentTimeMillis()}"
            "STATUS" -> status()
            "START" -> start(arg)
            "STOP" -> stop()
            "" -> "ERR empty command"
            else -> "ERR unknown command $cmd"
        }
    }

    private fun status(): String {
        val c = client()
        val st = c.status.value
        return "STATUS conn=${c.connectionState.value} rec=${st.recording} " +
            "file=${st.savedFileName.ifEmpty { "-" }}"
    }

    private fun start(label: String): String {
        if (label.isEmpty()) return "ERR START needs a label"
        val c = client()
        if (c.connectionState.value != ConnectionState.Connected) {
            return "ERR device not connected (${c.connectionState.value})"
        }
        if (c.status.value.recording == RecordingState.Recording) return "ERR already recording"
        val at = System.currentTimeMillis()
        onMain { c.startLabeledRecording(label) }
        return "OK $at"
    }

    private fun stop(): String {
        val c = client()
        if (c.status.value.recording != RecordingState.Recording) return "ERR not recording"
        val at = System.currentTimeMillis()
        onMain { c.setRecording(RecordingState.Stopped) }
        // savedFileName is cleared on START and filled once both CSVs are flushed and published.
        val deadline = System.currentTimeMillis() + SAVE_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            val name = c.status.value.savedFileName
            if (name.isNotEmpty()) {
                val where = if (location().location.value.usesCustomFolder) "custom" else "downloads"
                return "SAVED $name $where $at"
            }
            Thread.sleep(50)
        }
        return "ERR save timed out after ${SAVE_TIMEOUT_MS / 1000} s"
    }

    private fun onMain(block: () -> Unit) {
        val done = CountDownLatch(1)
        main.post {
            try {
                block()
            } finally {
                done.countDown()
            }
        }
        if (!done.await(5, TimeUnit.SECONDS)) throw IllegalStateException("main thread busy")
    }

    companion object {
        const val DEFAULT_PORT = 8765
        private const val TAG = "RemoteControl"
        private const val SAVE_TIMEOUT_MS = 60_000L
    }
}

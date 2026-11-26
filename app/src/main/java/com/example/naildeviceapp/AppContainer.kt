package com.example.naildeviceapp

import android.content.Context
import com.example.naildeviceapp.data.MockNailDeviceClient
import com.example.naildeviceapp.data.MonitoringPreferences
import com.example.naildeviceapp.data.NailDeviceClient
import com.example.naildeviceapp.data.RecordingLocationPreferences
import com.example.naildeviceapp.data.ble.NailBleClient
import com.example.naildeviceapp.data.remote.RemoteControlServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Process-wide wiring. The scan, monitor and settings screens must observe the *same* instances, so
 * they live here rather than inside any one ViewModel.
 *
 * [init] must run before any screen touches [deviceClient]; [NailApplication] does that.
 */
object AppContainer {

    /** Flip to true to drive the screens from [MockNailDeviceClient] with no hardware present. */
    private const val USE_MOCK_DEVICE = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private lateinit var appContext: Context

    val monitoringPreferences: MonitoringPreferences by lazy { MonitoringPreferences() }

    val recordingLocation: RecordingLocationPreferences by lazy {
        RecordingLocationPreferences(appContext)
    }

    val deviceClient: NailDeviceClient by lazy {
        if (USE_MOCK_DEVICE) MockNailDeviceClient(scope, monitoringPreferences)
        else NailBleClient(appContext, scope, monitoringPreferences, recordingLocation)
    }

    /** Lazily resolves the client so the server can start before any screen exists. */
    val remoteControl: RemoteControlServer by lazy {
        RemoteControlServer(client = { deviceClient }, location = { recordingLocation })
    }

    fun init(context: Context) {
        appContext = context.applicationContext
    }
}

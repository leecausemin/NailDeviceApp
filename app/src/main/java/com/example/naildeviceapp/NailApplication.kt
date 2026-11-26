package com.example.naildeviceapp

import android.app.Application

/** Gives [AppContainer] an application context before any screen asks for the device client. */
class NailApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AppContainer.init(this)
        // PC-side start/stop over `adb forward`; see RemoteControlServer.
        AppContainer.remoteControl.start()
    }
}

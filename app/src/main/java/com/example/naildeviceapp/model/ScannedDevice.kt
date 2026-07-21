package com.example.naildeviceapp.model

/** A peripheral surfaced by the BLE scan, mirroring the iOS peripheral table rows. */
data class ScannedDevice(
    val address: String,
    val name: String,
    val rssi: Int
)

enum class ConnectionState { Idle, Scanning, Connecting, Connected }

/** Per-stream availability marks shown on the scan screen (`IMU_Off` / `Mic_Off` in iOS). */
data class StreamMarks(
    val imuActive: Boolean = false,
    val micActive: Boolean = false
)

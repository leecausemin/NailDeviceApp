package com.example.naildeviceapp.model

/** Signal that feeds the spectrogram and the frequency-power chart on the monitor screen. */
enum class MonitoringSensor(val label: String) {
    Microphone("Microphone"),
    AccelX("Accel X"),
    AccelY("Accel Y"),
    AccelZ("Accel Z"),
    GyroX("Gyro X"),
    GyroY("Gyro Y"),
    GyroZ("Gyro Z")
}

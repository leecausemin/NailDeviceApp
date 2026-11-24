package com.example.naildeviceapp.model

/**
 * One window length for every waveform chart, so the x axes line up when the monitor screen is read
 * as a column.
 *
 * Capacities are derived from the display rates rather than written down beside them. A capacity
 * that disagrees with the rate its buffer is actually filled at makes the time labels wrong without
 * anything failing loudly — which is how the gyroscope and accelerometer charts came to label a
 * 1.2 s window as 5 s.
 */
object ChartWindow {

    /** Seconds of history every chart holds. */
    const val SECONDS = 10

    /**
     * Waveform frame rate.
     *
     * Raised 20 -> 50 Hz on 2026-07-27. A frame is the last thing a tap passes through before it is
     * drawn, so at 20 Hz it added 25 ms on average and 50 ms at worst — the largest single term in
     * the end-to-end delay, and larger than everything the firmware contributes put together. At
     * 50 Hz that becomes 10/20 ms.
     *
     * 50 is the highest rate that keeps [IMU_POINTS_PER_TICK] and [MIC_POINTS_PER_TICK] whole
     * (100/50 = 2, 600/50 = 12). 60 would make the IMU 1.67 points per frame, and an integer
     * division there silently changes how long a full window really is.
     */
    const val TICK_HZ = 50
    const val TICK_MILLIS = 1000L / TICK_HZ

    /**
     * The spectrum analyser runs every Nth frame rather than every frame.
     *
     * An FFT per frame at 50 Hz is 2.5x the work for no visible gain — the spectrogram is read as a
     * texture, not for its latency. Splitting the two rates also keeps the spectrogram's column
     * count from having to grow with the frame rate.
     *
     * The waveforms still advance every frame, so this does not slow down what the tap is judged by.
     */
    const val ANALYSIS_EVERY = 2
    const val ANALYSIS_HZ = TICK_HZ / ANALYSIS_EVERY

    /**
     * Points per second drawn for the waveforms. Neither sensor can be drawn at its own rate: the
     * microphone runs at up to 16 kHz, and the IMU rate is whatever the user picked in settings, so
     * both are thinned to a fixed rate before reaching a chart buffer.
     */
    const val IMU_DISPLAY_HZ = 100
    const val MIC_DISPLAY_HZ = 600

    /**
     * Points each waveform buffer gains per chart frame. Filling by the frame rather than by packet
     * arrival is what keeps the charts on a single clock: a buffer advanced as samples arrive sweeps
     * at its own sensor's rate and drifts against every other chart.
     */
    const val IMU_POINTS_PER_TICK = IMU_DISPLAY_HZ / TICK_HZ
    const val MIC_POINTS_PER_TICK = MIC_DISPLAY_HZ / TICK_HZ

    /**
     * Power and temperature gain one point per analysis pass, so their capacity follows
     * [ANALYSIS_HZ] and not the frame rate. They still span [SECONDS] like every other chart, so
     * the column still reads straight across.
     */
    const val POWER_CAPACITY = ANALYSIS_HZ * SECONDS
    const val TEMPERATURE_CAPACITY = ANALYSIS_HZ * SECONDS
    const val IMU_CAPACITY = IMU_DISPLAY_HZ * SECONDS
    const val MIC_CAPACITY = MIC_DISPLAY_HZ * SECONDS

    /**
     * How much history the spectrogram shows. Its column count has to be derived from
     * [ANALYSIS_HZ] for the same reason the waveform capacities are derived from their display
     * rates: a fixed column count silently becomes a different number of seconds the moment the
     * rate feeding it changes. It was 140 columns at 20 Hz, which was 7 s.
     */
    const val SPECTROGRAM_SECONDS = 7
    const val SPECTROGRAM_COLUMNS = ANALYSIS_HZ * SPECTROGRAM_SECONDS
}

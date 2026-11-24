package com.example.naildeviceapp.model

/**
 * Sampling rates offered by the device, matching the iOS segmented control.
 * [divider] is the byte written after the `0xA0` command in the original firmware protocol.
 */
enum class SamplingRate(val label: String, val divider: Int, val kHz: Int) {
    Hz1k("1k", 0x01, 1),
    Hz2k("2k", 0x02, 2),
    Hz4k("4k", 0x04, 4),
    Hz8k("8k", 0x08, 8),
    Hz16k("16k", 0x10, 16)
}

/**
 * LSM6DSL accelerometer and gyroscope output data rates exposed by this firmware.
 *
 * [hz] is what goes on the wire and it is deliberately not a round number: the sensor's ODR field
 * selects from a fixed ladder (…, 416, 833, 1666, 3333, 6664 Hz), so 400 or 6600 Hz cannot be
 * configured. [label] rounds for display only — treat [hz] as the truth when converting a recorded
 * sample index into time, or the result is off by up to 4%.
 *
 * The top three were added once the firmware moved from per-sample polling to draining the
 * sensor's own FIFO; before that the board's RTC tick could not express them at all.
 *
 * Above ~1.6 kHz the BLE link, not the sensor, is the limit: the microphone alone costs about
 * 269 kbps at 16 kHz and each IMU sample is 12 bytes, so 3333 Hz asks for a further 320 kbps.
 * Pick a high rate and watch logcat for `link: ... imu ... lost` — if it leaves 0%, the phone
 * could not carry it and the previous step is the real ceiling.
 */
enum class ImuSamplingRate(val label: String, val hz: Int) {
    Hz400("0.4k", 400),
    Hz416("0.4k", 416),
    Hz800("0.8k", 800),
    Hz833("0.8k", 833),
    Hz1600("1.6k", 1600),
    Hz1666("1.6k", 1666),
    Hz3333("3.3k", 3333),
    Hz6664("6.6k", 6664)
}

/**
 * How much the board compresses before putting data on the air (`0xA2`).
 *
 * The link, not the sensor, is what stops mic 16 kHz from coexisting with IMU 6664 Hz: that pair
 * needs about 929 kbps and a Galaxy A15 carries roughly 600. Connection interval, transmit queue
 * depth and 2M PHY were already at their best, so the remaining lever is sending fewer bytes.
 *
 * [Both] is the default — the one combination measured at 0.00% loss on both streams with margin.
 * [Raw] is the only lossless option. The others trade fidelity for rate:
 *  - the microphone codec is 4-bit IMA ADPCM, which measured about 19 dB SNR on real recordings
 *    and preserved peak amplitude exactly — fine for detecting and timing taps, not for precise
 *    waveform work;
 *  - the IMU codec keeps the full ±16 g / ±2000 dps range but drops each axis to 12 bits. For the
 *    accelerometer that discards less than the sensor's own noise floor; the gyroscope loses a
 *    little real resolution.
 *
 * [BothAccelOnly] additionally stops sending the gyroscope altogether.
 */
enum class StreamProfile(val label: String, val code: Int, val lossless: Boolean) {
    Raw("Mic raw / IMU raw", 0, true),
    MicAdpcm("Mic 4-bit / IMU raw", 1, false),
    ImuPacked("Mic raw / IMU 12-bit", 2, false),
    Both("Mic 4-bit / IMU 12-bit", 3, false),
    BothAccelOnly("Mic 4-bit / IMU 12-bit, no gyro", 4, false),

    /**
     * Same IMU packing as [Both] but the microphone gets 8-bit u-law instead of 4-bit ADPCM:
     * about 1.4% error instead of 15% on a real tap recording, for roughly 66 kbps more.
     *
     * Costs headroom — around 660 kbps against a link that saturated between 695 and 771 kbps in
     * measurements. It works, but a different phone or a longer range may not carry it; drop back
     * to [Both] if loss appears.
     */
    BothMic8("Mic 8-bit / IMU 12-bit", 5, false)
}

/** Hardware-specific settings selected before scanning. */
enum class DeviceMode(
    val label: String,
    val hasMicrophone: Boolean,
    val imuRates: List<ImuSamplingRate>,
    val defaultImuRate: ImuSamplingRate,
    val streamProfiles: List<StreamProfile>,
    val defaultStreamProfile: StreamProfile,
    val gyroDpsPerLsb: Float
) {
    Nail(
        label = "Nail",
        hasMicrophone = true,
        imuRates = listOf(
            ImuSamplingRate.Hz416,
            ImuSamplingRate.Hz833,
            ImuSamplingRate.Hz1666,
            ImuSamplingRate.Hz3333,
            ImuSamplingRate.Hz6664
        ),
        defaultImuRate = ImuSamplingRate.Hz1666,
        streamProfiles = StreamProfile.entries,
        defaultStreamProfile = StreamProfile.Raw,
        gyroDpsPerLsb = 0.07f
    ),
    DualErm(
        label = "ERM",
        hasMicrophone = false,
        imuRates = listOf(
            ImuSamplingRate.Hz400,
            ImuSamplingRate.Hz800,
            ImuSamplingRate.Hz1600
        ),
        defaultImuRate = ImuSamplingRate.Hz1600,
        streamProfiles = listOf(StreamProfile.ImuPacked, StreamProfile.Raw),
        defaultStreamProfile = StreamProfile.ImuPacked,
        gyroDpsPerLsb = 1f / 16.4f
    )
}

enum class MemoryStatus { Inactive, Active, Saved }

enum class RecordingState { Off, Recording, Stopped }

package com.example.naildeviceapp.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.naildeviceapp.R
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.MemoryStatus
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.ui.common.LabeledSlider
import com.example.naildeviceapp.ui.common.SectionCard
import com.example.naildeviceapp.ui.common.SegmentedControl

/** Device state summary: sync, memory, elapsed recording time and effective sampling rate. */
@Composable
fun StatusPanel(
    status: DeviceStatus,
    onToggleTimeSync: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.status_panel_title),
        modifier = modifier,
        trailing = {
            // Firmware has no time-sync command yet; kept visible so the layout is unchanged.
            OutlinedButton(onClick = onToggleTimeSync, enabled = false) {
                Text(stringResource(R.string.status_time_sync))
            }
        }
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusTile(
                label = stringResource(R.string.status_sync_short),
                value = stringResource(
                    if (status.timeSyncOn) R.string.status_sync_on else R.string.status_sync_off
                ),
                highlighted = status.timeSyncOn
            )
            StatusTile(
                label = stringResource(R.string.status_memory_short),
                value = status.memoryLabel(),
                highlighted = status.memory != MemoryStatus.Inactive
            )
            StatusTile(
                label = stringResource(R.string.status_elapsed_short),
                value = stringResource(R.string.status_elapsed, status.elapsedSeconds),
                highlighted = status.recording == RecordingState.Recording
            )
            StatusTile(
                label = stringResource(R.string.status_rate_short),
                value = stringResource(
                    R.string.status_sampling_rates,
                    status.samplingRate.label,
                    status.imuSamplingRate.label
                ),
                highlighted = false
            )
        }
    }
}

@Composable
private fun DeviceStatus.memoryLabel(): String = when (memory) {
    MemoryStatus.Inactive -> stringResource(R.string.status_memory_inactive)
    MemoryStatus.Active -> stringResource(R.string.status_memory_active)
    MemoryStatus.Saved -> stringResource(R.string.status_memory_saved, memoryUsedPercent)
}

@Composable
private fun RowScope.StatusTile(label: String, value: String, highlighted: Boolean) {
    Column(
        modifier = Modifier.weight(1f),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelLarge,
            color = if (highlighted) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

/** Sampling rate, recording control and memory download. */
@Composable
fun AcquisitionPanel(
    status: DeviceStatus,
    onSamplingRateChange: (SamplingRate) -> Unit,
    onImuSamplingRateChange: (ImuSamplingRate) -> Unit,
    onRecordingChange: (RecordingState) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.control_sampling_rates), modifier = modifier) {
        Text(
            text = stringResource(R.string.control_mic_sampling_rate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedControl(
            options = SamplingRate.entries,
            selected = status.samplingRate,
            onSelect = onSamplingRateChange,
            label = { it.label },
            enabled = status.recording != RecordingState.Recording
        )

        Text(
            text = stringResource(R.string.control_imu_sampling_rate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedControl(
            options = ImuSamplingRate.entries,
            selected = status.imuSamplingRate,
            onSelect = onImuSamplingRateChange,
            label = { it.label },
            enabled = status.recording != RecordingState.Recording
        )

        Text(
            text = stringResource(R.string.control_recording),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedControl(
            options = RecordingState.entries,
            selected = status.recording,
            onSelect = onRecordingChange,
            label = { state -> stringResource(state.labelRes()) }
        )

        val progress = status.downloadProgress
        if (progress == null) {
            // The device streams rather than storing, so recordings are already on the phone.
            Button(onClick = onDownload, enabled = false, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.control_download))
            }
        } else {
            Text(
                text = stringResource(R.string.control_downloading, progress),
                style = MaterialTheme.typography.bodyMedium
            )
            LinearProgressIndicator(
                progress = { progress / 100f },
                modifier = Modifier.fillMaxWidth()
            )
        }

        if (status.savedFileName.isNotEmpty()) {
            Text(
                text = status.savedFileName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun RecordingState.labelRes(): Int = when (this) {
    RecordingState.Off -> R.string.recording_off
    RecordingState.Recording -> R.string.recording_start
    RecordingState.Stopped -> R.string.recording_stop
}

/** Microphone gain and the min/max calibration pair. */
@Composable
fun CalibrationPanel(
    gainPercent: Float,
    calibrationMax: Float,
    calibrationMin: Float,
    onGainChange: (Float) -> Unit,
    onGainCommit: () -> Unit,
    onCalibrationMaxChange: (Float) -> Unit,
    onCalibrationMinChange: (Float) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.control_calibration), modifier = modifier) {
        LabeledSlider(
            label = stringResource(R.string.control_gain),
            value = gainPercent,
            valueRange = 0f..100f,
            onValueChange = onGainChange,
            onValueChangeFinished = onGainCommit,
            valueText = "%.1f".format(gainPercent),
            enabled = false   // no gain command in this firmware
        )
        LabeledSlider(
            label = stringResource(R.string.control_calib_max),
            value = calibrationMax,
            valueRange = 0f..150f,
            onValueChange = onCalibrationMaxChange
        )
        LabeledSlider(
            label = stringResource(R.string.control_calib_min),
            value = calibrationMin,
            valueRange = 0f..150f,
            onValueChange = onCalibrationMinChange
        )
    }
}

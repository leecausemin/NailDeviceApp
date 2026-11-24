package com.example.naildeviceapp.ui.monitor

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.naildeviceapp.R
import com.example.naildeviceapp.data.RecordingLocation
import com.example.naildeviceapp.model.DeviceStatus
import com.example.naildeviceapp.model.SaveLocation
import com.example.naildeviceapp.model.MemoryStatus
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.ImuSamplingRate
import com.example.naildeviceapp.model.SamplingRate
import com.example.naildeviceapp.ui.common.SectionCard
import com.example.naildeviceapp.ui.common.SegmentedControl

/** Device state summary: memory, elapsed recording time and effective sampling rate. */
@Composable
fun StatusPanel(
    status: DeviceStatus,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.status_panel_with_mode, status.deviceMode.label),
        modifier = modifier
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
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
                value = if (status.hasMicrophone) {
                    stringResource(
                        R.string.status_sampling_rates,
                        status.samplingRate.label,
                        status.imuSamplingRate.label
                    )
                } else {
                    stringResource(R.string.status_imu_only_rate, status.imuSamplingRate.label)
                },
                highlighted = false
            )
            // The board reporting imu_ok=0 is the difference between "the sensor is still" and
            // "the sensor never initialised"; both draw a flat chart, so it has to be said here.
            StatusTile(
                label = stringResource(R.string.status_imu_short),
                value = stringResource(
                    if (status.imuOk) R.string.status_imu_ok else R.string.status_imu_failed
                ),
                highlighted = !status.imuOk
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

/** Sampling rate, recording control, save location and memory download. */
@Composable
fun AcquisitionPanel(
    status: DeviceStatus,
    saveLocation: RecordingLocation,
    onSamplingRateChange: (SamplingRate) -> Unit,
    onImuSamplingRateChange: (ImuSamplingRate) -> Unit,
    onRecordingChange: (RecordingState) -> Unit,
    onUseDefaultLocation: () -> Unit,
    onFolderPicked: (Uri) -> Unit,
    onDownload: () -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.control_sampling_rates), modifier = modifier) {
        if (status.hasMicrophone) {
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
        }

        Text(
            text = stringResource(R.string.control_imu_sampling_rate),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        SegmentedControl(
            options = status.deviceMode.imuRates,
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

        SaveLocationPicker(
            location = saveLocation,
            enabled = status.recording != RecordingState.Recording,
            onUseDefault = onUseDefaultLocation,
            onFolderPicked = onFolderPicked
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

/**
 * Default versus a folder of the user's choosing.
 *
 * Picking [SaveLocation.Custom] opens the system picker straight away the first time, so choosing
 * the mode and choosing the folder are one gesture rather than two. Once a folder is set, the mode
 * switches without re-prompting and the path below doubles as the way to change it.
 */
@Composable
private fun SaveLocationPicker(
    location: RecordingLocation,
    enabled: Boolean,
    onUseDefault: () -> Unit,
    onFolderPicked: (Uri) -> Unit
) {
    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> uri?.let(onFolderPicked) }

    Text(
        text = stringResource(R.string.control_save_location),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SegmentedControl(
        options = SaveLocation.entries,
        selected = location.mode,
        onSelect = { mode ->
            when {
                mode == SaveLocation.Default -> onUseDefault()
                location.treeUri == null -> picker.launch(null)
                else -> onFolderPicked(location.treeUri)
            }
        },
        label = { mode -> stringResource(mode.labelRes()) },
        enabled = enabled
    )

    val isCustom = location.mode == SaveLocation.Custom
    Text(
        text = when {
            !isCustom -> stringResource(R.string.control_save_default_path)
            location.folderLabel.isEmpty() -> stringResource(R.string.control_save_pick_folder)
            else -> stringResource(R.string.control_save_change_folder, location.folderLabel)
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = if (isCustom && enabled) {
            Modifier.clickable { picker.launch(null) }
        } else {
            Modifier
        }
    )
}

private fun SaveLocation.labelRes(): Int = when (this) {
    SaveLocation.Default -> R.string.save_location_default
    SaveLocation.Custom -> R.string.save_location_custom
}

private fun RecordingState.labelRes(): Int = when (this) {
    RecordingState.Off -> R.string.recording_off
    RecordingState.Recording -> R.string.recording_start
    RecordingState.Stopped -> R.string.recording_stop
}

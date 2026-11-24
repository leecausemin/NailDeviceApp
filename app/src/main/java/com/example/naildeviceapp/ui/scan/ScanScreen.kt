package com.example.naildeviceapp.ui.scan

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.naildeviceapp.R
import com.example.naildeviceapp.model.ConnectionState
import com.example.naildeviceapp.model.DeviceMode
import com.example.naildeviceapp.model.ScannedDevice
import com.example.naildeviceapp.model.StreamMarks
import com.example.naildeviceapp.ui.common.SectionCard
import com.example.naildeviceapp.ui.common.SegmentedControl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanScreen(
    onConnected: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScanViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(state.connectionState) {
        if (state.connectionState == ConnectionState.Connected) onConnected()
    }

    Scaffold(
        modifier = modifier,
        topBar = { TopAppBar(title = { Text(stringResource(R.string.scan_title)) }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            DeviceModeSelector(
                selected = state.deviceMode,
                enabled = !state.isConnecting,
                onSelect = viewModel::selectDeviceMode
            )

            ScanHeader(
                deviceCount = state.devices.size,
                isScanning = state.isScanning,
                onToggleScan = viewModel::toggleScan
            )

            StreamMarkRow(state.marks, state.deviceMode)

            if (state.isConnecting) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    text = stringResource(R.string.scan_connecting),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            DeviceList(
                devices = state.devices,
                enabled = !state.isConnecting,
                onDeviceClick = viewModel::connect,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun DeviceModeSelector(
    selected: DeviceMode,
    enabled: Boolean,
    onSelect: (DeviceMode) -> Unit,
    modifier: Modifier = Modifier
) {
    SectionCard(title = stringResource(R.string.device_mode_title), modifier = modifier) {
        SegmentedControl(
            options = DeviceMode.entries,
            selected = selected,
            onSelect = onSelect,
            label = { it.label },
            enabled = enabled
        )
        Text(
            text = stringResource(
                if (selected == DeviceMode.DualErm) {
                    R.string.device_mode_erm_note
                } else {
                    R.string.device_mode_nail_note
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun ScanHeader(
    deviceCount: Int,
    isScanning: Boolean,
    onToggleScan: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(
                text = stringResource(R.string.scan_peripherals_found, deviceCount),
                style = MaterialTheme.typography.titleMedium
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (isScanning) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 2.dp
                    )
                }
                Text(
                    text = stringResource(
                        if (isScanning) R.string.scan_scanning else R.string.scan_idle
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Button(onClick = onToggleScan) {
            Text(stringResource(if (isScanning) R.string.scan_stop else R.string.scan_action))
        }
    }
}

@Composable
private fun StreamMarkRow(
    marks: StreamMarks,
    deviceMode: DeviceMode,
    modifier: Modifier = Modifier
) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StreamMarkChip(
            active = marks.imuActive,
            labelRes = if (marks.imuActive) R.string.mark_imu_on else R.string.mark_imu_off
        )
        StreamMarkChip(
            active = marks.micActive,
            labelRes = when {
                !deviceMode.hasMicrophone -> R.string.mark_mic_na
                marks.micActive -> R.string.mark_mic_on
                else -> R.string.mark_mic_off
            }
        )
    }
}

@Composable
private fun StreamMarkChip(active: Boolean, labelRes: Int) {
    val contentColor =
        if (active) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    AssistChip(
        onClick = {},
        enabled = false,
        label = { Text(stringResource(labelRes)) },
        colors = AssistChipDefaults.assistChipColors(
            disabledLabelColor = contentColor
        )
    )
}

@Composable
private fun DeviceList(
    devices: List<ScannedDevice>,
    enabled: Boolean,
    onDeviceClick: (ScannedDevice) -> Unit,
    modifier: Modifier = Modifier
) {
    if (devices.isEmpty()) {
        Text(
            text = stringResource(R.string.scan_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(top = 24.dp)
        )
        return
    }

    LazyColumn(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        items(devices, key = { it.address }) { device ->
            DeviceRow(device = device, enabled = enabled, onClick = { onDeviceClick(device) })
        }
        item { Spacer(Modifier.size(16.dp)) }
    }
}

@Composable
private fun DeviceRow(device: ScannedDevice, enabled: Boolean, onClick: () -> Unit) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Bluetooth,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary
            )
            Column(Modifier.weight(1f)) {
                Text(
                    text = device.name,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = device.address,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = stringResource(R.string.scan_rssi, device.rssi),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

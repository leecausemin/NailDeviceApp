package com.example.naildeviceapp.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.naildeviceapp.R
import com.example.naildeviceapp.model.OptionalChart
import com.example.naildeviceapp.model.MonitoringSensor
import com.example.naildeviceapp.model.RecordingState
import com.example.naildeviceapp.model.StreamProfile
import com.example.naildeviceapp.ui.common.SectionCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel()
) {
    val selected by viewModel.selectedSensor.collectAsStateWithLifecycle()
    val visibleCharts by viewModel.visibleCharts.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = null
                        )
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            SectionCard(title = stringResource(R.string.settings_spectral_title)) {
                MonitoringSensorSelector(
                    selected = selected,
                    options = viewModel.sensorOptions(status.deviceMode),
                    onSelect = viewModel::selectSensor
                )
            }

            SectionCard(title = stringResource(R.string.settings_transmission_title)) {
                StreamProfileSelector(
                    selected = status.streamProfile,
                    options = viewModel.profileOptions(status.deviceMode),
                    hasMicrophone = status.hasMicrophone,
                    onSelect = viewModel::selectStreamProfile,
                    // Changing the profile makes the board restart both streams' sample numbering.
                    // Mid-recording that splits the CSVs into two runs of indices with a real gap
                    // between them, which is invisible until someone tries to read the file.
                    enabled = status.recording != RecordingState.Recording
                )
            }

            SectionCard(title = stringResource(R.string.settings_visualized_data)) {
                ChartVisibilitySelector(
                    visible = visibleCharts,
                    options = viewModel.chartOptions(status.deviceMode),
                    onToggle = viewModel::toggleChart
                )
            }
        }
    }
}

/**
 * Collapsed row showing the current value; tapping it reveals [content].
 *
 * Every setting on this screen is set once and then left alone, so each one costs a single line
 * until the user goes looking for it.
 */
@Composable
private fun ExpandableRow(
    label: String,
    value: String,
    expandDescription: String,
    collapseDescription: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    val chevronRotation by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        label = "chevronRotation"
    )

    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .padding(vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface
            )
            Row(
                modifier = Modifier.weight(1f),
                horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.End),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                Icon(
                    imageVector = Icons.Filled.ExpandMore,
                    contentDescription =
                        if (expanded) collapseDescription else expandDescription,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.rotate(chevronRotation)
                )
            }
        }

        AnimatedVisibility(visible = expanded) {
            Column(content = content)
        }
    }
}

@Composable
private fun MonitoringSensorSelector(
    selected: MonitoringSensor,
    options: List<MonitoringSensor>,
    onSelect: (MonitoringSensor) -> Unit,
    modifier: Modifier = Modifier
) {
    ExpandableRow(
        label = stringResource(R.string.settings_monitoring_sensor),
        value = selected.label,
        expandDescription = stringResource(R.string.settings_sensor_expand),
        collapseDescription = stringResource(R.string.settings_sensor_collapse),
        modifier = modifier
    ) {
        Column(Modifier.selectableGroup()) {
            HorizontalDivider()
            options.forEach { sensor ->
                SensorOptionRow(
                    sensor = sensor,
                    selected = sensor == selected,
                    onSelect = { onSelect(sensor) }
                )
            }
        }
    }
}

/**
 * Compression sits here rather than beside the sampling rates because it is a property of the
 * link, not of a recording: mic 16 kHz alongside IMU 6664 Hz does not fit uncompressed, so this is
 * set once and left alone. The caption spells out that the default is lossy — the alternative is a
 * silent quality drop that looks like a free bandwidth win.
 */
@Composable
private fun StreamProfileSelector(
    selected: StreamProfile,
    options: List<StreamProfile>,
    hasMicrophone: Boolean,
    onSelect: (StreamProfile) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    ExpandableRow(
        label = stringResource(R.string.settings_compression),
        value = profileLabel(selected, hasMicrophone),
        expandDescription = stringResource(R.string.settings_compression_expand),
        collapseDescription = stringResource(R.string.settings_compression_collapse),
        modifier = modifier
    ) {
        Column(Modifier.selectableGroup()) {
            HorizontalDivider()
            options.forEach { profile ->
                ProfileOptionRow(
                    profile = profile,
                    selected = profile == selected,
                    onSelect = { onSelect(profile) },
                    enabled = enabled,
                    label = profileLabel(profile, hasMicrophone)
                )
            }
            if (!enabled) {
                Text(
                    text = stringResource(R.string.settings_compression_locked),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }
            Text(
                text = stringResource(
                    if (hasMicrophone) R.string.settings_compression_note
                    else R.string.settings_compression_note_erm
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 4.dp)
            )
        }
    }
}

/** Same collapsed row as the selectors above, but the list holds independent checkboxes. */
@Composable
private fun ChartVisibilitySelector(
    visible: Set<OptionalChart>,
    options: List<OptionalChart>,
    onToggle: (OptionalChart) -> Unit,
    modifier: Modifier = Modifier
) {
    ExpandableRow(
        label = stringResource(R.string.settings_charts),
        value = stringResource(
            R.string.settings_visualized_summary,
            visible.size,
            options.size
        ),
        expandDescription = stringResource(R.string.settings_charts_expand),
        collapseDescription = stringResource(R.string.settings_charts_collapse),
        modifier = modifier
    ) {
        HorizontalDivider()
        options.forEach { kind ->
            ChartToggleRow(
                kind = kind,
                checked = kind in visible,
                onToggle = { onToggle(kind) }
            )
        }
    }
}

@Composable
private fun ProfileOptionRow(
    profile: StreamProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    enabled: Boolean = true,
    label: String = profile.label
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect, enabled = enabled)
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurface
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            }
        )
    }
}

private fun profileLabel(profile: StreamProfile, hasMicrophone: Boolean): String =
    if (hasMicrophone) profile.label else when (profile) {
        StreamProfile.Raw -> "IMU raw (16-bit)"
        StreamProfile.ImuPacked -> "IMU packed (12-bit)"
        else -> profile.label
    }

@Composable
private fun ChartToggleRow(
    kind: OptionalChart,
    checked: Boolean,
    onToggle: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(checked = checked, onCheckedChange = { onToggle() })
        Text(
            text = stringResource(kind.titleRes),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun SensorOptionRow(
    sensor: MonitoringSensor,
    selected: Boolean,
    onSelect: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Text(
            text = sensor.label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

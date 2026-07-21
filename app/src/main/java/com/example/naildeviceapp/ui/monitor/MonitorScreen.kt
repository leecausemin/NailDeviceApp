package com.example.naildeviceapp.ui.monitor

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.naildeviceapp.R
import com.example.naildeviceapp.model.OptionalChart

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MonitorScreen(
    onDisconnect: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: MonitorViewModel = viewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier,
        topBar = {
            TopAppBar(
                title = {
                    Text(state.status.deviceName.ifEmpty { stringResource(R.string.monitor_title) })
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            viewModel.disconnect()
                            onDisconnect()
                        }
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.monitor_disconnect)
                        )
                    }
                },
                actions = {
                    IconButton(onClick = onOpenSettings) {
                        Icon(
                            imageVector = Icons.Filled.Settings,
                            contentDescription = stringResource(R.string.monitor_settings)
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
            StatusPanel(
                status = state.status,
                onToggleTimeSync = viewModel::toggleTimeSync
            )

            // The spectral views are always shown; everything below them is user-selectable.
            SpectrogramCard(state.sensors.spectrogram, state.monitoringSensor.label)
            PowerChartCard(state.sensors.power, state.monitoringSensor.label)

            val visible = state.visibleCharts
            if (OptionalChart.Gyroscope in visible) {
                GyroChartCard(state.sensors.gyro)
            }
            if (OptionalChart.Imu in visible) {
                AccelChartCard(state.sensors.accel)
            }
            if (OptionalChart.Microphone in visible) {
                MicChartCard(state.sensors.mic)
            }
            if (OptionalChart.Temperature in visible) {
                TemperatureChartCard(state.sensors.temperature)
            }

            AcquisitionPanel(
                status = state.status,
                onSamplingRateChange = viewModel::setSamplingRate,
                onRecordingChange = viewModel::setRecording,
                onDownload = viewModel::downloadMemory
            )

            CalibrationPanel(
                gainPercent = state.controls.gainPercent,
                calibrationMax = state.controls.calibrationMax,
                calibrationMin = state.controls.calibrationMin,
                onGainChange = viewModel::setGain,
                onGainCommit = viewModel::commitGain,
                onCalibrationMaxChange = viewModel::setCalibrationMax,
                onCalibrationMinChange = viewModel::setCalibrationMin
            )
        }
    }
}

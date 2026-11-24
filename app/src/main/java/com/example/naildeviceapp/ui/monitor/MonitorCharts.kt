package com.example.naildeviceapp.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.naildeviceapp.R
import com.example.naildeviceapp.data.XyzWindow
import com.example.naildeviceapp.model.ChartWindow
import com.example.naildeviceapp.ui.chart.ChartAxis
import com.example.naildeviceapp.ui.chart.ChartSeries
import com.example.naildeviceapp.ui.chart.RealtimeLineChart
import com.example.naildeviceapp.ui.chart.SpectrogramData
import com.example.naildeviceapp.ui.chart.SpectrogramView
import com.example.naildeviceapp.ui.chart.YScale
import com.example.naildeviceapp.ui.common.ChartLegend
import com.example.naildeviceapp.ui.common.SectionCard
import com.example.naildeviceapp.ui.theme.rememberChartPalette

private val CHART_HEIGHT = 132.dp
private val SPECTROGRAM_HEIGHT = 120.dp

private fun secondsAxis(capacity: Int, sampleRateHz: Int) =
    ChartAxis(capacity) { sample -> "%.1f".format(sample / sampleRateHz.toFloat()) }

@Composable
fun SpectrogramCard(
    data: SpectrogramData,
    sensorLabel: String,
    modifier: Modifier = Modifier
) {
    SectionCard(
        title = stringResource(R.string.chart_spectrogram),
        modifier = modifier,
        trailing = { SourceLabel(sensorLabel) }
    ) {
        SpectrogramView(
            data = data,
            modifier = Modifier
                .fillMaxWidth()
                .height(SPECTROGRAM_HEIGHT)
                .clip(RoundedCornerShape(6.dp))
                .background(Color.Black)
        )
    }
}

@Composable
fun PowerChartCard(
    power: FloatArray,
    sensorLabel: String,
    modifier: Modifier = Modifier
) {
    val palette = rememberChartPalette()
    val series = listOf(
        ChartSeries(stringResource(R.string.series_intensity), palette.intensity, power)
    )
    ChartCard(
        title = stringResource(R.string.chart_power),
        series = series,
        // ANALYSIS_HZ, not TICK_HZ: the power trace gains a point per analysis pass, and the axis
        // has to be told the rate the buffer is actually filled at or the labels quietly lie.
        axis = secondsAxis(ChartWindow.POWER_CAPACITY, ChartWindow.ANALYSIS_HZ),
        // Not a physical quantity, unlike every other chart here. [SpectrumAnalyzer] divides each
        // spectrum by its own peak, so this is 0..1 relative to the loudest bin in that same
        // frame — two frames' values cannot be compared as absolute levels.
        yScale = YScale.Auto(fallback = YScale.Fixed(0f, 1f)),
        sensorLabel = sensorLabel,
        modifier = modifier
    )
}

@Composable
fun GyroChartCard(window: XyzWindow, modifier: Modifier = Modifier) {
    val palette = rememberChartPalette()
    val series = listOf(
        ChartSeries(stringResource(R.string.series_gyro_x), palette.axisX, window.x),
        ChartSeries(stringResource(R.string.series_gyro_y), palette.axisY, window.y),
        ChartSeries(stringResource(R.string.series_gyro_z), palette.axisZ, window.z)
    )
    ChartCard(
        title = stringResource(R.string.chart_gyro),
        series = series,
        axis = secondsAxis(ChartWindow.IMU_CAPACITY, ChartWindow.IMU_DISPLAY_HZ),
        // Real units: the decode already applied GYRO_DPS_PER_LSB, so these are degrees per second
        // and the fallback is the sensor's configured +-2000 dps range.
        yScale = YScale.Auto(fallback = YScale.Fixed(-2000f, 2000f)),
        modifier = modifier
    )
}

@Composable
fun AccelChartCard(window: XyzWindow, modifier: Modifier = Modifier) {
    val palette = rememberChartPalette()
    val series = listOf(
        ChartSeries(stringResource(R.string.series_acc_x), palette.axisX, window.x),
        ChartSeries(stringResource(R.string.series_acc_y), palette.axisY, window.y),
        ChartSeries(stringResource(R.string.series_acc_z), palette.axisZ, window.z)
    )
    ChartCard(
        title = stringResource(R.string.chart_imu),
        series = series,
        axis = secondsAxis(ChartWindow.IMU_CAPACITY, ChartWindow.IMU_DISPLAY_HZ),
        // ACCEL_G_PER_LSB is already applied at decode, so these are g. The fallback is the
        // configured +-16 g range rather than +-1, which only ever looked right at rest.
        yScale = YScale.Auto(fallback = YScale.Fixed(-16f, 16f)),
        modifier = modifier
    )
}

@Composable
fun MicChartCard(mic: FloatArray, modifier: Modifier = Modifier) {
    val palette = rememberChartPalette()
    val series = listOf(ChartSeries(stringResource(R.string.series_mic), palette.mic, mic))
    ChartCard(
        title = stringResource(R.string.chart_mic),
        series = series,
        axis = secondsAxis(ChartWindow.MIC_CAPACITY, ChartWindow.MIC_DISPLAY_HZ),
        // Raw int16 PCM — the same number the recording's `pcm` column holds, so a peak read off
        // this chart can be looked up in the file. The fallback is the full scale the ADC can
        // reach, which also makes clipping obvious: a tap that flattens against +-32768 is
        // clipping, and that is not visible on a normalised axis.
        yScale = YScale.Auto(fallback = YScale.Fixed(-32768f, 32767f)),
        modifier = modifier
    )
}

@Composable
fun TemperatureChartCard(temperature: FloatArray, modifier: Modifier = Modifier) {
    val palette = rememberChartPalette()
    val series = listOf(
        ChartSeries(stringResource(R.string.series_temperature), palette.axisZ, temperature)
    )
    ChartCard(
        title = stringResource(R.string.chart_temperature),
        series = series,
        axis = secondsAxis(ChartWindow.TEMPERATURE_CAPACITY, ChartWindow.ANALYSIS_HZ),
        yScale = YScale.Auto(fallback = YScale.Fixed(20f, 40f)),
        modifier = modifier
    )
}

@Composable
private fun SourceLabel(sensorLabel: String) {
    Text(
        text = sensorLabel,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ChartCard(
    title: String,
    series: List<ChartSeries>,
    axis: ChartAxis,
    yScale: YScale,
    modifier: Modifier = Modifier,
    sensorLabel: String? = null
) {
    val palette = rememberChartPalette()
    SectionCard(
        title = title,
        modifier = modifier,
        trailing = {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                sensorLabel?.let { SourceLabel(it) }
                ChartLegend(series)
            }
        }
    ) {
        RealtimeLineChart(
            series = series,
            axis = axis,
            yScale = yScale,
            gridColor = palette.grid,
            axisColor = palette.axisLine,
            labelColor = palette.label,
            modifier = Modifier
                .fillMaxWidth()
                .height(CHART_HEIGHT)
        )
    }
}

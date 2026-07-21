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

/** Sample counts per chart window, and the rate each window is filled at. */
private const val POWER_CAPACITY = 200
private const val POWER_HZ = 20f
private const val IMU_CAPACITY = 500
private const val IMU_HZ = 100f
private const val MIC_CAPACITY = 600
private const val MIC_HZ = 600f
private const val TEMPERATURE_CAPACITY = 200
private const val TEMPERATURE_HZ = 20f

private fun secondsAxis(capacity: Int, sampleRateHz: Float) =
    ChartAxis(capacity) { sample -> "%.1f".format(sample / sampleRateHz) }

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
        axis = secondsAxis(POWER_CAPACITY, POWER_HZ),
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
        axis = secondsAxis(IMU_CAPACITY, IMU_HZ),
        yScale = YScale.Auto(fallback = YScale.Fixed(-500f, 500f)),
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
        axis = secondsAxis(IMU_CAPACITY, IMU_HZ),
        yScale = YScale.Auto(fallback = YScale.Fixed(-1f, 1f)),
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
        axis = secondsAxis(MIC_CAPACITY, MIC_HZ),
        yScale = YScale.Auto(fallback = YScale.Fixed(900f, 1800f)),
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
        axis = secondsAxis(TEMPERATURE_CAPACITY, TEMPERATURE_HZ),
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

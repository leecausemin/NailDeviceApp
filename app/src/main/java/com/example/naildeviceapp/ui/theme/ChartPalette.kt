package com.example.naildeviceapp.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * Trace colours for the sensor charts. The iOS app draws X black, Y blue and Z red; the dark variant
 * keeps the same hue ordering but lifts the luminance so the lines stay readable on a dark card.
 */
@Immutable
data class ChartPalette(
    val axisX: Color,
    val axisY: Color,
    val axisZ: Color,
    val intensity: Color,
    val mic: Color,
    val grid: Color,
    val axisLine: Color,
    val label: Color
)

private val LightChartPalette = ChartPalette(
    axisX = Color(0xFF202124),
    axisY = Color(0xFF1565C0),
    axisZ = Color(0xFFD32F2F),
    intensity = Color(0xFFD32F2F),
    mic = Color(0xFF202124),
    grid = Color(0xFFDCDCDC),
    axisLine = Color(0xFF9A9A9A),
    label = Color(0xFF6B6B6B)
)

private val DarkChartPalette = ChartPalette(
    axisX = Color(0xFFE3E3E3),
    axisY = Color(0xFF64B5F6),
    axisZ = Color(0xFFFF7043),
    intensity = Color(0xFFFF7043),
    mic = Color(0xFFE3E3E3),
    grid = Color(0xFF3A3A3A),
    axisLine = Color(0xFF6E6E6E),
    label = Color(0xFF9E9E9E)
)

@Composable
fun rememberChartPalette(): ChartPalette =
    if (isSystemInDarkTheme()) DarkChartPalette else LightChartPalette

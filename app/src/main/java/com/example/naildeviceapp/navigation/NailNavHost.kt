package com.example.naildeviceapp.navigation

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.naildeviceapp.ui.monitor.MonitorScreen
import com.example.naildeviceapp.ui.scan.ScanScreen
import com.example.naildeviceapp.ui.settings.SettingsScreen

object Routes {
    const val SCAN = "scan"
    const val MONITOR = "monitor"
    const val SETTINGS = "settings"
}

@Composable
fun NailNavHost(
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController()
) {
    NavHost(
        navController = navController,
        startDestination = Routes.SCAN,
        modifier = modifier
    ) {
        composable(Routes.SCAN) {
            ScanScreen(
                onConnected = {
                    navController.navigate(Routes.MONITOR) {
                        popUpTo(Routes.SCAN) { inclusive = false }
                    }
                }
            )
        }

        composable(Routes.MONITOR) {
            MonitorScreen(
                onDisconnect = { navController.popBackStack(Routes.SCAN, inclusive = false) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) }
            )
        }

        composable(Routes.SETTINGS) {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

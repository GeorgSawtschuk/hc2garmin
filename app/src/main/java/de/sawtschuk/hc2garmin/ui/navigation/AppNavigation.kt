package de.sawtschuk.hc2garmin.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import de.sawtschuk.hc2garmin.ui.main.MainScreen
import de.sawtschuk.hc2garmin.ui.settings.SettingsScreen

@Composable
fun AppNavigation() {
    val navController = rememberNavController()
    NavHost(navController, startDestination = "main") {
        composable("main") {
            MainScreen(onNavigateToSettings = { navController.navigate("settings") })
        }
        composable("settings") {
            SettingsScreen(onBack = { navController.popBackStack() })
        }
    }
}

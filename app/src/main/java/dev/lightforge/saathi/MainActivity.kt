package dev.lightforge.saathi

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import dev.lightforge.saathi.ui.calllog.CallLogScreen
import dev.lightforge.saathi.ui.home.HomeScreen
import dev.lightforge.saathi.ui.settings.SettingsScreen
import dev.lightforge.saathi.ui.setup.SetupScreen
import dev.lightforge.saathi.ui.theme.SaathiTheme

/**
 * Single-activity host for all Compose screens.
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            SaathiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = NavRoute.HOME
                    ) {
                        composable(NavRoute.SETUP) {
                            SetupScreen(
                                onSetupComplete = {
                                    navController.navigate(NavRoute.HOME) {
                                        popUpTo(NavRoute.SETUP) { inclusive = true }
                                    }
                                }
                            )
                        }
                        composable(NavRoute.HOME) {
                            HomeScreen(
                                onNavigateToCallLog = {
                                    navController.navigate(NavRoute.CALL_LOG)
                                },
                                onNavigateToSettings = {
                                    navController.navigate(NavRoute.SETTINGS)
                                }
                            )
                        }
                        composable(NavRoute.CALL_LOG) {
                            CallLogScreen(onBack = { navController.popBackStack() })
                        }
                        composable(NavRoute.SETTINGS) {
                            SettingsScreen(onBack = { navController.popBackStack() })
                        }
                    }
                }
            }
        }
    }
}

/**
 * Navigation route constants.
 */
object NavRoute {
    const val SETUP = "setup"
    const val HOME = "home"
    const val CALL_LOG = "call_log"
    const val SETTINGS = "settings"
}

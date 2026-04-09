package dev.lightforge.saathi

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import dagger.hilt.android.AndroidEntryPoint
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.ui.calllog.CallLogScreen
import dev.lightforge.saathi.ui.home.HomeScreen
import dev.lightforge.saathi.ui.settings.SettingsScreen
import dev.lightforge.saathi.ui.setup.BatteryOptimizationScreen
import dev.lightforge.saathi.ui.setup.OTPScreen
import dev.lightforge.saathi.ui.setup.PermissionsScreen
import dev.lightforge.saathi.ui.setup.SetupScreen
import dev.lightforge.saathi.ui.theme.SaathiTheme
import javax.inject.Inject

/**
 * Single-activity host for all Compose screens.
 *
 * On startup: checks if device_token exists in EncryptedSharedPreferences.
 * - Token present → start at HomeScreen
 * - No token → start at SetupScreen (onboarding flow)
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var tokenManager: TokenManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Force dark status/nav bars to match our pure-dark theme
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT)
        )

        val startDestination = if (tokenManager.hasToken()) NavRoute.HOME else NavRoute.SETUP

        setContent {
            SaathiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val navController = rememberNavController()

                    NavHost(
                        navController = navController,
                        startDestination = startDestination
                    ) {
                        // --- Onboarding flow ---
                        composable(NavRoute.SETUP) {
                            SetupScreen(
                                onOtpSent = { pairingId ->
                                    navController.navigate(NavRoute.otp(pairingId))
                                }
                            )
                        }

                        composable(
                            route = NavRoute.OTP,
                            arguments = listOf(navArgument("pairingId") { type = NavType.StringType })
                        ) { backStackEntry ->
                            val pairingId = backStackEntry.arguments?.getString("pairingId") ?: ""
                            OTPScreen(
                                pairingId = pairingId,
                                onVerified = {
                                    navController.navigate(NavRoute.PERMISSIONS) {
                                        popUpTo(NavRoute.SETUP) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(NavRoute.PERMISSIONS) {
                            PermissionsScreen(
                                onPermissionsGranted = {
                                    navController.navigate(NavRoute.BATTERY) {
                                        popUpTo(NavRoute.PERMISSIONS) { inclusive = true }
                                    }
                                }
                            )
                        }

                        composable(NavRoute.BATTERY) {
                            BatteryOptimizationScreen(
                                onContinue = {
                                    navController.navigate(NavRoute.HOME) {
                                        popUpTo(NavRoute.BATTERY) { inclusive = true }
                                    }
                                }
                            )
                        }

                        // --- Main app ---
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
    const val OTP = "otp/{pairingId}"
    const val PERMISSIONS = "permissions"
    const val BATTERY = "battery"
    const val HOME = "home"
    const val CALL_LOG = "call_log"
    const val SETTINGS = "settings"

    fun otp(pairingId: String) = "otp/$pairingId"
}

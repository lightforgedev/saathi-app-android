package dev.lightforge.saathi.ui.home

import android.app.role.RoleManager
import android.content.Intent
import android.telecom.TelecomManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lightforge.saathi.R
import dev.lightforge.saathi.network.CallRecord
import dev.lightforge.saathi.ui.theme.SaathiBorder
import dev.lightforge.saathi.ui.theme.SaathiGreen
import dev.lightforge.saathi.ui.theme.SaathiSurface
import dev.lightforge.saathi.ui.theme.SaathiSurface2
import dev.lightforge.saathi.ui.theme.SaathiTextSecondary

/**
 * Home screen — main status and stats view.
 */
@Composable
fun HomeScreen(
    onNavigateToCallLog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current

    val roleResultLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { viewModel.checkDefaultDialer() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            SaathiBottomNav(
                selected = 0,
                onHome = {},
                onCallLog = onNavigateToCallLog,
                onSettings = onNavigateToSettings
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(24.dp))

            // Top brand + restaurant name
            BrandHeader(restaurantName = uiState.restaurantName)

            // Default dialer warning — shown after every APK install until re-granted
            if (!uiState.isDefaultDialer) {
                Spacer(modifier = Modifier.height(16.dp))
                DefaultDialerBanner {
                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                        val rm = context.getSystemService(RoleManager::class.java)
                        roleResultLauncher.launch(rm.createRequestRoleIntent(RoleManager.ROLE_DIALER))
                    } else {
                        val intent = Intent(TelecomManager.ACTION_CHANGE_DEFAULT_DIALER)
                            .putExtra(TelecomManager.EXTRA_CHANGE_DEFAULT_DIALER_PACKAGE_NAME, context.packageName)
                        roleResultLauncher.launch(intent)
                    }
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Status circle
            StatusCircle(
                isActive = uiState.isActive,
                onClick = { viewModel.toggleActive(!uiState.isActive) }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (uiState.isActive) "Tap to pause" else "Tap to activate",
                style = MaterialTheme.typography.bodySmall,
                color = SaathiTextSecondary
            )

            Spacer(modifier = Modifier.height(32.dp))

            // Stats row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                StatCard(
                    label = stringResource(R.string.home_stat_calls),
                    value = uiState.callsHandled.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.home_stat_actions),
                    value = uiState.reservationsMade.toString(),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(R.string.home_stat_duration),
                    value = viewModel.formatDuration(uiState.lastCallDurationSeconds),
                    modifier = Modifier.weight(1f)
                )
            }

            // Recent callers
            if (uiState.recentCalls.isNotEmpty()) {
                Spacer(modifier = Modifier.height(28.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.home_recent_callers),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = SaathiTextSecondary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                uiState.recentCalls.forEach { call ->
                    RecentCallRow(call = call)
                    Spacer(modifier = Modifier.height(6.dp))
                }
            }

            // Footer
            if (uiState.isActive) {
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = stringResource(R.string.home_footer_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = SaathiGreen.copy(alpha = 0.7f),
                    textAlign = TextAlign.Center
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun DefaultDialerBanner(onFix: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFFB300).copy(alpha = 0.15f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.WarningAmber,
                contentDescription = null,
                tint = Color(0xFFFFB300),
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Not set as default phone app",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = "Calls won't be intercepted",
                    style = MaterialTheme.typography.labelSmall,
                    color = SaathiTextSecondary
                )
            }
            Button(
                onClick = onFix,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
            ) {
                Text("Fix", color = Color.Black, fontSize = 12.sp)
            }
        }
    }
}

@Composable
fun BrandHeader(restaurantName: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = "saathi.help",
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            color = SaathiGreen
        )
        if (restaurantName.isNotEmpty()) {
            Text(
                text = restaurantName,
                style = MaterialTheme.typography.bodySmall,
                color = SaathiTextSecondary
            )
        }
    }
}

@Composable
private fun StatusCircle(isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) SaathiGreen else Color(0xFF444444)

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.06f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.15f,
        targetValue = if (isActive) 0.35f else 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glow"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(200.dp)
            .clickable { onClick() }
    ) {
        // Glow blur layer
        if (isActive) {
            Box(
                modifier = Modifier
                    .size(180.dp)
                    .blur(32.dp)
                    .background(color.copy(alpha = glowAlpha), CircleShape)
            )
        }

        // Outer ring
        Box(
            modifier = Modifier
                .size(180.dp)
                .scale(scale)
                .background(color.copy(alpha = 0.08f), CircleShape)
        )

        // Middle ring
        Box(
            modifier = Modifier
                .size(148.dp)
                .background(color.copy(alpha = 0.12f), CircleShape)
        )

        // Inner solid circle
        Box(
            modifier = Modifier
                .size(110.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = if (isActive) "ACTIVE" else "INACTIVE",
                    color = if (isActive) Color.Black else Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = if (isActive) "Tap to answer" else "",
                    color = if (isActive) Color.Black.copy(alpha = 0.6f) else Color.Transparent,
                    fontSize = 9.sp
                )
            }
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .background(SaathiSurface, RoundedCornerShape(12.dp))
            .padding(vertical = 16.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = SaathiTextSecondary,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RecentCallRow(call: CallRecord) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaathiSurface2, RoundedCornerShape(10.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(SaathiGreen.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (call.direction == "incoming") Icons.AutoMirrored.Filled.CallReceived
                              else Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                tint = SaathiGreen,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = call.caller_name ?: formatNumber(call.caller_number),
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = formatDurationShort(call.duration_seconds),
                style = MaterialTheme.typography.bodySmall,
                color = SaathiTextSecondary
            )
        }
        Icon(
            imageVector = when (call.outcome) {
                "resolved" -> Icons.Default.CheckCircle
                "escalated" -> Icons.AutoMirrored.Filled.CallMade
                else -> Icons.Default.WarningAmber
            },
            contentDescription = call.outcome,
            tint = when (call.outcome) {
                "resolved" -> SaathiGreen
                else -> SaathiTextSecondary
            },
            modifier = Modifier.size(18.dp)
        )
    }
}

@Composable
fun SaathiBottomNav(
    selected: Int,
    onHome: () -> Unit,
    onCallLog: () -> Unit,
    onSettings: () -> Unit
) {
    Column {
        HorizontalDivider(color = SaathiBorder, thickness = 0.5.dp)
        NavigationBar(
            containerColor = SaathiSurface,
            tonalElevation = 0.dp
        ) {
            NavigationBarItem(
                selected = selected == 0,
                onClick = onHome,
                icon = { Icon(Icons.Default.Home, contentDescription = null) },
                label = { Text(stringResource(R.string.home_tab_home)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SaathiGreen,
                    selectedTextColor = SaathiGreen,
                    unselectedIconColor = SaathiTextSecondary,
                    unselectedTextColor = SaathiTextSecondary,
                    indicatorColor = SaathiGreen.copy(alpha = 0.12f)
                )
            )
            NavigationBarItem(
                selected = selected == 1,
                onClick = onCallLog,
                icon = { Icon(Icons.Default.History, contentDescription = null) },
                label = { Text(stringResource(R.string.home_tab_calllog)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SaathiGreen,
                    selectedTextColor = SaathiGreen,
                    unselectedIconColor = SaathiTextSecondary,
                    unselectedTextColor = SaathiTextSecondary,
                    indicatorColor = SaathiGreen.copy(alpha = 0.12f)
                )
            )
            NavigationBarItem(
                selected = selected == 2,
                onClick = onSettings,
                icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                label = { Text(stringResource(R.string.home_tab_settings)) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = SaathiGreen,
                    selectedTextColor = SaathiGreen,
                    unselectedIconColor = SaathiTextSecondary,
                    unselectedTextColor = SaathiTextSecondary,
                    indicatorColor = SaathiGreen.copy(alpha = 0.12f)
                )
            )
        }
    }
}

private fun formatNumber(phone: String): String {
    return if (phone.startsWith("+91") && phone.length == 13) {
        "+91 ${phone.substring(3, 8)} ${phone.substring(8)}"
    } else phone
}

private fun formatDurationShort(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

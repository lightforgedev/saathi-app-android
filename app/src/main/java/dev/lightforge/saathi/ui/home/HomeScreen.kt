package dev.lightforge.saathi.ui.home

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

private val ActiveGreen = Color(0xFF4CAF50)
private val InactiveGrey = Color(0xFF9E9E9E)

/**
 * Home screen — main status and stats view.
 *
 * - Status circle: pulsing green ACTIVE or grey INACTIVE
 * - Tap circle to toggle call mode (full_auto / off)
 * - Stats row: calls today, reservations, last call duration
 * - Recent callers list (last 4 calls)
 * - Bottom nav bar: Home | Call Log | Settings
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToCallLog: () -> Unit,
    onNavigateToSettings: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "saathi.help",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                        if (uiState.restaurantName.isNotEmpty()) {
                            Text(
                                text = uiState.restaurantName,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = stringResource(R.string.home_tab_settings))
                    }
                }
            )
        },
        bottomBar = {
            BottomAppBar {
                NavigationBarItem(
                    selected = true,
                    onClick = {},
                    icon = { Icon(Icons.Default.CheckCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_tab_home)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToCallLog,
                    icon = { Icon(Icons.Default.History, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_tab_calllog)) }
                )
                NavigationBarItem(
                    selected = false,
                    onClick = onNavigateToSettings,
                    icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                    label = { Text(stringResource(R.string.home_tab_settings)) }
                )
            }
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Status circle — tap to toggle
            StatusCircle(
                isActive = uiState.isActive,
                onClick = { viewModel.toggleActive(!uiState.isActive) }
            )

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
                Column(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = stringResource(R.string.home_recent_callers),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    uiState.recentCalls.forEach { call ->
                        RecentCallRow(call = call)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }

            // Footer when active
            if (uiState.isActive) {
                Text(
                    text = stringResource(R.string.home_footer_active),
                    style = MaterialTheme.typography.bodySmall,
                    color = ActiveGreen,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

@Composable
private fun StatusCircle(isActive: Boolean, onClick: () -> Unit) {
    val color = if (isActive) ActiveGreen else InactiveGrey

    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isActive) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(160.dp)
            .scale(scale)
            .clickable { onClick() }
    ) {
        // Outer ring
        Surface(
            modifier = Modifier.size(160.dp),
            shape = CircleShape,
            color = color.copy(alpha = 0.15f),
            border = BorderStroke(3.dp, color.copy(alpha = 0.4f))
        ) {}

        // Middle ring
        Surface(
            modifier = Modifier.size(120.dp),
            shape = CircleShape,
            color = color.copy(alpha = 0.25f)
        ) {}

        // Inner filled circle
        Box(
            modifier = Modifier
                .size(80.dp)
                .background(color, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = if (isActive) "ACTIVE" else "INACTIVE",
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .padding(16.dp)
                .fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun RecentCallRow(call: CallRecord) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = if (call.direction == "incoming") Icons.AutoMirrored.Filled.CallReceived else Icons.AutoMirrored.Filled.CallMade,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.caller_name ?: formatNumber(call.caller_number),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = formatDurationShort(call.duration_seconds),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val outcomeIcon = when (call.outcome) {
                "resolved" -> Icons.Default.CheckCircle
                "escalated" -> Icons.AutoMirrored.Filled.CallMade
                else -> Icons.Default.WarningAmber
            }
            Icon(
                imageVector = outcomeIcon,
                contentDescription = call.outcome,
                tint = when (call.outcome) {
                    "resolved" -> ActiveGreen
                    "escalated" -> MaterialTheme.colorScheme.primary
                    else -> MaterialTheme.colorScheme.outline
                },
                modifier = Modifier.size(20.dp)
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

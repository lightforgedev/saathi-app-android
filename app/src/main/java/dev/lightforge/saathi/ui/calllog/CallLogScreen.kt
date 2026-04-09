package dev.lightforge.saathi.ui.calllog

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.CallMade
import androidx.compose.material.icons.automirrored.filled.CallMissed
import androidx.compose.material.icons.automirrored.filled.CallReceived
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lightforge.saathi.R
import dev.lightforge.saathi.network.CallRecord
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter

private val ActiveGreen = Color(0xFF4CAF50)

/**
 * Paginated call history screen, grouped by date (today / yesterday / earlier).
 * Infinite scroll with cursor pagination.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CallLogScreen(
    onBack: () -> Unit,
    viewModel: CallLogViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()

    // Trigger load-more when scrolled near end
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val total = listState.layoutInfo.totalItemsCount
            lastVisible >= total - 3 && uiState.hasMore && !uiState.isLoadingMore
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) viewModel.loadMore()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.calllog_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        when {
            uiState.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator()
                }
            }

            uiState.groupedCalls.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.calllog_empty),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            else -> {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(paddingValues)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    uiState.groupedCalls.forEach { (group, calls) ->
                        item {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = when (group) {
                                    "TODAY" -> stringResource(R.string.calllog_today)
                                    "YESTERDAY" -> stringResource(R.string.calllog_yesterday)
                                    else -> stringResource(R.string.calllog_earlier)
                                },
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.padding(vertical = 4.dp)
                            )
                        }
                        items(calls, key = { it.id }) { call ->
                            CallRow(call = call)
                        }
                    }

                    if (uiState.isLoadingMore) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(16.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                CircularProgressIndicator(modifier = Modifier.size(24.dp))
                            }
                        }
                    }

                    if (uiState.hasMore && !uiState.isLoadingMore) {
                        item {
                            Button(
                                onClick = { viewModel.loadMore() },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(stringResource(R.string.calllog_load_more))
                            }
                        }
                    }

                    item { Spacer(modifier = Modifier.height(16.dp)) }
                }
            }
        }
    }
}

@Composable
private fun CallRow(call: CallRecord) {
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
            // Direction icon
            Icon(
                imageVector = when {
                    call.outcome == "missed" -> Icons.AutoMirrored.Filled.CallMissed
                    call.direction == "incoming" -> Icons.AutoMirrored.Filled.CallReceived
                    else -> Icons.AutoMirrored.Filled.CallMade
                },
                contentDescription = call.direction,
                tint = if (call.outcome == "missed") MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(24.dp)
            )

            // Caller info
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = call.caller_name ?: formatNumber(call.caller_number),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = formatTime(call.started_at),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "•",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDuration(call.duration_seconds),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Outcome icon
            Icon(
                imageVector = when (call.outcome) {
                    "resolved" -> Icons.Default.CheckCircle
                    "escalated" -> Icons.AutoMirrored.Filled.CallMade
                    "missed" -> Icons.AutoMirrored.Filled.CallMissed
                    else -> Icons.Default.WarningAmber
                },
                contentDescription = call.outcome,
                tint = when (call.outcome) {
                    "resolved" -> ActiveGreen
                    "escalated" -> MaterialTheme.colorScheme.primary
                    "missed" -> MaterialTheme.colorScheme.error
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

private fun formatTime(iso: String): String {
    return try {
        val dt = OffsetDateTime.parse(iso)
        DateTimeFormatter.ofPattern("h:mm a").format(dt)
    } catch (_: Exception) {
        iso
    }
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return if (m > 0) "${m}m ${s}s" else "${s}s"
}

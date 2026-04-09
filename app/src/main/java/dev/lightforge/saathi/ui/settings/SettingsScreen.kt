package dev.lightforge.saathi.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lightforge.saathi.R

/**
 * Settings screen: call mode, business hours, language, notifications.
 *
 * All changes are batched and saved via PATCH /settings on tap of Save button.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val dayLabels = listOf(
        "monday" to stringResource(R.string.settings_monday),
        "tuesday" to stringResource(R.string.settings_tuesday),
        "wednesday" to stringResource(R.string.settings_wednesday),
        "thursday" to stringResource(R.string.settings_thursday),
        "friday" to stringResource(R.string.settings_friday),
        "saturday" to stringResource(R.string.settings_saturday),
        "sunday" to stringResource(R.string.settings_sunday)
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Restaurant info
            if (uiState.restaurantName.isNotEmpty()) {
                Column {
                    SectionHeader(stringResource(R.string.settings_restaurant))
                    Text(
                        text = uiState.restaurantName,
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
                HorizontalDivider()
            }

            // Call Handling Mode
            Column {
                SectionHeader(stringResource(R.string.settings_call_mode))
                Spacer(modifier = Modifier.height(8.dp))

                val modes = listOf("full_auto", "busy_no_answer", "off")
                val modeLabels = listOf(
                    stringResource(R.string.settings_call_mode_full_auto),
                    stringResource(R.string.settings_call_mode_busy),
                    stringResource(R.string.settings_call_mode_off)
                )
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    modes.forEachIndexed { index, mode ->
                        SegmentedButton(
                            selected = uiState.callMode == mode,
                            onClick = { viewModel.setCallMode(mode) },
                            shape = SegmentedButtonDefaults.itemShape(index, modes.size)
                        ) {
                            Text(modeLabels[index], maxLines = 1)
                        }
                    }
                }
            }

            HorizontalDivider()

            // Greeting Language
            Column {
                SectionHeader(stringResource(R.string.settings_language))
                Spacer(modifier = Modifier.height(8.dp))

                val langOptions = listOf(
                    "auto" to stringResource(R.string.settings_language_auto),
                    "hi-IN" to stringResource(R.string.settings_language_hindi),
                    "en-IN" to stringResource(R.string.settings_language_english)
                )
                LanguageDropdown(
                    selected = uiState.primaryLanguage,
                    options = langOptions,
                    onSelected = { viewModel.setLanguage(it) }
                )
            }

            HorizontalDivider()

            // Notifications
            Column {
                SectionHeader(stringResource(R.string.settings_notifications))
                Spacer(modifier = Modifier.height(8.dp))

                ToggleRow(
                    label = stringResource(R.string.settings_whatsapp_summary),
                    checked = uiState.whatsappDailySummary,
                    onCheckedChange = { viewModel.setWhatsappSummary(it) }
                )
                Spacer(modifier = Modifier.height(8.dp))
                ToggleRow(
                    label = stringResource(R.string.settings_per_call),
                    checked = uiState.perCallNotification,
                    onCheckedChange = { viewModel.setPerCallNotification(it) }
                )
            }

            HorizontalDivider()

            // Business Hours
            Column {
                SectionHeader(stringResource(R.string.settings_business_hours))
                Spacer(modifier = Modifier.height(8.dp))

                dayLabels.forEach { (dayKey, dayLabel) ->
                    val hours = uiState.businessHours[dayKey] ?: DayHours()
                    DayHoursRow(
                        dayLabel = dayLabel,
                        hours = hours,
                        onToggleClosed = { closed ->
                            viewModel.setDayHours(dayKey, hours.copy(closed = closed))
                        },
                        onOpenChange = { open ->
                            viewModel.setDayHours(dayKey, hours.copy(open = open))
                        },
                        onCloseChange = { close ->
                            viewModel.setDayHours(dayKey, hours.copy(close = close))
                        }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }

            // Status messages
            uiState.error?.let { error ->
                Text(
                    text = error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            if (uiState.saveSuccess) {
                Text(
                    text = stringResource(R.string.settings_saved),
                    color = MaterialTheme.colorScheme.secondary,
                    style = MaterialTheme.typography.bodySmall
                )
            }

            // Save button
            Button(
                onClick = { viewModel.save() },
                enabled = !uiState.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.height(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                } else {
                    Text(stringResource(R.string.settings_save))
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LanguageDropdown(
    selected: String,
    options: List<Pair<String, String>>,
    onSelected: (String) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedLabel = options.firstOrNull { it.first == selected }?.second ?: selected

    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelected(value)
                        expanded = false
                    }
                )
            }
        }
    }
}

@Composable
private fun DayHoursRow(
    dayLabel: String,
    hours: DayHours,
    onToggleClosed: (Boolean) -> Unit,
    onOpenChange: (String) -> Unit,
    onCloseChange: (String) -> Unit
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = dayLabel,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !hours.closed,
                onCheckedChange = { onToggleClosed(!it) }
            )
        }
        if (!hours.closed) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = hours.open,
                    onValueChange = onOpenChange,
                    label = { Text(stringResource(R.string.settings_open)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
                Text("–")
                OutlinedTextField(
                    value = hours.close,
                    onValueChange = onCloseChange,
                    label = { Text(stringResource(R.string.settings_closed)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

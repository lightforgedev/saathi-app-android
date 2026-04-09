package dev.lightforge.saathi.ui.settings

import androidx.compose.foundation.background
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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import dev.lightforge.saathi.R
import dev.lightforge.saathi.ui.theme.SaathiBorder
import dev.lightforge.saathi.ui.theme.SaathiGreen
import dev.lightforge.saathi.ui.theme.SaathiSurface
import dev.lightforge.saathi.ui.theme.SaathiSurface2
import dev.lightforge.saathi.ui.theme.SaathiTextSecondary

/**
 * Settings screen: call mode, business hours, language, notifications.
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
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = stringResource(R.string.settings_title),
                            fontWeight = FontWeight.Bold,
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onBackground
                        )
                    },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(
                                Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Back",
                                tint = MaterialTheme.colorScheme.onBackground
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.background
                    )
                )
                HorizontalDivider(color = SaathiBorder, thickness = 0.5.dp)
            }
        }
    ) { paddingValues ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = SaathiGreen)
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            // Restaurant info
            if (uiState.restaurantName.isNotEmpty()) {
                SettingsSection(title = stringResource(R.string.settings_restaurant)) {
                    Text(
                        text = uiState.restaurantName,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onBackground,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }

            // Call Handling Mode
            SettingsSection(title = stringResource(R.string.settings_call_mode)) {
                val modes = listOf("full_auto", "busy_no_answer", "off")
                val modeLabels = listOf(
                    stringResource(R.string.settings_call_mode_full_auto),
                    stringResource(R.string.settings_call_mode_busy),
                    stringResource(R.string.settings_call_mode_off)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    modes.forEachIndexed { index, mode ->
                        val isSelected = uiState.callMode == mode
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .background(
                                    if (isSelected) SaathiGreen else SaathiSurface2,
                                    RoundedCornerShape(8.dp)
                                )
                                .then(
                                    if (!isSelected) Modifier.background(
                                        SaathiSurface2,
                                        RoundedCornerShape(8.dp)
                                    ) else Modifier
                                )
                                .padding(vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            TextButton(
                                onClick = { viewModel.setCallMode(mode) },
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.textButtonColors(
                                    contentColor = if (isSelected) Color.Black else SaathiTextSecondary
                                )
                            ) {
                                Text(
                                    text = modeLabels[index],
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    fontSize = 12.sp,
                                    maxLines = 1
                                )
                            }
                        }
                    }
                }
            }

            // Greeting Language
            SettingsSection(title = stringResource(R.string.settings_language)) {
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

            // Notifications
            SettingsSection(title = stringResource(R.string.settings_notifications)) {
                ToggleRow(
                    label = stringResource(R.string.settings_whatsapp_summary),
                    checked = uiState.whatsappDailySummary,
                    onCheckedChange = { viewModel.setWhatsappSummary(it) }
                )
                Spacer(modifier = Modifier.height(4.dp))
                HorizontalDivider(color = SaathiBorder, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(4.dp))
                ToggleRow(
                    label = stringResource(R.string.settings_per_call),
                    checked = uiState.perCallNotification,
                    onCheckedChange = { viewModel.setPerCallNotification(it) }
                )
            }

            // Business Hours
            SettingsSection(title = stringResource(R.string.settings_business_hours)) {
                dayLabels.forEachIndexed { index, (dayKey, dayLabel) ->
                    val hours = uiState.businessHours[dayKey] ?: DayHours()
                    if (index > 0) {
                        HorizontalDivider(
                            color = SaathiBorder,
                            thickness = 0.5.dp,
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    }
                    DayHoursRow(
                        dayLabel = dayLabel,
                        hours = hours,
                        onToggleClosed = { closed -> viewModel.setDayHours(dayKey, hours.copy(closed = closed)) },
                        onOpenChange = { open -> viewModel.setDayHours(dayKey, hours.copy(open = open)) },
                        onCloseChange = { close -> viewModel.setDayHours(dayKey, hours.copy(close = close)) }
                    )
                }
            }

            // Status messages
            uiState.error?.let { error ->
                Text(text = error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (uiState.saveSuccess) {
                Text(text = stringResource(R.string.settings_saved), color = SaathiGreen, style = MaterialTheme.typography.bodySmall)
            }

            // Save button
            Button(
                onClick = { viewModel.save() },
                enabled = !uiState.isSaving,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaathiGreen,
                    contentColor = Color.Black,
                    disabledContainerColor = SaathiGreen.copy(alpha = 0.3f)
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp), color = Color.Black, strokeWidth = 2.dp)
                } else {
                    Text(text = stringResource(R.string.settings_save), fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaathiSurface, RoundedCornerShape(14.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.SemiBold,
            color = SaathiGreen
        )
        content()
    }
}

@Composable
private fun ToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color.Black,
                checkedTrackColor = SaathiGreen,
                uncheckedThumbColor = SaathiTextSecondary,
                uncheckedTrackColor = SaathiSurface2,
                uncheckedBorderColor = SaathiBorder
            )
        )
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

    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selectedLabel,
            onValueChange = {},
            readOnly = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            shape = RoundedCornerShape(8.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = SaathiGreen,
                unfocusedBorderColor = SaathiBorder,
                focusedTextColor = MaterialTheme.colorScheme.onBackground,
                unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                focusedContainerColor = SaathiSurface2,
                unfocusedContainerColor = SaathiSurface2,
                focusedTrailingIconColor = SaathiGreen,
                unfocusedTrailingIconColor = SaathiTextSecondary
            ),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            containerColor = SaathiSurface2
        ) {
            options.forEach { (value, label) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label,
                            color = if (value == selected) SaathiGreen
                                    else MaterialTheme.colorScheme.onBackground
                        )
                    },
                    onClick = { onSelected(value); expanded = false }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
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
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Switch(
                checked = !hours.closed,
                onCheckedChange = { onToggleClosed(!it) },
                colors = SwitchDefaults.colors(
                    checkedThumbColor = Color.Black,
                    checkedTrackColor = SaathiGreen,
                    uncheckedThumbColor = SaathiTextSecondary,
                    uncheckedTrackColor = SaathiSurface2,
                    uncheckedBorderColor = SaathiBorder
                )
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
                    label = { Text(stringResource(R.string.settings_open), color = SaathiTextSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaathiGreen,
                        unfocusedBorderColor = SaathiBorder,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = SaathiGreen
                    ),
                    modifier = Modifier.weight(1f)
                )
                Text("–", color = SaathiTextSecondary)
                OutlinedTextField(
                    value = hours.close,
                    onValueChange = onCloseChange,
                    label = { Text(stringResource(R.string.settings_closed), color = SaathiTextSecondary) },
                    singleLine = true,
                    shape = RoundedCornerShape(8.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = SaathiGreen,
                        unfocusedBorderColor = SaathiBorder,
                        focusedTextColor = MaterialTheme.colorScheme.onBackground,
                        unfocusedTextColor = MaterialTheme.colorScheme.onBackground,
                        cursorColor = SaathiGreen
                    ),
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

package dev.lightforge.saathi.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Settings screen for device configuration and diagnostics.
 *
 * Sections:
 * - Restaurant info (name, phone — read-only from backend)
 * - Device pairing status
 * - Language preference
 * - Sync status + manual sync trigger
 * - Unpair device
 * - Version info
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // TODO: Wire to ViewModel
            ListItem(
                headlineContent = { Text("Restaurant") },
                supportingContent = { Text("Not configured") }
            )
            ListItem(
                headlineContent = { Text("Device Status") },
                supportingContent = { Text("Not paired") }
            )
            ListItem(
                headlineContent = { Text("Language") },
                supportingContent = { Text("Hindi + English") }
            )
            ListItem(
                headlineContent = { Text("Last Sync") },
                supportingContent = { Text("Never") }
            )
            ListItem(
                headlineContent = {
                    Text("Unpair Device", color = MaterialTheme.colorScheme.error)
                },
                supportingContent = { Text("Remove this device from the restaurant") }
            )
        }
    }
}

package dev.lightforge.saathi.ui.setup

import android.os.Build
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.lightforge.saathi.R

/**
 * Battery optimization guidance — step 4 of onboarding.
 *
 * Detects OEM from Build.MANUFACTURER and shows device-specific steps.
 * Samsung and Xiaomi/Redmi get tailored instructions.
 * All others get generic Android steps.
 */
@Composable
fun BatteryOptimizationScreen(onContinue: () -> Unit) {
    val manufacturer = Build.MANUFACTURER.lowercase()

    val isSamsung = manufacturer.contains("samsung")
    val isXiaomi = manufacturer.contains("xiaomi") || manufacturer.contains("redmi") || manufacturer.contains("poco")

    val genericSteps = listOf(
        stringResource(R.string.battery_step1),
        stringResource(R.string.battery_step2),
        stringResource(R.string.battery_step3),
        stringResource(R.string.battery_step4)
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(40.dp))

        Icon(
            imageVector = Icons.Default.BatteryFull,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(64.dp)
        )

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = stringResource(R.string.battery_title),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = stringResource(R.string.battery_subtitle),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(32.dp))

        // OEM-specific note
        when {
            isSamsung -> {
                OemNoteCard(text = stringResource(R.string.battery_samsung_note))
                Spacer(modifier = Modifier.height(16.dp))
            }
            isXiaomi -> {
                OemNoteCard(text = stringResource(R.string.battery_xiaomi_note))
                Spacer(modifier = Modifier.height(16.dp))
            }
        }

        // Numbered steps
        genericSteps.forEachIndexed { index, step ->
            StepRow(number = index + 1, text = step)
            Spacer(modifier = Modifier.height(12.dp))
        }

        Spacer(modifier = Modifier.height(40.dp))

        Button(
            onClick = onContinue,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(stringResource(R.string.battery_start_button))
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(MaterialTheme.colorScheme.primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$number",
                color = MaterialTheme.colorScheme.onPrimary,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun OemNoteCard(text: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(16.dp)
        )
    }
}

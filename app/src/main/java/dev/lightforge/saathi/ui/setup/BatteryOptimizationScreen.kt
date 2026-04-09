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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BatteryAlert
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightforge.saathi.R
import dev.lightforge.saathi.ui.theme.SaathiGreen
import dev.lightforge.saathi.ui.theme.SaathiGreenDim
import dev.lightforge.saathi.ui.theme.SaathiSurface
import dev.lightforge.saathi.ui.theme.SaathiSurface2
import dev.lightforge.saathi.ui.theme.SaathiTextSecondary

/**
 * Battery optimization guidance — step 4 of onboarding.
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

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            // Icon
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .background(SaathiGreen.copy(alpha = 0.12f), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.BatteryAlert,
                    contentDescription = null,
                    tint = SaathiGreen,
                    modifier = Modifier.size(32.dp)
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            Text(
                text = stringResource(R.string.battery_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = stringResource(R.string.battery_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = SaathiTextSecondary,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(24.dp))

            // OEM-specific note
            if (isSamsung || isXiaomi) {
                val oemText = if (isSamsung) stringResource(R.string.battery_samsung_note)
                              else stringResource(R.string.battery_xiaomi_note)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SaathiGreenDim.copy(alpha = 0.2f), RoundedCornerShape(10.dp))
                        .padding(14.dp)
                ) {
                    Text(
                        text = oemText,
                        style = MaterialTheme.typography.bodySmall,
                        color = SaathiGreen,
                        textAlign = TextAlign.Start
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
            }

            // Steps card
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SaathiSurface, RoundedCornerShape(14.dp))
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                genericSteps.forEachIndexed { index, step ->
                    StepRow(number = index + 1, text = step)
                }
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = onContinue,
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = SaathiGreen,
                    contentColor = Color.Black
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(50.dp)
            ) {
                Text(
                    text = stringResource(R.string.battery_start_button),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun StepRow(number: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .background(SaathiGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "$number",
                color = Color.Black,
                fontWeight = FontWeight.Bold,
                fontSize = 12.sp
            )
        }
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.weight(1f)
        )
    }
}

package dev.lightforge.saathi.ui.setup

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.lightforge.saathi.R
import dev.lightforge.saathi.ui.theme.SaathiBorder
import dev.lightforge.saathi.ui.theme.SaathiGreen
import dev.lightforge.saathi.ui.theme.SaathiSurface
import dev.lightforge.saathi.ui.theme.SaathiSurface2
import dev.lightforge.saathi.ui.theme.SaathiTextSecondary

/**
 * Permissions onboarding screen — step 3.
 */
@Composable
fun PermissionsScreen(onPermissionsGranted: () -> Unit) {
    val requiredPermissions = buildList {
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) add(Manifest.permission.MANAGE_OWN_CALLS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }

    var permissionResults by remember {
        mutableStateOf(requiredPermissions.associateWith { false })
    }
    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        permissionResults = permissionResults + results
        if (permissionResults.values.all { it }) onPermissionsGranted()
    }

    val allGranted = permissionResults.values.all { it }

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

            Text(
                text = "saathi.help",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = SaathiGreen
            )

            Spacer(modifier = Modifier.height(32.dp))

            Text(
                text = stringResource(R.string.permissions_title),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = stringResource(R.string.permissions_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = SaathiTextSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 8.dp)
            )

            Spacer(modifier = Modifier.height(32.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SaathiSurface, RoundedCornerShape(16.dp))
                    .padding(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                PermissionRow(
                    icon = Icons.Default.Call,
                    title = stringResource(R.string.permissions_calls),
                    description = stringResource(R.string.permissions_calls_desc),
                    isGranted = allGranted
                )
                PermissionRow(
                    icon = Icons.Default.Mic,
                    title = stringResource(R.string.permissions_microphone),
                    description = stringResource(R.string.permissions_microphone_desc),
                    isGranted = allGranted
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PermissionRow(
                        icon = Icons.Default.Notifications,
                        title = stringResource(R.string.permissions_notifications),
                        description = stringResource(R.string.permissions_notifications_desc),
                        isGranted = allGranted
                    )
                }
                PermissionRow(
                    icon = Icons.Default.PhoneAndroid,
                    title = stringResource(R.string.permissions_phone_state),
                    description = stringResource(R.string.permissions_phone_state_desc),
                    isGranted = allGranted
                )
            }

            Spacer(modifier = Modifier.height(32.dp))

            Button(
                onClick = {
                    if (allGranted) onPermissionsGranted()
                    else launcher.launch(requiredPermissions.toTypedArray())
                },
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
                    text = if (allGranted) "Continue →" else stringResource(R.string.permissions_grant_button),
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun PermissionRow(
    icon: ImageVector,
    title: String,
    description: String,
    isGranted: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(SaathiSurface2, RoundedCornerShape(10.dp))
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .background(SaathiGreen.copy(alpha = 0.12f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = SaathiGreen,
                modifier = Modifier.size(18.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onBackground
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = SaathiTextSecondary
            )
        }
        if (isGranted) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(SaathiGreen, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Check,
                    contentDescription = "Granted",
                    tint = Color.Black,
                    modifier = Modifier.size(14.dp)
                )
            }
        } else {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .border(1.dp, SaathiBorder, CircleShape)
            )
        }
    }
}

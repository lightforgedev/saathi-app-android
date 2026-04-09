package dev.lightforge.saathi.ui.spike

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import dev.lightforge.saathi.ui.theme.SaathiTheme

/**
 * Debug-only activity for Spike 1: Call Interception + Bidirectional Audio Streaming.
 *
 * Provides a UI to:
 *   - Simulate an incoming call without a real SIM call (for on-device testing)
 *   - Toggle echo mode (mic loopback — no Gemini required)
 *   - View real-time AudioRecord / AudioTrack / WebSocket state
 *   - View a decibel meter (RMS of mic buffer) proving capture is live
 *
 * NOT for production — remove or gate behind BuildConfig.DEBUG before release.
 *
 * Usage on device:
 *   1. Grant RECORD_AUDIO + MANAGE_OWN_CALLS permissions
 *   2. Toggle "Echo Mode" ON
 *   3. Tap "Simulate Incoming Call"
 *   4. Tap "Answer" in the system call notification
 *   5. Speak into the mic — hear your voice echoed back
 *   6. Observe dB meter confirms mic capture is live
 */
class SpikeTestActivity : ComponentActivity() {

    companion object {
        private const val TAG = "SpikeTestActivity"
    }

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val allGranted = grants.values.all { it }
        Log.i(TAG, "Permissions granted: $allGranted | $grants")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request required permissions up front
        val permissionsNeeded = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MANAGE_OWN_CALLS,
            Manifest.permission.READ_PHONE_STATE
        ).filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }.toTypedArray()

        if (permissionsNeeded.isNotEmpty()) {
            requestPermission.launch(permissionsNeeded)
        }

        setContent {
            SaathiTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    SpikeTestScreen()
                }
            }
        }
    }
}

@Composable
fun SpikeTestScreen() {
    val vm: SpikeTestViewModel = viewModel()
    val state by vm.state.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Spike 1 — Call Interception Test",
            style = MaterialTheme.typography.titleLarge
        )

        // --- Echo mode toggle ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (state.echoMode)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Echo Mode", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Mic → Speaker loopback (no Gemini)",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = state.echoMode,
                    onCheckedChange = { vm.setEchoMode(it) },
                    enabled = !state.callActive
                )
            }
        }

        // --- Simulate incoming call button ---
        Button(
            onClick = { vm.simulateIncomingCall() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.callActive && state.phoneAccountReady,
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary
            )
        ) {
            Text("Simulate Incoming Call (+91 9876500001)")
        }

        // --- Phone account status ---
        StatusRow(
            label = "PhoneAccount",
            value = if (state.phoneAccountReady) "Registered" else "Not registered",
            ok = state.phoneAccountReady
        )

        // --- Real-time state panel ---
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Audio Pipeline", style = MaterialTheme.typography.titleSmall)

                StatusRow("AudioRecord", state.audioRecordState, state.audioRecordState == "RECORDING")
                StatusRow("AudioTrack", state.audioTrackState, state.audioTrackState == "PLAYING")
                StatusRow("WebSocket", state.webSocketState,
                    state.webSocketState == "CONNECTED" || state.webSocketState == "ECHO")

                Spacer(modifier = Modifier.height(8.dp))

                // Decibel meter — proves mic is capturing
                Text(
                    "Mic Level: ${state.dbLevel} dBFS",
                    style = MaterialTheme.typography.bodyMedium
                )
                LinearProgressIndicator(
                    progress = { state.dbProgress },
                    modifier = Modifier.fillMaxWidth(),
                    color = when {
                        state.dbProgress > 0.8f -> Color.Red
                        state.dbProgress > 0.4f -> Color.Yellow
                        else -> MaterialTheme.colorScheme.primary
                    }
                )

                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Frames captured: ${state.frameCount}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // --- Call log ---
        if (state.callLog.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text("Log", style = MaterialTheme.typography.titleSmall)
                    Spacer(modifier = Modifier.height(4.dp))
                    state.callLog.takeLast(8).forEach { entry ->
                        Text(
                            entry,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        // --- End call button ---
        if (state.callActive) {
            Button(
                onClick = { vm.endCall() },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("End Call")
            }
        }
    }
}

@Composable
private fun StatusRow(label: String, value: String, ok: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Text(
            value,
            style = MaterialTheme.typography.bodyMedium,
            color = if (ok) Color(0xFF4CAF50) else MaterialTheme.colorScheme.error
        )
    }
}

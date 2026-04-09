package dev.lightforge.saathi.ui.setup

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
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
 * OTP verification — step 2 of device pairing onboarding.
 * Shows individual digit boxes for the 6-digit OTP.
 */
@Composable
fun OTPScreen(
    pairingId: String,
    onVerified: () -> Unit,
    viewModel: OTPViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(horizontal = 28.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // Brand
            Text(
                text = "saathi.help",
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = SaathiGreen
            )

            Spacer(modifier = Modifier.height(48.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(SaathiSurface, RoundedCornerShape(16.dp))
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = stringResource(R.string.otp_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onBackground
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = stringResource(R.string.otp_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = SaathiTextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 8.dp)
                )

                Spacer(modifier = Modifier.height(32.dp))

                // Hidden text field driving OTP input
                BasicTextField(
                    value = uiState.otp,
                    onValueChange = { if (it.length <= 6) viewModel.onOtpChanged(it) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                    cursorBrush = SolidColor(SaathiGreen),
                    textStyle = TextStyle(color = Color.Transparent, fontSize = 1.sp),
                    decorationBox = {
                        OtpDigitRow(otp = uiState.otp, hasError = uiState.error != null)
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                uiState.error?.let { error ->
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = error,
                        color = MaterialTheme.colorScheme.error,
                        style = MaterialTheme.typography.bodySmall,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "OTP expiry: 5:00",
                    style = MaterialTheme.typography.bodySmall,
                    color = SaathiTextSecondary
                )

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = { viewModel.verify(pairingId, onSuccess = onVerified) },
                    enabled = uiState.otp.length == 6 && !uiState.isLoading,
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SaathiGreen,
                        contentColor = Color.Black,
                        disabledContainerColor = SaathiGreen.copy(alpha = 0.3f),
                        disabledContentColor = Color.Black.copy(alpha = 0.4f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(20.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text(
                            text = stringResource(R.string.otp_verify_button),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun OtpDigitRow(otp: String, hasError: Boolean) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)
    ) {
        repeat(6) { index ->
            val digit = otp.getOrNull(index)?.toString() ?: ""
            val isFocused = index == otp.length
            val borderColor = when {
                hasError -> MaterialTheme.colorScheme.error
                isFocused -> SaathiGreen
                digit.isNotEmpty() -> SaathiGreen.copy(alpha = 0.5f)
                else -> SaathiBorder
            }

            Box(
                modifier = Modifier
                    .size(width = 42.dp, height = 52.dp)
                    .background(SaathiSurface2, RoundedCornerShape(8.dp))
                    .border(
                        width = if (isFocused) 2.dp else 1.dp,
                        color = borderColor,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = digit,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (digit.isNotEmpty()) SaathiGreen else SaathiTextSecondary,
                    textAlign = TextAlign.Center
                )
            }
        }
    }
}

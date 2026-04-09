package dev.lightforge.saathi.ui.setup

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lightforge.saathi.auth.DevicePairing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SetupUiState(
    val phoneNumber: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class SetupViewModel @Inject constructor(
    private val devicePairing: DevicePairing
) : ViewModel() {

    private val _uiState = MutableStateFlow(SetupUiState())
    val uiState: StateFlow<SetupUiState> = _uiState.asStateFlow()

    fun onPhoneChanged(phone: String) {
        _uiState.value = _uiState.value.copy(phoneNumber = phone, error = null)
    }

    fun sendOtp(onSuccess: (pairingId: String) -> Unit) {
        val rawPhone = _uiState.value.phoneNumber.trim()
        val phone = if (rawPhone.startsWith("+")) rawPhone else "+91$rawPhone"

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            val deviceName = "${Build.MANUFACTURER} ${Build.MODEL}"
            val result = devicePairing.startPairing(phone, deviceName)

            when (result) {
                is DevicePairing.PairingResult.OtpSent -> {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    withContext(Dispatchers.Main) { onSuccess(result.pairingId) }
                }
                is DevicePairing.PairingResult.Error -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = result.message
                    )
                }
                else -> {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Unexpected response"
                    )
                }
            }
        }
    }
}

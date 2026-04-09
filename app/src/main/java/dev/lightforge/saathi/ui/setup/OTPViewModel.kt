package dev.lightforge.saathi.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lightforge.saathi.auth.DevicePairing
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.VerifyRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OTPUiState(
    val otp: String = "",
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class OTPViewModel @Inject constructor(
    private val api: AegisApiClient,
    private val tokenManager: TokenManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(OTPUiState())
    val uiState: StateFlow<OTPUiState> = _uiState.asStateFlow()

    fun onOtpChanged(otp: String) {
        if (otp.length <= 6 && otp.all { it.isDigit() }) {
            _uiState.value = _uiState.value.copy(otp = otp, error = null)
        }
    }

    fun verify(pairingId: String, onSuccess: () -> Unit) {
        val otp = _uiState.value.otp
        if (otp.length != 6) return

        _uiState.value = _uiState.value.copy(isLoading = true, error = null)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.verifyPairing(VerifyRequest(pairingId, otp))
                if (response.isSuccessful) {
                    val body = response.body()!!
                    tokenManager.storeDeviceToken(body.device_token)
                    tokenManager.storeOrgId(body.org_id)
                    _uiState.value = _uiState.value.copy(isLoading = false)
                    onSuccess()
                } else {
                    val errorMsg = when (response.code()) {
                        401 -> "OTP galat hai. Dobara try karein."
                        410 -> "OTP expire ho gaya. Phir se phone number daalein."
                        else -> "Verification fail hua (${response.code()})"
                    }
                    _uiState.value = _uiState.value.copy(isLoading = false, error = errorMsg)
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Network error"
                )
            }
        }
    }
}

package dev.lightforge.saathi.ui.home

import android.app.Application
import android.app.role.RoleManager
import android.telecom.TelecomManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lightforge.saathi.auth.TokenManager
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.CallRecord
import dev.lightforge.saathi.network.UpdateSettingsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val restaurantName: String = "",
    val isActive: Boolean = true,
    val isDefaultDialer: Boolean = true,
    val callsHandled: Int = 0,
    val reservationsMade: Int = 0,
    val lastCallDurationSeconds: Int? = null,
    val recentCalls: List<CallRecord> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    application: Application,
    private val api: AegisApiClient,
    private val tokenManager: TokenManager
) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadStats()
        loadRecentCalls()
        loadConfig()
        checkDefaultDialer()
    }

    fun checkDefaultDialer() {
        val ctx = getApplication<Application>()
        val isDefault = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            val rm = ctx.getSystemService(RoleManager::class.java)
            rm?.isRoleHeld(RoleManager.ROLE_DIALER) == true
        } else {
            val tm = ctx.getSystemService(TelecomManager::class.java)
            tm?.defaultDialerPackage == ctx.packageName
        }
        _uiState.value = _uiState.value.copy(isDefaultDialer = isDefault)
    }

    fun loadStats() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getStats()
                if (response.isSuccessful) {
                    val body = response.body()!!
                    _uiState.value = _uiState.value.copy(
                        callsHandled = body.today.calls_handled,
                        reservationsMade = body.today.reservations_made,
                        lastCallDurationSeconds = body.today.last_call_duration_seconds
                    )
                }
            } catch (_: Exception) {
                // Stats load failure is non-fatal
            }
        }
    }

    fun loadRecentCalls() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getCalls(limit = 4)
                if (response.isSuccessful) {
                    _uiState.value = _uiState.value.copy(
                        recentCalls = response.body()?.calls ?: emptyList()
                    )
                }
            } catch (_: Exception) {
                // Non-fatal
            }
        }
    }

    fun loadConfig() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getConfig()
                if (response.isSuccessful) {
                    val body = response.body()!!
                    val isActive = body.restaurant.call_mode != "off"
                    tokenManager.setSaathiActive(isActive)
                    _uiState.value = _uiState.value.copy(
                        restaurantName = body.restaurant.name,
                        isActive = isActive
                    )
                }
            } catch (_: Exception) {
                // Non-fatal
            }
        }
    }

    fun toggleActive(active: Boolean) {
        val newMode = if (active) "full_auto" else "off"
        tokenManager.setSaathiActive(active) // persist immediately for InCallService
        _uiState.value = _uiState.value.copy(isActive = active)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                api.updateSettings(UpdateSettingsRequest(call_mode = newMode))
            } catch (_: Exception) {
                // Revert optimistic update on failure
                tokenManager.setSaathiActive(!active)
                _uiState.value = _uiState.value.copy(isActive = !active)
            }
        }
    }

    fun formatDuration(seconds: Int?): String {
        if (seconds == null) return "-"
        val m = seconds / 60
        val s = seconds % 60
        return if (m > 0) "${m}m ${s}s" else "${s}s"
    }
}

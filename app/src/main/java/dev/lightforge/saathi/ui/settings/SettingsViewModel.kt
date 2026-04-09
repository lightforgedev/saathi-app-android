package dev.lightforge.saathi.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.BusinessHourDto
import dev.lightforge.saathi.network.NotificationSettings
import dev.lightforge.saathi.network.UpdateSettingsRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class DayHours(
    val open: String = "11:00",
    val close: String = "23:00",
    val closed: Boolean = false
)

data class SettingsUiState(
    val restaurantName: String = "",
    val callMode: String = "full_auto",
    val primaryLanguage: String = "auto",
    val whatsappDailySummary: Boolean = true,
    val perCallNotification: Boolean = true,
    val businessHours: Map<String, DayHours> = defaultHours(),
    val isSaving: Boolean = false,
    val saveSuccess: Boolean = false,
    val error: String? = null,
    val isLoading: Boolean = false
)

fun defaultHours(): Map<String, DayHours> = mapOf(
    "monday" to DayHours(),
    "tuesday" to DayHours(),
    "wednesday" to DayHours(),
    "thursday" to DayHours(),
    "friday" to DayHours(),
    "saturday" to DayHours(),
    "sunday" to DayHours(closed = true)
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val api: AegisApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        loadConfig()
    }

    fun loadConfig() {
        _uiState.value = _uiState.value.copy(isLoading = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getConfig()
                if (response.isSuccessful) {
                    val body = response.body()!!
                    val r = body.restaurant
                    val hours = r.business_hours?.mapValues { (_, dto) ->
                        DayHours(open = dto.open, close = dto.close, closed = dto.closed)
                    } ?: defaultHours()
                    _uiState.value = _uiState.value.copy(
                        restaurantName = r.name,
                        callMode = r.call_mode ?: "full_auto",
                        primaryLanguage = r.primary_language ?: "auto",
                        businessHours = hours,
                        isLoading = false
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoading = false)
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false)
            }
        }
    }

    fun setCallMode(mode: String) {
        _uiState.value = _uiState.value.copy(callMode = mode, saveSuccess = false)
    }

    fun setLanguage(language: String) {
        _uiState.value = _uiState.value.copy(primaryLanguage = language, saveSuccess = false)
    }

    fun setWhatsappSummary(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(whatsappDailySummary = enabled, saveSuccess = false)
    }

    fun setPerCallNotification(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(perCallNotification = enabled, saveSuccess = false)
    }

    fun setDayHours(day: String, hours: DayHours) {
        val updated = _uiState.value.businessHours.toMutableMap()
        updated[day] = hours
        _uiState.value = _uiState.value.copy(businessHours = updated, saveSuccess = false)
    }

    fun save() {
        _uiState.value = _uiState.value.copy(isSaving = true, error = null, saveSuccess = false)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = _uiState.value
                val hoursDto = state.businessHours.mapValues { (_, day) ->
                    BusinessHourDto(open = day.open, close = day.close, closed = day.closed)
                }
                val request = UpdateSettingsRequest(
                    call_mode = state.callMode,
                    primary_language = if (state.primaryLanguage == "auto") null else state.primaryLanguage,
                    notifications = NotificationSettings(
                        whatsapp_daily_summary = state.whatsappDailySummary,
                        per_call_notification = state.perCallNotification
                    ),
                    business_hours = hoursDto
                )
                val response = api.updateSettings(request)
                if (response.isSuccessful && response.body()?.ok == true) {
                    _uiState.value = _uiState.value.copy(isSaving = false, saveSuccess = true)
                } else {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        error = "Save nahi ho saka"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    error = e.message ?: "Network error"
                )
            }
        }
    }
}

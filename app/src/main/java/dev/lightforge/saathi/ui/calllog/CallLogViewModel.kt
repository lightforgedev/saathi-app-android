package dev.lightforge.saathi.ui.calllog

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dev.lightforge.saathi.network.AegisApiClient
import dev.lightforge.saathi.network.CallRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.OffsetDateTime
import javax.inject.Inject

data class CallLogUiState(
    val groupedCalls: Map<String, List<CallRecord>> = emptyMap(),
    val isLoading: Boolean = false,
    val isLoadingMore: Boolean = false,
    val hasMore: Boolean = false,
    val nextCursor: String? = null,
    val error: String? = null
)

@HiltViewModel
class CallLogViewModel @Inject constructor(
    private val api: AegisApiClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(CallLogUiState())
    val uiState: StateFlow<CallLogUiState> = _uiState.asStateFlow()

    private val allCalls = mutableListOf<CallRecord>()

    init {
        loadCalls()
    }

    fun loadCalls() {
        _uiState.value = _uiState.value.copy(isLoading = true, error = null)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getCalls(limit = 20)
                if (response.isSuccessful) {
                    val body = response.body()!!
                    allCalls.clear()
                    allCalls.addAll(body.calls)
                    _uiState.value = _uiState.value.copy(
                        groupedCalls = groupByDate(allCalls),
                        isLoading = false,
                        hasMore = body.has_more,
                        nextCursor = body.next_cursor
                    )
                } else {
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        error = "Load failed (${response.code()})"
                    )
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Network error"
                )
            }
        }
    }

    fun loadMore() {
        val cursor = _uiState.value.nextCursor ?: return
        if (_uiState.value.isLoadingMore) return

        _uiState.value = _uiState.value.copy(isLoadingMore = true)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val response = api.getCalls(limit = 20, before = cursor)
                if (response.isSuccessful) {
                    val body = response.body()!!
                    allCalls.addAll(body.calls)
                    _uiState.value = _uiState.value.copy(
                        groupedCalls = groupByDate(allCalls),
                        isLoadingMore = false,
                        hasMore = body.has_more,
                        nextCursor = body.next_cursor
                    )
                } else {
                    _uiState.value = _uiState.value.copy(isLoadingMore = false)
                }
            } catch (_: Exception) {
                _uiState.value = _uiState.value.copy(isLoadingMore = false)
            }
        }
    }

    private fun groupByDate(calls: List<CallRecord>): Map<String, List<CallRecord>> {
        val today = LocalDate.now()
        val yesterday = today.minusDays(1)

        return calls.groupBy { call ->
            try {
                val date = OffsetDateTime.parse(call.started_at).toLocalDate()
                when (date) {
                    today -> "TODAY"
                    yesterday -> "YESTERDAY"
                    else -> "EARLIER"
                }
            } catch (_: Exception) {
                "EARLIER"
            }
        }.toSortedMap(compareBy { group ->
            when (group) {
                "TODAY" -> 0
                "YESTERDAY" -> 1
                else -> 2
            }
        })
    }
}

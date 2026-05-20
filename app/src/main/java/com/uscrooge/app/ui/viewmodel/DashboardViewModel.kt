package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.model.Portfolio
import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class DashboardViewModel @Inject constructor(
    private val repository: TradingRepository,
    private val configRepository: ConfigRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        viewModelScope.launch {
            try {
                _uiState.value = DashboardUiState.Loading

                repository.getOpenPositions().collect { positions ->
                    val config = configRepository.configFlow.first()
                    val portfolio = repository.getPortfolio(config)
                    _uiState.value = DashboardUiState.Success(portfolio, positions)
                }
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun refreshData() {
        loadDashboard()
    }
}

sealed class DashboardUiState {
    object Loading : DashboardUiState()
    data class Success(
        val portfolio: Portfolio,
        val positions: List<Position>
    ) : DashboardUiState()
    data class Error(val message: String) : DashboardUiState()
}

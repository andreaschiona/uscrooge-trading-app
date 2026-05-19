package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.model.TradingSignal
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SignalsViewModel(
    private val repository: TradingRepository,
    private val orderExecutor: OrderExecutor
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignalsUiState>(SignalsUiState.Loading)
    val uiState: StateFlow<SignalsUiState> = _uiState.asStateFlow()

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    init {
        loadSignals()
    }

    fun loadSignals() {
        viewModelScope.launch {
            try {
                _uiState.value = SignalsUiState.Loading

                repository.getAllSignals().collect { signals ->
                    _uiState.value = SignalsUiState.Success(signals)
                }
            } catch (e: Exception) {
                _uiState.value = SignalsUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun executeSignal(signal: TradingSignal) {
        viewModelScope.launch {
            try {
                _executionState.value = ExecutionState.Executing(signal.id)

                val result = orderExecutor.executeSignal(signal)

                if (result.isSuccess) {
                    _executionState.value = ExecutionState.Success("Order executed successfully")
                } else {
                    _executionState.value = ExecutionState.Error(
                        result.exceptionOrNull()?.message ?: "Execution failed"
                    )
                }

                // Reset state after 3 seconds
                kotlinx.coroutines.delay(3000)
                _executionState.value = ExecutionState.Idle
            } catch (e: Exception) {
                _executionState.value = ExecutionState.Error(e.message ?: "Unknown error")
                kotlinx.coroutines.delay(3000)
                _executionState.value = ExecutionState.Idle
            }
        }
    }

    fun ignoreSignal(signal: TradingSignal) {
        viewModelScope.launch {
            try {
                orderExecutor.ignoreSignal(signal)
            } catch (e: Exception) {
                _executionState.value = ExecutionState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun refreshSignals() {
        loadSignals()
    }
}

sealed class SignalsUiState {
    object Loading : SignalsUiState()
    data class Success(val signals: List<TradingSignal>) : SignalsUiState()
    data class Error(val message: String) : SignalsUiState()
}

sealed class ExecutionState {
    object Idle : ExecutionState()
    data class Executing(val signalId: Long) : ExecutionState()
    data class Success(val message: String) : ExecutionState()
    data class Error(val message: String) : ExecutionState()
}

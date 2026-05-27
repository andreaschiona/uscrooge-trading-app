package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.data.model.AnalysisLog
import com.uscrooge.app.data.model.TradingSignal
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SignalsViewModel @Inject constructor(
    private val repository: TradingRepository,
    private val configRepository: ConfigRepository,
    private val orderExecutor: OrderExecutor,
    private val gitHubIssueReporter: GitHubIssueReporter
) : ViewModel() {

    private val _uiState = MutableStateFlow<SignalsUiState>(SignalsUiState.Loading)
    val uiState: StateFlow<SignalsUiState> = _uiState.asStateFlow()

    private val _executionState = MutableStateFlow<ExecutionState>(ExecutionState.Idle)
    val executionState: StateFlow<ExecutionState> = _executionState.asStateFlow()

    val lastAnalysisLog: StateFlow<AnalysisLog?> = repository.lastAnalysisLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), null)

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
                    val error = result.exceptionOrNull()
                    _executionState.value = ExecutionState.Error(
                        error?.message ?: "Execution failed"
                    )
                    if (error != null) {
                        reportToGitHub("Manual signal execution failed for ${signal.pair}", error)
                    }
                }

                // Reset state after 3 seconds
                kotlinx.coroutines.delay(3000)
                _executionState.value = ExecutionState.Idle
            } catch (e: Exception) {
                _executionState.value = ExecutionState.Error(e.message ?: "Unknown error")
                reportToGitHub("Manual signal execution failed", e)
                kotlinx.coroutines.delay(3000)
                _executionState.value = ExecutionState.Idle
            }
        }
    }

    private suspend fun reportToGitHub(context: String, error: Throwable) {
        if (!gitHubIssueReporter.isConfigured()) return
        val title = "[${java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())}] $context"
        val body = buildString {
            appendLine("## Error Report")
            appendLine()
            appendLine("- **Context:** $context")
            appendLine("- **Timestamp:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
            appendLine("- **App Version:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            appendLine("- **Error:** ${error.message ?: "Unknown"}")
            appendLine()
            appendLine("### Stack Trace")
            appendLine("```")
            appendLine(error.stackTraceToString())
            appendLine("```")
        }
        gitHubIssueReporter.reportError(title, body)
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
        viewModelScope.launch {
            try {
                val config = configRepository.configFlow.first()
                repository.analyzeAllPairs(config)
            } catch (e: Exception) {
                _executionState.value = ExecutionState.Error(
                    "Analysis failed: ${e.message ?: "Unknown error"}"
                )
                kotlinx.coroutines.delay(3000)
                _executionState.value = ExecutionState.Idle
            }
        }
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

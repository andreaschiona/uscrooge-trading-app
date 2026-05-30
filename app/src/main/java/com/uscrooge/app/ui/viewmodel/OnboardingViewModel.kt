package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject

data class OnboardingState(
    val krakenApiKey: String = "",
    val krakenApiSecret: String = "",
    val alpacaApiKey: String = "",
    val alpacaApiSecret: String = "",
    val alpacaPaperTrading: Boolean = true,
    val selectedPairs: List<String> = listOf("BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR"),
    val riskPerTrade: Double = 0.25,
    val maxOpenPositions: Int = 3,
    val stopLossPercent: Double = 2.0,
    val takeProfitPercent: Double = 4.0,
    val currentStep: Int = 0,
    val isSaving: Boolean = false,
    val isCompleted: Boolean = false
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val configRepository: ConfigRepository
) : ViewModel() {

    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    private val _validationErrors = MutableStateFlow<Map<String, String>>(emptyMap())
    val validationErrors: StateFlow<Map<String, String>> = _validationErrors.asStateFlow()

    fun loadExistingConfig() {
        viewModelScope.launch {
            val config = configRepository.configFlow.first()
            _state.value = _state.value.copy(
                krakenApiKey = config.krakenApiKey,
                krakenApiSecret = config.krakenApiSecret,
                alpacaApiKey = config.alpacaApiKey,
                alpacaApiSecret = config.alpacaApiSecret,
                alpacaPaperTrading = config.alpacaPaperTrading,
                selectedPairs = config.tradingPairs,
                riskPerTrade = config.riskPerTrade,
                maxOpenPositions = config.maxOpenPositions,
                stopLossPercent = config.stopLossPercent,
                takeProfitPercent = config.takeProfitPercent
            )
        }
    }

    fun updateKrakenApiKey(value: String) {
        _state.value = _state.value.copy(krakenApiKey = value)
    }

    fun updateKrakenApiSecret(value: String) {
        _state.value = _state.value.copy(krakenApiSecret = value)
    }

    fun updateAlpacaApiKey(value: String) {
        _state.value = _state.value.copy(alpacaApiKey = value)
    }

    fun updateAlpacaApiSecret(value: String) {
        _state.value = _state.value.copy(alpacaApiSecret = value)
    }

    fun updatePaperTrading(value: Boolean) {
        _state.value = _state.value.copy(alpacaPaperTrading = value)
    }

    fun togglePair(pair: String) {
        val current = _state.value.selectedPairs.toMutableList()
        if (current.contains(pair)) {
            current.remove(pair)
        } else {
            current.add(pair)
        }
        _state.value = _state.value.copy(selectedPairs = current)
    }

    fun updateRiskPerTrade(value: Double) {
        _state.value = _state.value.copy(riskPerTrade = value)
    }

    fun updateMaxOpenPositions(value: Int) {
        _state.value = _state.value.copy(maxOpenPositions = value)
    }

    fun updateStopLossPercent(value: Double) {
        _state.value = _state.value.copy(stopLossPercent = value)
    }

    fun updateTakeProfitPercent(value: Double) {
        _state.value = _state.value.copy(takeProfitPercent = value)
    }

    fun nextStep() {
        val current = _state.value.currentStep
        if (current < 5) {
            _state.value = _state.value.copy(currentStep = current + 1)
        }
    }

    fun previousStep() {
        val current = _state.value.currentStep
        if (current > 0) {
            _state.value = _state.value.copy(currentStep = current - 1)
        }
    }

    fun skipAndComplete() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            val existing = configRepository.configFlow.first()
            configRepository.updateConfig(existing.copy(onboardingCompleted = true))
            _state.value = _state.value.copy(isSaving = false, isCompleted = true)
        }
    }

    fun completeOnboarding() {
        viewModelScope.launch {
            _state.value = _state.value.copy(isSaving = true)
            val s = _state.value
            val existing = configRepository.configFlow.first()
            val updated = existing.copy(
                onboardingCompleted = true,
                krakenApiKey = s.krakenApiKey.trim(),
                krakenApiSecret = s.krakenApiSecret.trim(),
                alpacaApiKey = s.alpacaApiKey.trim(),
                alpacaApiSecret = s.alpacaApiSecret.trim(),
                alpacaPaperTrading = s.alpacaPaperTrading,
                tradingPairs = s.selectedPairs,
                riskPerTrade = s.riskPerTrade,
                maxOpenPositions = s.maxOpenPositions,
                stopLossPercent = s.stopLossPercent,
                takeProfitPercent = s.takeProfitPercent
            )
            configRepository.updateConfig(updated)
            _state.value = _state.value.copy(isSaving = false, isCompleted = true)
        }
    }
}

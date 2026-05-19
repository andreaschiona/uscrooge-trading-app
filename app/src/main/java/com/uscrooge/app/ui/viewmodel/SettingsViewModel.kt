package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.worker.MarketAnalysisWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val configRepository: ConfigRepository
) : ViewModel() {

    private val _config = MutableStateFlow<TradingConfig?>(null)
    val config: StateFlow<TradingConfig?> = _config.asStateFlow()

    private val _saveState = MutableStateFlow<SaveState>(SaveState.Idle)
    val saveState: StateFlow<SaveState> = _saveState.asStateFlow()

    init {
        loadConfig()
    }

    private fun loadConfig() {
        viewModelScope.launch {
            configRepository.configFlow.collect { config ->
                _config.value = config
            }
        }
    }

    fun updateConfig(newConfig: TradingConfig) {
        viewModelScope.launch {
            try {
                _saveState.value = SaveState.Saving

                // Validate config
                val validation = newConfig.validate()
                if (validation.isFailure) {
                    _saveState.value = SaveState.Error(
                        validation.exceptionOrNull()?.message ?: "Validation failed"
                    )
                    return@launch
                }

                configRepository.updateConfig(newConfig)
                _saveState.value = SaveState.Success("Settings saved successfully")

                // Reset state after 2 seconds
                kotlinx.coroutines.delay(2000)
                _saveState.value = SaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SaveState.Error(e.message ?: "Failed to save settings")
            }
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                _saveState.value = SaveState.Saving
                configRepository.resetToDefaults()
                _saveState.value = SaveState.Success("Settings reset to defaults")

                kotlinx.coroutines.delay(2000)
                _saveState.value = SaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SaveState.Error(e.message ?: "Failed to reset settings")
            }
        }
    }
}

sealed class SaveState {
    object Idle : SaveState()
    object Saving : SaveState()
    data class Success(val message: String) : SaveState()
    data class Error(val message: String) : SaveState()
}

package com.uscrooge.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.qualifiers.ApplicationContext
import com.uscrooge.app.data.api.AlpacaApiClient
import com.uscrooge.app.data.api.KrakenApiClient
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.worker.MarketAnalysisWorker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject
import javax.net.ssl.SSLException

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val configRepository: ConfigRepository,
    private val gitHubIssueReporter: GitHubIssueReporter
) : ViewModel() {

    private enum class CredentialsValidationStatus {
        VALID,
        INVALID,
        UNVERIFIED
    }

    private data class CredentialsValidationResult(
        val status: CredentialsValidationStatus,
        val message: String? = null
    )

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

                val normalizedConfig = normalizeCredentials(newConfig)

                // Validate config
                val validation = normalizedConfig.validate()
                if (validation.isFailure) {
                    _saveState.value = SaveState.Error(
                        validation.exceptionOrNull()?.message ?: "Validation failed"
                    )
                    return@launch
                }

                val currentConfig = _config.value
                val credentialsChanged = currentConfig == null ||
                    currentConfig.krakenApiKey.trim() != normalizedConfig.krakenApiKey.trim() ||
                    currentConfig.krakenApiSecret.trim() != normalizedConfig.krakenApiSecret.trim() ||
                    currentConfig.apiTimeout != normalizedConfig.apiTimeout ||
                    currentConfig.alpacaApiKey.trim() != normalizedConfig.alpacaApiKey.trim() ||
                    currentConfig.alpacaApiSecret.trim() != normalizedConfig.alpacaApiSecret.trim() ||
                    currentConfig.githubToken.trim() != normalizedConfig.githubToken.trim()

                if (credentialsChanged) {
                    val credentialsValidation = validateKrakenCredentials(normalizedConfig)
                    when (credentialsValidation.status) {
                        CredentialsValidationStatus.INVALID -> {
                            _saveState.value = SaveState.Error(
                                credentialsValidation.message
                                    ?: "Kraken credentials validation failed"
                            )
                            return@launch
                        }

                        CredentialsValidationStatus.UNVERIFIED -> {
                        }

                        CredentialsValidationStatus.VALID -> Unit
                    }

                    // Also validate Alpaca credentials if stock trading is enabled
                    if (normalizedConfig.enableStockTrading &&
                        (normalizedConfig.alpacaApiKey.isNotBlank() || normalizedConfig.alpacaApiSecret.isNotBlank())) {
                        val alpacaValidation = validateAlpacaCredentials(normalizedConfig)
                        when (alpacaValidation.status) {
                            CredentialsValidationStatus.INVALID -> {
                                _saveState.value = SaveState.Error(
                                    alpacaValidation.message
                                        ?: "Alpaca credentials validation failed"
                                )
                                return@launch
                            }
                            CredentialsValidationStatus.UNVERIFIED -> {
                            }
                            CredentialsValidationStatus.VALID -> Unit
                        }
                    }
                }

                configRepository.updateConfig(normalizedConfig)
                gitHubIssueReporter.configureToken(normalizedConfig.githubToken)

                val intervalMinutes = (normalizedConfig.checkIntervalSeconds / 60).toLong()
                MarketAnalysisWorker.schedule(context, intervalMinutes)

                _saveState.value = SaveState.Success("Settings saved successfully")

                // Reset state after 2 seconds
                kotlinx.coroutines.delay(2000)
                _saveState.value = SaveState.Idle
            } catch (e: Exception) {
                _saveState.value = SaveState.Error(e.message ?: "Failed to save settings")
            }
        }
    }

    private fun normalizeCredentials(config: TradingConfig): TradingConfig {
        fun clean(value: String): String {
            return value
                .replace('’', '\'')
                .replace('\u00A0', ' ')
                .replace("\\s+".toRegex(), "")
                .trim()
        }

        return config.copy(
            krakenApiKey = clean(config.krakenApiKey),
            krakenApiSecret = clean(config.krakenApiSecret),
            alpacaApiKey = clean(config.alpacaApiKey),
            alpacaApiSecret = clean(config.alpacaApiSecret),
            githubToken = clean(config.githubToken)
        )
    }

    private suspend fun validateKrakenCredentials(config: TradingConfig): CredentialsValidationResult {
        val apiKey = config.krakenApiKey.trim()
        val apiSecret = config.krakenApiSecret.trim()

        if (apiKey.isEmpty() && apiSecret.isEmpty()) {
            return CredentialsValidationResult(CredentialsValidationStatus.VALID)
        }

        if (apiKey.isEmpty() || apiSecret.isEmpty()) {
            return CredentialsValidationResult(
                status = CredentialsValidationStatus.INVALID,
                message = "Both Kraken API key and secret are required"
            )
        }

        return try {
            val validationClient = KrakenApiClient(
                apiKey = apiKey,
                apiSecret = apiSecret,
                timeout = config.apiTimeout
            )

            try {
                val connectivityResult = validationClient.getServerTime()
                if (connectivityResult.isFailure) {
                    return CredentialsValidationResult(
                        status = CredentialsValidationStatus.UNVERIFIED,
                        message = mapKrakenError(connectivityResult.exceptionOrNull())
                    )
                }

                val balanceResult = validationClient.getAccountBalance()
                if (balanceResult.isSuccess) {
                    CredentialsValidationResult(CredentialsValidationStatus.VALID)
                } else {
                    val errorMessage = mapKrakenError(balanceResult.exceptionOrNull())
                    val isInvalid = isInvalidCredentialsError(balanceResult.exceptionOrNull()?.message)
                    CredentialsValidationResult(
                        status = if (isInvalid) CredentialsValidationStatus.INVALID else CredentialsValidationStatus.UNVERIFIED,
                        message = errorMessage
                    )
                }
            } finally {
                // Release the short-lived OkHttp dispatcher and connection pool
                // immediately instead of waiting for GC.
                validationClient.close()
            }
        } catch (e: Exception) {
            val isInvalid = isInvalidCredentialsError(e.message)
            CredentialsValidationResult(
                status = if (isInvalid) CredentialsValidationStatus.INVALID else CredentialsValidationStatus.UNVERIFIED,
                message = mapKrakenError(e)
            )
        }
    }

    private suspend fun validateAlpacaCredentials(config: TradingConfig): CredentialsValidationResult {
        val apiKey = config.alpacaApiKey.trim()
        val apiSecret = config.alpacaApiSecret.trim()

        if (apiKey.isEmpty() && apiSecret.isEmpty()) {
            return CredentialsValidationResult(CredentialsValidationStatus.VALID)
        }

        if (apiKey.isEmpty() || apiSecret.isEmpty()) {
            return CredentialsValidationResult(
                status = CredentialsValidationStatus.INVALID,
                message = "Both Alpaca API key and secret are required for stock trading"
            )
        }

        return try {
            val validationClient = AlpacaApiClient(
                apiKey = apiKey,
                apiSecret = apiSecret,
                isPaperTrading = config.alpacaPaperTrading,
                timeout = config.apiTimeout
            )

            try {
                val accountResult = validationClient.getAccountBalance()
                if (accountResult.isSuccess) {
                    CredentialsValidationResult(CredentialsValidationStatus.VALID)
                } else {
                    val errorMessage = mapAlpacaError(accountResult.exceptionOrNull())
                    val isInvalid = isInvalidAlpacaCredentialsError(accountResult.exceptionOrNull()?.message)
                    CredentialsValidationResult(
                        status = if (isInvalid) CredentialsValidationStatus.INVALID else CredentialsValidationStatus.UNVERIFIED,
                        message = errorMessage
                    )
                }
            } finally {
                validationClient.close()
            }
        } catch (e: Exception) {
            val isInvalid = isInvalidAlpacaCredentialsError(e.message)
            CredentialsValidationResult(
                status = if (isInvalid) CredentialsValidationStatus.INVALID else CredentialsValidationStatus.UNVERIFIED,
                message = mapAlpacaError(e)
            )
        }
    }

    private fun isInvalidAlpacaCredentialsError(rawMessage: String?): Boolean {
        val message = rawMessage?.trim().orEmpty()
        return message.contains("invalid key", ignoreCase = true) ||
            message.contains("invalid signature", ignoreCase = true) ||
            message.contains("unauthorized", ignoreCase = true) ||
            message.contains("forbidden", ignoreCase = true) ||
            message.contains("401", ignoreCase = true) ||
            message.contains("403", ignoreCase = true)
    }

    private fun mapAlpacaError(throwable: Throwable?): String {
        val message = throwable?.message?.trim().orEmpty()
        val errorType = throwable?.javaClass?.simpleName ?: "UnknownError"

        return when (throwable) {
            is UnknownHostException, is ConnectException ->
                "Unable to reach Alpaca. Check internet connection and try again."

            is SocketTimeoutException ->
                "Alpaca verification timed out. Try again or increase API timeout."

            is SSLException ->
                "Secure connection to Alpaca failed. Check device date/time and network."

            else -> mapAlpacaMessage(message, errorType)
        }
    }

    private fun mapAlpacaMessage(message: String, errorType: String): String {
        return when {
            message.contains("invalid key", ignoreCase = true) ||
                message.contains("invalid signature", ignoreCase = true) ->
                "Alpaca credentials are invalid. Check API key/secret and remove spaces or line breaks."

            message.contains("unauthorized", ignoreCase = true) ||
                message.contains("forbidden", ignoreCase = true) ->
                "Alpaca API key is valid but access is denied. Check permissions."

            message.isBlank() ->
                "Unable to verify Alpaca credentials. Check internet connection. (type: $errorType)"

            else -> "Alpaca credentials validation failed [$errorType]: $message"
        }
    }

    private fun isInvalidCredentialsError(rawMessage: String?): Boolean {
        val message = rawMessage?.trim().orEmpty()
        return message.contains("EAPI:Invalid key", ignoreCase = true) ||
            message.contains("EAPI:Invalid signature", ignoreCase = true) ||
            message.contains("EGeneral:Permission denied", ignoreCase = true)
    }

    private fun mapKrakenError(throwable: Throwable?): String {
        val message = throwable?.message?.trim().orEmpty()
        val errorType = throwable?.javaClass?.simpleName ?: "UnknownError"

        return when (throwable) {
            is UnknownHostException, is ConnectException ->
                "Unable to reach Kraken. Check internet connection and try again."

            is SocketTimeoutException ->
                "Kraken verification timed out. Try again or increase API timeout."

            is SSLException ->
                "Secure connection to Kraken failed. Check device date/time and network."

            else -> mapKrakenMessage(message, errorType)
        }
    }

    private fun mapKrakenMessage(message: String, errorType: String): String {
        return when {
            message.contains("EAPI:Invalid key", ignoreCase = true) ||
                message.contains("EAPI:Invalid signature", ignoreCase = true) ->
                "Kraken credentials are invalid. Check API key/secret and remove spaces or line breaks."

            message.contains("EAPI:Bad request", ignoreCase = true) ->
                "Kraken rejected the request format. Verify API secret and try again."

            message.contains("EAPI:Invalid nonce", ignoreCase = true) ->
                "Device time seems out of sync. Enable automatic date/time and retry."

            message.contains("EGeneral:Permission denied", ignoreCase = true) ->
                "Kraken API key is valid but missing required permissions (Query Funds)."

            message.isBlank() ->
                "Unable to verify Kraken credentials. Check internet and API permissions. (type: $errorType)"

            else -> "Kraken credentials validation failed [$errorType]: $message"
        }
    }

    fun resetToDefaults() {
        viewModelScope.launch {
            try {
                _saveState.value = SaveState.Saving
                configRepository.resetToDefaults()

                val defaultConfig = configRepository.configFlow.first()
                val intervalMinutes = (defaultConfig.checkIntervalSeconds / 60).toLong()
                MarketAnalysisWorker.schedule(context, intervalMinutes)

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
    data class Warning(val message: String) : SaveState()
    data class Error(val message: String) : SaveState()
}

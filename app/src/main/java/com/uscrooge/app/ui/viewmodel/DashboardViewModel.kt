package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.util.Log
import com.uscrooge.app.BuildConfig
import kotlin.math.cos
import kotlin.math.sin
import com.uscrooge.app.data.model.Portfolio
import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.SystemHealth
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.ui.screen.PieSlice
import com.uscrooge.app.ui.screen.presetColors
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
    private val configRepository: ConfigRepository,
    private val healthCheckRepository: HealthCheckRepository,
    private val orderExecutor: OrderExecutor,
    private val gitHubIssueReporter: GitHubIssueReporter
) : ViewModel() {

    private val _uiState = MutableStateFlow<DashboardUiState>(DashboardUiState.Loading)
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    val systemHealth: StateFlow<SystemHealth> = healthCheckRepository.systemHealth

    init {
        loadDashboard()
    }

    fun loadDashboard() {
        viewModelScope.launch {
            try {
                _uiState.value = DashboardUiState.Loading

                val config = configRepository.configFlow.first()

                // Sync positions from both brokers
                repository.syncOpenPositionsFromKraken(config)
                if (config.alpacaApiKey.isNotBlank()) {
                    repository.syncOpenPositionsFromAlpaca(config)
                }

                val positions = repository.getOpenPositions().first()
                val portfolio = repository.getPortfolio(config)
                _uiState.value = DashboardUiState.Success(portfolio, positions)
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.message ?: "Unknown error")
                gitHubIssueReporter.reportError(
                    title = "Dashboard load failed",
                    body = buildString {
                        appendLine("## Contesto Errore")
                        appendLine()
                        appendLine("**Errore:** ${e.message}")
                        appendLine("**Versione App:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                        appendLine("**Timestamp:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                        appendLine()
                        appendLine("---")
                        appendLine()
                        appendLine("_Issue creata automaticamente dal sistema di error handling._")
                    },
                    labels = listOf("bug", "auto-reported", "api")
                )
            }
        }
    }

    fun refreshData() {
        loadDashboard()
    }

    fun closePosition(position: Position) {
        viewModelScope.launch {
            orderExecutor.closePosition(position)
                .onFailure { e ->
                    Log.e("DashboardViewModel", "Failed to close position: ${e.message}", e)
                    gitHubIssueReporter.reportError(
                        title = "Position close failed",
                        body = buildString {
                            appendLine("## Contesto Errore")
                            appendLine()
                            appendLine("**Errore:** ${e.message}")
                            appendLine("**Pair:** ${position.pair}")
                            appendLine("**Versione App:** ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
                            appendLine("**Timestamp:** ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())}")
                            appendLine()
                            appendLine("---")
                            appendLine()
                            appendLine("_Issue creata automaticamente dal sistema di error handling._")
                        },
                        labels = listOf("bug", "auto-reported", "trading")
                    )
                }
            loadDashboard()
        }
    }

    fun generateEquityCurve(portfolio: Portfolio): List<Pair<Float, Float>> {
        val positions = portfolio.positions
        if (positions.isEmpty()) {
            val nowHours = System.currentTimeMillis() / 3600000f
            return listOf(
                (nowHours - 24f) to portfolio.availableBalance.toFloat(),
                nowHours to portfolio.currentValue.toFloat()
            )
        }

        val sorted = positions.sortedBy { it.openedAt }
        val points = mutableListOf<Pair<Float, Float>>()
        val nowMs = System.currentTimeMillis()
        val nowHours = nowMs / 3600000f

        val startMs = sorted.first().openedAt - 86400000L
        points.add((startMs / 3600000f) to portfolio.availableBalance.toFloat())

        var runningValue = portfolio.availableBalance
        sorted.forEach { pos ->
            runningValue += pos.totalInvested
            points.add((pos.openedAt / 3600000f) to runningValue.toFloat())
        }

        points.add(nowHours to portfolio.currentValue.toFloat())

        return points
    }

    fun generateAllocationSlices(portfolio: Portfolio): List<PieSlice> {
        val positions = portfolio.positions
        if (positions.isEmpty()) return emptyList()

        val totalValue = positions.sumOf { it.currentValue } + portfolio.availableBalance
        val slices = mutableListOf<PieSlice>()

        positions.forEachIndexed { index, pos ->
            slices.add(
                PieSlice(
                    label = pos.pair,
                    value = pos.currentValue,
                    color = presetColors[index % presetColors.size]
                )
            )
        }

        if (portfolio.availableBalance > 0) {
            slices.add(
                PieSlice(
                    label = "Available",
                    value = portfolio.availableBalance,
                    color = presetColors[positions.size % presetColors.size]
                )
            )
        }

        return slices
    }

    fun generateDrawdownData(portfolio: Portfolio): List<Float> {
        val values = portfolio.positions.map { it.currentValue }
        if (values.isEmpty()) return listOf(0f)

        var peak = values.first()
        return values.map { current ->
            if (current > peak) peak = current
            if (peak > 0f) ((current - peak) / peak * 100).toFloat() else 0f
        }
    }

    fun generatePriceHistory(position: Position): List<Float> {
        val days = 30
        val basePrice = if (position.averageEntryPrice > 0.0) position.averageEntryPrice else position.currentPrice
        return (0 until days).map { i ->
            val progress = i.toFloat() / days
            val drift = (position.currentPrice - basePrice) * progress
            val noise = (
                sin(i * 0.5) * 0.01 +
                sin(i * 0.13) * 0.005 +
                cos(i * 0.07) * 0.008
            ) * basePrice
            (basePrice + drift + noise).toFloat()
        }
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

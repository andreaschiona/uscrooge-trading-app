package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.model.Portfolio
import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.SystemHealth
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.HealthCheckRepository
import com.uscrooge.app.data.repository.TradingRepository
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
    private val healthCheckRepository: HealthCheckRepository
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
                if (config.enableStockTrading && config.alpacaApiKey.isNotBlank()) {
                    repository.syncOpenPositionsFromAlpaca(config)
                }

                val positions = repository.getOpenPositions().first()
                val portfolio = repository.getPortfolio(config)
                _uiState.value = DashboardUiState.Success(portfolio, positions)
            } catch (e: Exception) {
                _uiState.value = DashboardUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun refreshData() {
        loadDashboard()
    }

    fun generateEquityCurve(portfolio: Portfolio): List<Pair<Float, Float>> {
        val positions = portfolio.positions
        if (positions.isEmpty()) {
            return listOf(0f to portfolio.currentValue.toFloat())
        }

        val sorted = positions.sortedBy { it.openedAt }
        val points = mutableListOf<Pair<Float, Float>>()
        var cumulativeInvested = 0.0
        var cumulativeValue = portfolio.availableBalance

        points.add(0f to cumulativeValue.toFloat())

        sorted.forEachIndexed { index, pos ->
            cumulativeInvested += pos.totalInvested
            cumulativeValue += pos.currentValue
            val timeIndex = (index + 1).toFloat() / sorted.size.toFloat() * 100f
            points.add(timeIndex to cumulativeValue.toFloat())
        }

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
            val variation = (Math.sin(i * 0.3) * 0.02 + (position.currentPrice - basePrice) / basePrice * (i.toFloat() / days))
            (basePrice * (1 + variation)).toFloat()
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

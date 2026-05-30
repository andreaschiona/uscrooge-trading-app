package com.uscrooge.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.uscrooge.app.data.local.TradeJournalDao
import com.uscrooge.app.data.model.TradeJournalEntry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TradeJournalViewModel @Inject constructor(
    private val tradeJournalDao: TradeJournalDao
) : ViewModel() {

    private val _entries = MutableStateFlow<List<TradeJournalEntry>>(emptyList())
    val entries: StateFlow<List<TradeJournalEntry>> = _entries.asStateFlow()

    private val _stats = MutableStateFlow<TradeJournalStats?>(null)
    val stats: StateFlow<TradeJournalStats?> = _stats.asStateFlow()

    private val _selectedPair = MutableStateFlow<String?>(null)
    val selectedPair: StateFlow<String?> = _selectedPair.asStateFlow()

    private var allEntries: List<TradeJournalEntry> = emptyList()

    init {
        loadEntries()
    }

    private fun loadEntries() {
        viewModelScope.launch {
            tradeJournalDao.getAllEntries().collect { entries ->
                allEntries = entries
                applyFilter()
            }
        }
    }

    fun filterByPair(pair: String?) {
        _selectedPair.value = pair
        applyFilter()
    }

    private fun applyFilter() {
        val pair = _selectedPair.value
        val filtered = if (pair != null) {
            allEntries.filter { it.pair == pair }
        } else {
            allEntries
        }
        _entries.value = filtered
        computeStats(filtered)
    }

    fun generateCsv(): String {
        val entries = _entries.value
        if (entries.isEmpty()) return ""

        val header = "Date,Pair,Side,Entry,Exit,Size,PnL,PnL%,ExitReason,Fees,Duration"
        val rows = entries.joinToString("\n") { e ->
            val date = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date(e.exitTime))
            val side = if (e.side.name == "BUY") "Buy" else "Sell"
            val exitReason = e.exitReason.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() }
            val durationHours = e.duration / 3600000.0
            "$date,${e.pair},$side,${e.entryPrice},${e.exitPrice},${e.amount},${e.profitLoss},${e.profitLossPercent}%,$exitReason,${e.fee},${String.format("%.1f", durationHours)}h"
        }
        return "$header\n$rows"
    }

    private fun computeStats(entries: List<TradeJournalEntry>) {
        if (entries.isEmpty()) {
            _stats.value = null
            return
        }
        val totalPnL = entries.sumOf { it.profitLoss }
        val wins = entries.filter { it.profitLoss > 0 }
        val losses = entries.filter { it.profitLoss < 0 }
        val winRate = if (entries.isNotEmpty()) wins.size.toDouble() / entries.size else 0.0
        val avgWin = if (wins.isNotEmpty()) wins.map { it.profitLoss }.average() else 0.0
        val avgLoss = if (losses.isNotEmpty()) losses.map { it.profitLoss }.average() else 0.0
        val profitFactor = if (avgLoss != 0.0) kotlin.math.abs(avgWin / avgLoss) else 0.0
        val avgDuration = if (entries.isNotEmpty()) entries.map { it.duration }.average().toLong() else 0L

        _stats.value = TradeJournalStats(
            totalPnL = totalPnL,
            tradeCount = entries.size,
            winCount = wins.size,
            lossCount = losses.size,
            winRate = winRate,
            profitFactor = profitFactor,
            avgDuration = avgDuration,
            pairs = allEntries.map { it.pair }.distinct()
        )
    }
}

data class TradeJournalStats(
    val totalPnL: Double,
    val tradeCount: Int,
    val winCount: Int,
    val lossCount: Int,
    val winRate: Double,
    val profitFactor: Double,
    val avgDuration: Long,
    val pairs: List<String>
)

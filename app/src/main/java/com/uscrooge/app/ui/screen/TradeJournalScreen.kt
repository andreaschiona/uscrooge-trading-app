package com.uscrooge.app.ui.screen

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.uscrooge.app.data.model.TradeJournalEntry
import com.uscrooge.app.ui.viewmodel.TradeJournalStats
import com.uscrooge.app.ui.viewmodel.TradeJournalViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun TradeJournalScreen(
    viewModel: TradeJournalViewModel,
    modifier: Modifier = Modifier
) {
    val entries by viewModel.entries.collectAsState()
    val stats by viewModel.stats.collectAsState()
    val selectedPair by viewModel.selectedPair.collectAsState()
    val context = LocalContext.current
    var showExportToast by remember { mutableStateOf(false) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("text/csv")
    ) { uri: Uri? ->
        if (uri != null) {
            try {
                val csv = viewModel.generateCsv()
                context.contentResolver.openOutputStream(uri)?.use { outputStream ->
                    outputStream.write(csv.toByteArray(Charsets.UTF_8))
                }
                showExportToast = true
            } catch (e: Exception) {
                android.util.Log.e("TradeJournalScreen", "Export failed: ${e.message}", e)
                exportError = e.message ?: "Export failed"
            }
        }
    }

    if (showExportToast) {
        LaunchedEffect(Unit) {
            kotlinx.coroutines.delay(2000)
            showExportToast = false
        }
    }
    if (exportError != null) {
        LaunchedEffect(exportError) {
            kotlinx.coroutines.delay(3000)
            exportError = null
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Trade Journal",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            if (entries.isNotEmpty()) {
                TextButton(onClick = { exportLauncher.launch("trade_journal.csv") }) {
                    Icon(Icons.Default.FileDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Export CSV")
                }
            }
        }

        if (showExportToast) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Export completed successfully",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFF4CAF50)
            )
        }
        if (exportError != null) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "Export failed: $exportError",
                style = MaterialTheme.typography.bodySmall,
                color = Color(0xFFE57373)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        stats?.let { s ->
            JournalStatsCard(s, selectedPair) { pair ->
                viewModel.filterByPair(pair)
            }
            Spacer(modifier = Modifier.height(12.dp))
        }

        if (entries.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.Default.DateRange,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (selectedPair != null) "No closed trades for $selectedPair" else "No closed trades yet",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(entries) { entry ->
                    JournalEntryCard(entry)
                }
            }
        }
    }
}

@Composable
fun JournalStatsCard(
    stats: TradeJournalStats,
    selectedPair: String?,
    onPairFilter: (String?) -> Unit
) {
    var showFilterMenu by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Performance Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Box {
                    TextButton(onClick = { showFilterMenu = true }) {
                        Icon(Icons.Default.FilterList, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(selectedPair ?: "All Pairs")
                    }
                    DropdownMenu(
                        expanded = showFilterMenu,
                        onDismissRequest = { showFilterMenu = false }
                    ) {
                        DropdownMenuItem(
                            text = { Text("All Pairs") },
                            onClick = {
                                onPairFilter(null)
                                showFilterMenu = false
                            }
                        )
                        stats.pairs.forEach { pair ->
                            DropdownMenuItem(
                                text = { Text(pair) },
                                onClick = {
                                    onPairFilter(pair)
                                    showFilterMenu = false
                                }
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            Divider()
            Spacer(modifier = Modifier.height(12.dp))

            val pnlColor = if (stats.totalPnL >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("Total P/L", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${if (stats.totalPnL >= 0) "+" else ""}€${String.format("%.2f", stats.totalPnL)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("Win Rate", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${String.format("%.1f", stats.winRate * 100)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Trades", "${stats.tradeCount}")
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Wins/Losses", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        text = "${stats.winCount} / ${stats.lossCount}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                InfoItem("Profit Factor", String.format("%.2f", stats.profitFactor))
            }

            Spacer(modifier = Modifier.height(4.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Avg Duration", formatDuration(stats.avgDuration))
            }
        }
    }
}

@Composable
fun JournalEntryCard(entry: TradeJournalEntry) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (entry.side) {
                        com.uscrooge.app.data.model.OrderSide.BUY -> Icons.Default.TrendingUp
                        com.uscrooge.app.data.model.OrderSide.SELL -> Icons.Default.TrendingDown
                    }
                    val iconColor = when (entry.side) {
                        com.uscrooge.app.data.model.OrderSide.BUY -> Color(0xFF4CAF50)
                        com.uscrooge.app.data.model.OrderSide.SELL -> Color(0xFFE57373)
                    }
                    Icon(icon, contentDescription = null, tint = iconColor, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = entry.pair,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                }

                val pnlColor = if (entry.profitLoss >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                Text(
                    text = "${if (entry.profitLoss >= 0) "+" else ""}€${String.format("%.2f", entry.profitLoss)}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = pnlColor
                )
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Entry", "€${String.format("%.2f", entry.entryPrice)}")
                InfoItem("Exit", "€${String.format("%.2f", entry.exitPrice)}")
                InfoItem("Amount", String.format("%.6f", entry.amount))
                InfoItem("P/L%", "${if (entry.profitLossPercent >= 0) "+" else ""}${String.format("%.2f", entry.profitLossPercent)}%")
            }

            Spacer(modifier = Modifier.height(4.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Duration", formatDuration(entry.duration))
                InfoItem("Exit", entry.exitReason.replace("_", " ").lowercase().replaceFirstChar { it.uppercase() })
                InfoItem("Date", formatTimestamp(entry.exitTime))
            }
        }
    }
}

fun formatDuration(millis: Long): String {
    val hours = millis / 3600000
    val minutes = (millis % 3600000) / 60000
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

package com.uscrooge.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Velocity
import com.uscrooge.app.data.model.AnalysisLog
import com.uscrooge.app.data.model.AnalysisLogEntry
import com.uscrooge.app.data.model.SignalStatus
import com.uscrooge.app.data.model.SignalType
import com.uscrooge.app.data.model.TradingSignal
import com.uscrooge.app.ui.viewmodel.ExecutionState
import com.uscrooge.app.ui.viewmodel.SignalsUiState
import com.uscrooge.app.ui.viewmodel.SignalsViewModel
import java.text.SimpleDateFormat
import java.util.*

@Composable
fun SignalsScreen(
    viewModel: SignalsViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val executionState by viewModel.executionState.collectAsState()
    val analysisLog by viewModel.lastAnalysisLog.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableStateOf(0f) }
    val refreshThreshold = 120f

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            viewModel.refreshSignals()
            kotlinx.coroutines.delay(800)
            isRefreshing = false
            pullOffset = 0f
        }
    }

    val nestedScrollConnection = remember {
        object : androidx.compose.ui.input.nestedscroll.NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < 0f && pullOffset > 0f) {
                    pullOffset = maxOf(0f, pullOffset + available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: androidx.compose.ui.input.nestedscroll.NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y > 0f && !isRefreshing) {
                    pullOffset = minOf(refreshThreshold, pullOffset + available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override suspend fun onPreFling(available: Velocity): Velocity {
                if (pullOffset >= refreshThreshold && !isRefreshing) {
                    isRefreshing = true
                }
                return Velocity.Zero
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .padding(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pullOffset > 0f || isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth(),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Show execution state snackbar
            when (val state = executionState) {
                is ExecutionState.Success -> {
                    Snackbar(
                        modifier = Modifier.padding(bottom = 8.dp)
                    ) {
                        Text(state.message)
                    }
                }
                is ExecutionState.Error -> {
                    Snackbar(
                        modifier = Modifier.padding(bottom = 8.dp),
                        containerColor = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer
                    ) {
                        Text(state.message)
                    }
                }
                else -> {}
            }

            // Last analysis log
            analysisLog?.let { log ->
                AnalysisLogCard(log)
                Spacer(modifier = Modifier.height(12.dp))
            }

            when (val state = uiState) {
                is SignalsUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is SignalsUiState.Success -> {
                    SignalsContent(
                        signals = state.signals,
                        executionState = executionState,
                        onExecute = { viewModel.executeSignal(it) },
                        onIgnore = { viewModel.ignoreSignal(it) },
                        onRefresh = { isRefreshing = true }
                    )
                }

                is SignalsUiState.Error -> {
                    ErrorView(
                        message = state.message,
                        onRetry = { isRefreshing = true }
                    )
                }
            }
        }
    }
}

@Composable
fun SignalsContent(
    signals: List<TradingSignal>,
    executionState: ExecutionState,
    onExecute: (TradingSignal) -> Unit,
    onIgnore: (TradingSignal) -> Unit,
    onRefresh: () -> Unit
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Trading Signals",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onRefresh) {
                Icon(Icons.Default.Refresh, contentDescription = "Refresh")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        val pendingSignals = signals.filter { it.status == SignalStatus.PENDING }
        val otherSignals = signals.filter { it.status != SignalStatus.PENDING }

        if (signals.isEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No signals available",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                if (pendingSignals.isNotEmpty()) {
                    item {
                        Text(
                            text = "Pending (${pendingSignals.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }

                    items(pendingSignals) { signal ->
                        SignalCard(
                            signal = signal,
                            isExecuting = executionState is ExecutionState.Executing &&
                                    (executionState as ExecutionState.Executing).signalId == signal.id,
                            onExecute = { onExecute(signal) },
                            onIgnore = { onIgnore(signal) }
                        )
                    }

                    item { Spacer(modifier = Modifier.height(8.dp)) }
                }

                if (otherSignals.isNotEmpty()) {
                    item {
                        Text(
                            text = "History",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    items(otherSignals) { signal ->
                        SignalCard(
                            signal = signal,
                            isExecuting = false,
                            onExecute = null,
                            onIgnore = null
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SignalCard(
    signal: TradingSignal,
    isExecuting: Boolean,
    onExecute: (() -> Unit)?,
    onIgnore: (() -> Unit)?
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        colors = CardDefaults.cardColors(
            containerColor = when (signal.status) {
                SignalStatus.PENDING -> MaterialTheme.colorScheme.surface
                SignalStatus.EXECUTED -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                SignalStatus.IGNORED -> MaterialTheme.colorScheme.surfaceVariant
                SignalStatus.FAILED -> MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    val icon = when (signal.type) {
                        SignalType.BUY -> Icons.Default.TrendingUp
                        SignalType.SELL -> Icons.Default.TrendingDown
                        SignalType.HOLD -> Icons.Default.Remove
                    }
                    val iconColor = when (signal.type) {
                        SignalType.BUY -> Color(0xFF4CAF50)
                        SignalType.SELL -> Color(0xFFE57373)
                        SignalType.HOLD -> Color.Gray
                    }

                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconColor,
                        modifier = Modifier.size(28.dp)
                    )

                    Spacer(modifier = Modifier.width(8.dp))

                    Column {
                        Text(
                            text = signal.pair,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = signal.type.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = iconColor
                        )
                    }
                }

                SignalStrengthBadge(signal.strength)
            }

            Spacer(modifier = Modifier.height(12.dp))

            Divider()

            Spacer(modifier = Modifier.height(12.dp))

            // Price information
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Current Price", "€${String.format("%.2f", signal.currentPrice)}")
                InfoItem("Suggested", "€${String.format("%.2f", signal.suggestedPrice)}")
                InfoItem("Amount", "€${String.format("%.2f", signal.suggestedAmount)}")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Stop Loss", "€${String.format("%.2f", signal.stopLoss)}")
                InfoItem("Take Profit", "€${String.format("%.2f", signal.takeProfit)}")
                InfoItem("R/R Ratio", String.format("%.2f", signal.riskRewardRatio))
            }

            // Reasons
            if (signal.getReasonsList().isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Reasons:",
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                signal.getReasonsList().take(3).forEach { reason ->
                    Text(
                        text = "• $reason",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // Timestamp and status
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatTimestamp(signal.timestamp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                StatusBadge(signal.status)
            }

            // Action buttons for pending signals
            if (signal.status == SignalStatus.PENDING && onExecute != null && onIgnore != null) {
                Spacer(modifier = Modifier.height(12.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = onExecute,
                        modifier = Modifier.weight(1f),
                        enabled = !isExecuting,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (signal.type == SignalType.BUY)
                                Color(0xFF4CAF50) else Color(0xFFE57373)
                        )
                    ) {
                        if (isExecuting) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                        } else {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Execute")
                        }
                    }

                    OutlinedButton(
                        onClick = onIgnore,
                        modifier = Modifier.weight(1f),
                        enabled = !isExecuting
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Ignore")
                    }
                }
            }
        }
    }
}

@Composable
fun SignalStrengthBadge(strength: Double) {
    val percent = (strength * 100).toInt()
    val color = when {
        strength >= 0.8 -> Color(0xFF4CAF50)
        strength >= 0.65 -> Color(0xFFFF9800)
        else -> Color(0xFFE57373)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = "$percent%",
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

@Composable
fun StatusBadge(status: SignalStatus) {
    val (text, color) = when (status) {
        SignalStatus.PENDING -> "Pending" to Color(0xFFFF9800)
        SignalStatus.EXECUTING -> "Executing" to Color(0xFF2196F3)
        SignalStatus.EXECUTED -> "Executed" to Color(0xFF4CAF50)
        SignalStatus.IGNORED -> "Ignored" to Color.Gray
        SignalStatus.FAILED -> "Failed" to Color(0xFFE57373)
        SignalStatus.EXPIRED -> "Expired" to Color.Gray
        SignalStatus.MISSED -> "Missed" to Color(0xFF9E9E9E)
    }

    Surface(
        shape = MaterialTheme.shapes.small,
        color = color.copy(alpha = 0.2f)
    ) {
        Text(
            text = text,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

fun formatTimestamp(timestamp: Long): String {
    val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
    return sdf.format(Date(timestamp))
}

@Composable
fun AnalysisLogCard(log: AnalysisLog) {
    var expanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (log.errorCount > 0)
                MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            else
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
        )
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
                Column {
                    Text(
                        text = "Last Analysis",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = formatTimestamp(log.timestamp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (log.errorCount > 0) {
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            color = Color(0xFFE57373).copy(alpha = 0.2f)
                        ) {
                            Text(
                                text = "${log.errorCount} errors",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE57373)
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        color = Color(0xFF4CAF50).copy(alpha = 0.2f)
                    ) {
                        Text(
                            text = "${log.successCount}/${log.totalCount} OK",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF4CAF50)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    IconButton(
                        onClick = { expanded = !expanded },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                            contentDescription = if (expanded) "Collapse" else "Expand",
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(8.dp))
                Divider()
                Spacer(modifier = Modifier.height(8.dp))

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    log.entries.forEach { entry ->
                        AnalysisLogEntryRow(entry)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AnalysisLogEntryRow(entry: AnalysisLogEntry) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = if (entry.isSuccess) Icons.Default.CheckCircle else Icons.Default.Error,
                    contentDescription = null,
                    tint = if (entry.isSuccess) Color(0xFF4CAF50) else Color(0xFFE57373),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = entry.pair,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(6.dp))
                BrokerBadge(entry.broker)
            }

            if (entry.isSuccess) {
                val info = when (entry.signalType) {
                    SignalType.BUY -> "BUY ${((entry.strength ?: 0.0) * 100).toInt()}%"
                    SignalType.SELL -> "SELL ${((entry.strength ?: 0.0) * 100).toInt()}%"
                    SignalType.HOLD -> "HOLD"
                    null -> entry.errorMessage ?: "No signal"
                }
                Text(
                    text = info,
                    style = MaterialTheme.typography.bodySmall,
                    color = when (entry.signalType) {
                        SignalType.BUY -> Color(0xFF4CAF50)
                        SignalType.SELL -> Color(0xFFE57373)
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                )
            } else {
                var expanded by remember { mutableStateOf(false) }
                Column {
                    Text(
                        text = entry.errorMessage ?: "Error",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFE57373),
                        maxLines = if (expanded) Int.MAX_VALUE else 1
                    )
                    if ((entry.errorMessage?.length ?: 0) > 50) {
                        TextButton(
                            onClick = { expanded = !expanded },
                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp),
                            modifier = Modifier.height(24.dp)
                        ) {
                            Text(
                                text = if (expanded) "Show less" else "Show more",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE57373)
                            )
                        }
                    }
                }
            }
        }

        // Show detailed technical indicators for successful entries
        if (entry.isSuccess) {
            val detailColor = MaterialTheme.colorScheme.onSurfaceVariant
            val detailStyle = MaterialTheme.typography.labelSmall
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = buildString {
                    entry.currentPrice?.let { append("Price: €${String.format("%.2f", it)}") }
                    entry.rsiValue?.let { append(" | RSI: ${String.format("%.1f", it)} (${entry.rsiSignal})") }
                    entry.macdHistogram?.let { append(" | MACD: ${String.format("%.4f", it)} (${entry.macdSignal})") }
                },
                style = detailStyle,
                color = detailColor
            )
            Text(
                text = buildString {
                    entry.trend?.let { append("Trend: $it") }
                    entry.volumeRatio?.let { append(" | Vol: ${String.format("%.2f", it)}x") }
                    entry.candlestickPattern?.let { append(" | Pattern: $it") }
                    entry.availableBalance?.let { append(" | Avail: €${String.format("%.2f", it)}") }
                },
                style = detailStyle,
                color = detailColor
            )
        }
    }
}

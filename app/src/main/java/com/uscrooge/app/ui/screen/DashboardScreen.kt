package com.uscrooge.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.uscrooge.app.data.model.BrokerHealth
import com.uscrooge.app.data.model.BrokerHealthStatus
import com.uscrooge.app.data.model.FearGreedHealth
import com.uscrooge.app.data.model.Position
import com.uscrooge.app.data.model.SystemHealth
import com.uscrooge.app.data.model.Portfolio
import com.uscrooge.app.ui.viewmodel.DashboardUiState
import com.uscrooge.app.ui.viewmodel.DashboardViewModel
import kotlinx.coroutines.delay

@Composable
fun DashboardScreen(
    viewModel: DashboardViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    var isRefreshing by remember { mutableStateOf(false) }
    var pullOffset by remember { mutableStateOf(0f) }
    val refreshThreshold = 120f

    LaunchedEffect(isRefreshing) {
        if (isRefreshing) {
            viewModel.refreshData()
            delay(800)
            isRefreshing = false
            pullOffset = 0f
        }
    }

    val nestedScrollConnection = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
                if (available.y < 0f && pullOffset > 0f) {
                    pullOffset = maxOf(0f, pullOffset + available.y)
                    return androidx.compose.ui.geometry.Offset(0f, available.y)
                }
                return androidx.compose.ui.geometry.Offset.Zero
            }

            override fun onPostScroll(consumed: androidx.compose.ui.geometry.Offset, available: androidx.compose.ui.geometry.Offset, source: NestedScrollSource): androidx.compose.ui.geometry.Offset {
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
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            if (pullOffset > 0f || isRefreshing) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp),
                    color = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.height(4.dp))
            }

            when (val state = uiState) {
                is DashboardUiState.Loading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator()
                    }
                }

                is DashboardUiState.Success -> {
                    DashboardContent(
                        portfolio = state.portfolio,
                        positions = state.positions,
                        viewModel = viewModel,
                        onRefresh = {
                            isRefreshing = true
                        }
                    )
                }

                is DashboardUiState.Error -> {
                    ErrorView(
                        message = state.message,
                        onRetry = {
                            isRefreshing = true
                            viewModel.refreshData()
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun DashboardContent(
    portfolio: Portfolio,
    positions: List<Position>,
    viewModel: DashboardViewModel,
    onRefresh: () -> Unit
) {
    val equityData = remember(portfolio) { viewModel.generateEquityCurve(portfolio) }
    val allocationSlices = remember(portfolio) { viewModel.generateAllocationSlices(portfolio) }
    val drawdownData = remember(portfolio) { viewModel.generateDrawdownData(portfolio) }
    val systemHealth by viewModel.systemHealth.collectAsState()
    var showHealthDialog by remember { mutableStateOf(false) }

    if (showHealthDialog) {
        HealthDetailsDialog(
            systemHealth = systemHealth,
            onDismiss = { showHealthDialog = false }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(16.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Portfolio",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    SystemHealthIndicator(systemHealth = systemHealth, onClick = { showHealthDialog = true })
                    FilledTonalButton(onClick = onRefresh) {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh", modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Refresh")
                    }
                }
            }
        }

        item {
            PortfolioSummaryCard(portfolio)
        }

        if (positions.isNotEmpty()) {
            item {
                EquityCurveChart(
                    dataPoints = equityData,
                    modifier = Modifier.fillMaxWidth(),
                    label = "Equity Curve"
                )
            }

            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                ) {
                    PortfolioAllocationPie(
                        slices = allocationSlices,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )
                }
            }

            if (drawdownData.any { it < 0f }) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
                    ) {
                        DrawdownChart(
                            dataPoints = drawdownData,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp)
                        )
                    }
                }
            }
        }

        item {
            Text(
                text = "Open Positions",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        if (positions.isEmpty()) {
            item {
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
                            text = "No open positions",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        } else {
            items(positions) { position ->
                PositionCard(
                    position = position,
                    priceHistory = viewModel.generatePriceHistory(position),
                    onClose = { viewModel.closePosition(position) }
                )
            }
        }
    }
}

@Composable
fun PortfolioSummaryCard(portfolio: Portfolio) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Total Value",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "€${String.format("%.2f", portfolio.currentValue)}",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "P/L",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    val pnlColor = if (portfolio.totalPnL >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                    Text(
                        text = "${if (portfolio.totalPnL >= 0) "+" else ""}€${String.format("%.2f", portfolio.totalPnL)}",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                    Text(
                        text = "${if (portfolio.totalPnLPercent >= 0) "+" else ""}${String.format("%.2f", portfolio.totalPnLPercent)}%",
                        style = MaterialTheme.typography.bodyMedium,
                        color = pnlColor
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Divider()

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text(
                        text = "Invested",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "€${String.format("%.2f", portfolio.totalInvested)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                }

                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "Available",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "€${String.format("%.2f", portfolio.availableBalance)}",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = portfolio.availableBalanceSource,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PositionCard(
    position: Position,
    priceHistory: List<Float>,
    onClose: () -> Unit = {}
) {
    var showConfirmDialog by remember { mutableStateOf(false) }

    if (showConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showConfirmDialog = false },
            title = { Text("Close Position") },
            text = {
                Text("Are you sure you want to close ${position.pair}? This will place a market sell order.")
            },
            confirmButton = {
                Button(
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE57373)),
                    onClick = {
                        showConfirmDialog = false
                        onClose()
                    }
                ) {
                    Text("Close")
                }
            },
            dismissButton = {
                TextButton(onClick = { showConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.TrendingUp,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = position.pair,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    BrokerBadge(position.broker)
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    val pnlColor = if (position.unrealizedPnL >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                    Text(
                        text = "${if (position.unrealizedPnL >= 0) "+" else ""}${String.format("%.2f", position.unrealizedPnLPercent)}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = pnlColor
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    IconButton(
                        onClick = { showConfirmDialog = true },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close position",
                            tint = Color(0xFFE57373),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Amount", String.format("%.6f", position.amount))
                InfoItem("Entry", "€${String.format("%.2f", position.averageEntryPrice)}")
                InfoItem("Current", "€${String.format("%.2f", position.currentPrice)}")
            }

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                InfoItem("Invested", "€${String.format("%.2f", position.totalInvested)}")
                InfoItem("Value", "€${String.format("%.2f", position.currentValue)}")
                val pnlColor = if (position.unrealizedPnL >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                Column {
                    Text(
                        text = "P/L",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${if (position.unrealizedPnL >= 0) "+" else ""}€${String.format("%.2f", position.unrealizedPnL)}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = pnlColor
                    )
                }
            }

            if (priceHistory.size >= 2) {
                Spacer(modifier = Modifier.height(8.dp))
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
                    ),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = "Price History",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        MiniPriceChart(
                            dataPoints = priceHistory,
                            modifier = Modifier.fillMaxWidth(),
                            lineColor = if (position.unrealizedPnL >= 0) Color(0xFF4CAF50) else Color(0xFFE57373)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun BrokerBadge(broker: String?) {
    if (broker == null) return
    val (label, color) = when (broker) {
        "STOCK" -> "STOCK" to Color(0xFF2196F3)
        "CRYPTO" -> "CRYPTO" to Color(0xFFFF9800)
        "Alpaca" -> "STOCK" to Color(0xFF2196F3)
        "Kraken" -> "CRYPTO" to Color(0xFFFF9800)
        else -> return
    }
    Text(
        text = label,
        style = MaterialTheme.typography.labelSmall,
        color = color,
        modifier = Modifier
            .border(0.5.dp, color, RoundedCornerShape(4.dp))
            .padding(horizontal = 4.dp, vertical = 1.dp)
    )
}

@Composable
fun InfoItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold
        )
    }
}

@Composable
fun SystemHealthIndicator(systemHealth: SystemHealth, onClick: () -> Unit) {
    val allOnline = systemHealth.brokers.values.all { it.status == BrokerHealthStatus.ONLINE }
    val anyOffline = systemHealth.brokers.values.any { it.status == BrokerHealthStatus.OFFLINE }
    val indicatorColor = when {
        systemHealth.brokers.isEmpty() -> Color.Gray
        anyOffline -> Color(0xFFE57373)
        !allOnline -> Color(0xFFFFD54F)
        else -> Color(0xFF4CAF50)
    }

    IconButton(onClick = onClick, modifier = Modifier.size(32.dp)) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .background(indicatorColor, CircleShape)
        )
    }
}

@Composable
fun HealthDetailsDialog(systemHealth: SystemHealth, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("System Health") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                systemHealth.brokers.forEach { (name, health) ->
                    BrokerHealthRow(health)
                }
                if (systemHealth.fearGreed != null) {
                    HorizontalDivider()
                    FearGreedRow(systemHealth.fearGreed)
                }
                HorizontalDivider()
                Text(
                    text = "Last updated: ${java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US).format(java.util.Date(systemHealth.lastUpdated))}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Close")
            }
        }
    )
}

@Composable
fun BrokerHealthRow(health: BrokerHealth) {
    val statusColor = when (health.status) {
        BrokerHealthStatus.ONLINE -> Color(0xFF4CAF50)
        BrokerHealthStatus.SLOW -> Color(0xFFFFD54F)
        BrokerHealthStatus.OFFLINE -> Color(0xFFE57373)
    }
    val statusLabel = when (health.status) {
        BrokerHealthStatus.ONLINE -> "Online"
        BrokerHealthStatus.SLOW -> "Slow"
        BrokerHealthStatus.OFFLINE -> "Offline"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = health.brokerName, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    text = "${health.latencyMs}ms · $statusLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (health.lastError != null) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Error",
                tint = Color(0xFFE57373),
                modifier = Modifier.size(16.dp)
            )
        }
    }
    if (health.lastError != null) {
        Text(
            text = health.lastError,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE57373),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp)
        )
    }
}

@Composable
fun FearGreedRow(health: FearGreedHealth) {
    val statusColor = when (health.status) {
        BrokerHealthStatus.ONLINE -> Color(0xFF4CAF50)
        BrokerHealthStatus.SLOW -> Color(0xFFFFD54F)
        BrokerHealthStatus.OFFLINE -> Color(0xFFE57373)
    }
    val statusLabel = when (health.status) {
        BrokerHealthStatus.ONLINE -> "Online"
        BrokerHealthStatus.SLOW -> "Slow"
        BrokerHealthStatus.OFFLINE -> "Offline"
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(statusColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column {
                Text(text = "Fear & Greed", style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                val valueText = health.value?.let { "$it/100" } ?: "N/A"
                Text(
                    text = "$valueText · ${health.latencyMs}ms · $statusLabel",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        if (health.lastError != null) {
            Icon(
                Icons.Default.Info,
                contentDescription = "Error",
                tint = Color(0xFFE57373),
                modifier = Modifier.size(16.dp)
            )
        }
    }
    if (health.lastError != null) {
        Text(
            text = health.lastError,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFFE57373),
            modifier = Modifier.padding(start = 18.dp, top = 2.dp)
        )
    }
}

@Composable
fun ErrorView(message: String, onRetry: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = "Error: $message",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error
            )
            Button(onClick = onRetry) {
                Text("Retry")
            }
        }
    }
}

package com.uscrooge.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.uscrooge.app.BuildConfig
import com.uscrooge.app.data.model.TradingConfig
import com.uscrooge.app.ui.viewmodel.SaveState
import com.uscrooge.app.ui.viewmodel.SettingsViewModel

@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val config by viewModel.config.collectAsState()
    val saveState by viewModel.saveState.collectAsState()

    var editedConfig by remember { mutableStateOf<TradingConfig?>(null) }

    LaunchedEffect(config) {
        if (editedConfig == null && config != null) {
            editedConfig = config
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Settings",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold
            )

            Row {
                TextButton(onClick = { viewModel.resetToDefaults() }) {
                    Text("Reset")
                }
                Button(
                    onClick = { editedConfig?.let { viewModel.updateConfig(it) } },
                    enabled = saveState !is SaveState.Saving
                ) {
                    if (saveState is SaveState.Saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Save")
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Save state feedback
        when (val state = saveState) {
            is SaveState.Success -> {
                Snackbar(
                    modifier = Modifier.padding(vertical = 8.dp),
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                ) {
                    Text(state.message)
                }
            }
            is SaveState.Warning -> {
                Snackbar(
                    modifier = Modifier.padding(vertical = 8.dp),
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(state.message)
                }
            }
            is SaveState.Error -> {
                Snackbar(
                    modifier = Modifier.padding(vertical = 8.dp),
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ) {
                    Text(state.message)
                }
            }
            else -> {}
        }

        Spacer(modifier = Modifier.height(8.dp))

        editedConfig?.let { currentConfig ->
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Appearance (now first)
                item {
                    SettingsSection(title = "Appearance") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SwitchSetting(
                                title = "Dark Mode",
                                checked = currentConfig.useDarkMode,
                                onCheckedChange = { checked ->
                                    editedConfig = currentConfig.copy(useDarkMode = checked)
                                }
                            )
                        }
                    }
                }

                // Risk Section
                item {
                    SettingsSection(title = "Risk Management") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = (currentConfig.riskPerTrade * 100).toString(),
                                onValueChange = { value ->
                                    value.toDoubleOrNull()?.let {
                                        editedConfig = currentConfig.copy(riskPerTrade = it / 100)
                                    }
                                },
                                label = { Text("Risk Per Trade (%)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = { Text("Percentage of available balance per trade") }
                            )

                            OutlinedTextField(
                                value = currentConfig.maxOpenPositions.toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let {
                                        editedConfig = currentConfig.copy(maxOpenPositions = it)
                                    }
                                },
                                label = { Text("Max Open Positions") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = currentConfig.maxDailyTrades.toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let {
                                        editedConfig = currentConfig.copy(maxDailyTrades = it)
                                    }
                                },
                                label = { Text("Max Daily Trades") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Strategy Parameters Section
                item {
                    SettingsSection(title = "Strategy Parameters") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = (currentConfig.minSignalStrength * 100).toString(),
                                onValueChange = { value ->
                                    value.toDoubleOrNull()?.let {
                                        editedConfig = currentConfig.copy(minSignalStrength = it / 100)
                                    }
                                },
                                label = { Text("Min Signal Strength (%)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = currentConfig.stopLossPercent.toString(),
                                onValueChange = { value ->
                                    value.toDoubleOrNull()?.let {
                                        editedConfig = currentConfig.copy(stopLossPercent = it)
                                    }
                                },
                                label = { Text("Stop Loss (%)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = currentConfig.takeProfitPercent.toString(),
                                onValueChange = { value ->
                                    value.toDoubleOrNull()?.let {
                                        editedConfig = currentConfig.copy(takeProfitPercent = it)
                                    }
                                },
                                label = { Text("Take Profit (%)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )

                            OutlinedTextField(
                                value = currentConfig.trailingStopPercent.toString(),
                                onValueChange = { value ->
                                    value.toDoubleOrNull()?.let {
                                        editedConfig = currentConfig.copy(trailingStopPercent = it)
                                    }
                                },
                                label = { Text("Trailing Stop (%)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Execution Settings
                item {
                    SettingsSection(title = "Execution") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Automatic Trading",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Execute orders without confirmation",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = currentConfig.automaticTrading,
                                    onCheckedChange = { checked ->
                                        editedConfig = currentConfig.copy(automaticTrading = checked)
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = (currentConfig.checkIntervalSeconds / 60).toString(),
                                onValueChange = { value ->
                                    value.toIntOrNull()?.let {
                                        editedConfig = currentConfig.copy(checkIntervalSeconds = it * 60)
                                    }
                                },
                                label = { Text("Check Interval (minutes)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                // Technical Analysis
                item {
                    SettingsSection(title = "Technical Analysis") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SwitchSetting(
                                title = "Multi-Timeframe Analysis",
                                checked = currentConfig.useMultiTimeframe,
                                onCheckedChange = { editedConfig = currentConfig.copy(useMultiTimeframe = it) }
                            )

                            SwitchSetting(
                                title = "Candlestick Patterns",
                                checked = currentConfig.useCandlestickPatterns,
                                onCheckedChange = { editedConfig = currentConfig.copy(useCandlestickPatterns = it) }
                            )

                            SwitchSetting(
                                title = "Volume Analysis",
                                checked = currentConfig.useVolumeAnalysis,
                                onCheckedChange = { editedConfig = currentConfig.copy(useVolumeAnalysis = it) }
                            )

                            SwitchSetting(
                                title = "Support/Resistance",
                                checked = currentConfig.useSupportResistance,
                                onCheckedChange = { editedConfig = currentConfig.copy(useSupportResistance = it) }
                            )
                        }
                    }
                }

                // Notifications
                item {
                    SettingsSection(title = "Notifications") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            SwitchSetting(
                                title = "Signal Notifications",
                                checked = currentConfig.notifyOnSignals,
                                onCheckedChange = { editedConfig = currentConfig.copy(notifyOnSignals = it) }
                            )

                            SwitchSetting(
                                title = "Execution Notifications",
                                checked = currentConfig.notifyOnExecution,
                                onCheckedChange = { editedConfig = currentConfig.copy(notifyOnExecution = it) }
                            )

                            SwitchSetting(
                                title = "Error Notifications",
                                checked = currentConfig.notifyOnErrors,
                                onCheckedChange = { editedConfig = currentConfig.copy(notifyOnErrors = it) }
                            )
                        }
                    }
                }

                // API Configuration - Kraken (Crypto)
                item {
                    SettingsSection(title = "Kraken API (Crypto)") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Enable Crypto Trading",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Trade crypto via Kraken",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = currentConfig.enableCryptoTrading,
                                    onCheckedChange = { checked ->
                                        editedConfig = currentConfig.copy(enableCryptoTrading = checked)
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = currentConfig.krakenApiKey,
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(krakenApiKey = value)
                                },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = currentConfig.krakenApiSecret,
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(krakenApiSecret = value)
                                },
                                label = { Text("API Secret") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = currentConfig.tradingPairs.joinToString(", "),
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(
                                        tradingPairs = value.split(",").map { it.trim() }
                                    )
                                },
                                label = { Text("Trading Pairs") },
                                placeholder = { Text("BTC/EUR, ETH/EUR, SOL/EUR") },
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = { Text("Comma-separated list") }
                            )

                            Text(
                                text = "Leave empty to disable crypto trading.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // Alpaca API Configuration
                item {
                    SettingsSection(title = "Alpaca API (Stocks)") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Enable Stock Trading",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Trade stocks via Alpaca Markets",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = currentConfig.enableStockTrading,
                                    onCheckedChange = { checked ->
                                        editedConfig = currentConfig.copy(enableStockTrading = checked)
                                    }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Paper Trading",
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.Medium
                                    )
                                    Text(
                                        text = "Use simulated money for testing",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = currentConfig.alpacaPaperTrading,
                                    onCheckedChange = { checked ->
                                        editedConfig = currentConfig.copy(alpacaPaperTrading = checked)
                                    }
                                )
                            }

                            OutlinedTextField(
                                value = currentConfig.alpacaApiKey,
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(alpacaApiKey = value)
                                },
                                label = { Text("API Key") },
                                modifier = Modifier.fillMaxWidth(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = currentConfig.alpacaApiSecret,
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(alpacaApiSecret = value)
                                },
                                label = { Text("API Secret") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                singleLine = true
                            )

                            OutlinedTextField(
                                value = currentConfig.stockTradingPairs.joinToString(", "),
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(
                                        stockTradingPairs = value.split(",").map { it.trim() }.filter { it.isNotBlank() }
                                    )
                                },
                                label = { Text("Stock Symbols") },
                                placeholder = { Text("AAPL/USD, MSFT/USD, GOOGL/USD") },
                                modifier = Modifier.fillMaxWidth(),
                                supportingText = { Text("Comma-separated list (symbol/USD)") }
                            )

                            Text(
                                text = "Leave API keys empty to disable stock trading.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                // GitHub Integration
                item {
                    SettingsSection(title = "GitHub Integration") {
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            OutlinedTextField(
                                value = currentConfig.githubToken,
                                onValueChange = { value ->
                                    editedConfig = currentConfig.copy(githubToken = value)
                                },
                                label = { Text("GitHub Token") },
                                modifier = Modifier.fillMaxWidth(),
                                visualTransformation = PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(
                                    capitalization = KeyboardCapitalization.None,
                                    autoCorrect = false,
                                    keyboardType = KeyboardType.Ascii
                                ),
                                singleLine = true,
                                supportingText = {
                                    Text("Personal Access Token for auto error reporting")
                                }
                            )
                            Text(
                                text = "Used to automatically notify the GitHub project of errors.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                item { Spacer(modifier = Modifier.height(24.dp)) }

                item {
                    Text(
                        text = "v${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                }

                item { Spacer(modifier = Modifier.height(32.dp)) }
            }
        }
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun SwitchSetting(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange
        )
    }
}

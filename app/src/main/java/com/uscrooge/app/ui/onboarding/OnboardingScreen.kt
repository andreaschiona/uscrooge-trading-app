package com.uscrooge.app.ui.onboarding

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.uscrooge.app.ui.viewmodel.OnboardingViewModel

private val AVAILABLE_CRYPTO_PAIRS = listOf(
    "BTC/EUR", "ETH/EUR", "SOL/EUR", "XRP/EUR",
    "ADA/EUR", "DOT/EUR", "LINK/EUR", "AVAX/EUR",
    "MATIC/EUR", "ATOM/EUR", "UNI/EUR", "LTC/EUR"
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OnboardingScreen(
    viewModel: OnboardingViewModel,
    onComplete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(state.isCompleted) {
        if (state.isCompleted) {
            onComplete()
        }
    }

    Scaffold(
        modifier = modifier,
        topBar = {
            if (state.currentStep > 0 && state.currentStep < 5) {
                TopAppBar(
                    title = { },
                    navigationIcon = {
                        TextButton(onClick = { viewModel.previousStep() }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Indietro")
                            Spacer(Modifier.width(4.dp))
                            Text("Indietro")
                        }
                    },
                    actions = {
                        TextButton(onClick = { viewModel.skipAndComplete() }) {
                            Text("Salta")
                        }
                    }
                )
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (state.currentStep < 5) {
                StepIndicator(
                    currentStep = state.currentStep,
                    totalSteps = 5,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            AnimatedContent(
                targetState = state.currentStep,
                transitionSpec = {
                    (fadeIn(tween(300)) togetherWith fadeOut(tween(300)))
                },
                label = "step_transition",
                modifier = Modifier.weight(1f)
            ) { step ->
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 24.dp)
                ) {
                    when (step) {
                        0 -> WelcomeStep(onGetStarted = { viewModel.nextStep() })
                        1 -> ApiKeysStep(
                            krakenApiKey = state.krakenApiKey,
                            krakenApiSecret = state.krakenApiSecret,
                            alpacaApiKey = state.alpacaApiKey,
                            alpacaApiSecret = state.alpacaApiSecret,
                            onKrakenKeyChange = viewModel::updateKrakenApiKey,
                            onKrakenSecretChange = viewModel::updateKrakenApiSecret,
                            onAlpacaKeyChange = viewModel::updateAlpacaApiKey,
                            onAlpacaSecretChange = viewModel::updateAlpacaApiSecret,
                            onNext = { viewModel.nextStep() },
                            onSkip = { viewModel.skipAndComplete() }
                        )
                        2 -> TradingModeStep(
                            paperTrading = state.alpacaPaperTrading,
                            onPaperTradingChange = viewModel::updatePaperTrading,
                            onNext = { viewModel.nextStep() }
                        )
                        3 -> PairsStep(
                            selectedPairs = state.selectedPairs,
                            availablePairs = AVAILABLE_CRYPTO_PAIRS,
                            onTogglePair = viewModel::togglePair,
                            onNext = { viewModel.nextStep() }
                        )
                        4 -> RiskStep(
                            riskPerTrade = state.riskPerTrade,
                            maxOpenPositions = state.maxOpenPositions,
                            stopLossPercent = state.stopLossPercent,
                            takeProfitPercent = state.takeProfitPercent,
                            onRiskChange = viewModel::updateRiskPerTrade,
                            onMaxPositionsChange = viewModel::updateMaxOpenPositions,
                            onStopLossChange = viewModel::updateStopLossPercent,
                            onTakeProfitChange = viewModel::updateTakeProfitPercent,
                            onNext = { viewModel.nextStep() }
                        )
                        5 -> CompleteStep(
                            isSaving = state.isSaving,
                            state = state,
                            onComplete = { viewModel.completeOnboarding() }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StepIndicator(currentStep: Int, totalSteps: Int, modifier: Modifier = Modifier) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        (0 until totalSteps).forEach { step ->
            val color = when {
                step < currentStep -> MaterialTheme.colorScheme.primary
                step == currentStep -> MaterialTheme.colorScheme.primary
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
            val isCurrent = step == currentStep
            val height = if (isCurrent) 10.dp else 6.dp

            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(height)
                    .padding(vertical = if (isCurrent) 0.dp else 2.dp)
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    shape = MaterialTheme.shapes.small,
                    color = color.copy(alpha = if (isCurrent) 1f else 0.4f),
                    tonalElevation = if (isCurrent) 2.dp else 0.dp
                ) {}
            }
        }
    }
}

@Composable
private fun WelcomeStep(onGetStarted: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector = Icons.Default.TrendingUp,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Benvenuto in\nUScrooge Trading Bot",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(16.dp))

        Text(
            text = "Il tuo assistente automatico per il trading di criptovalute e azioni. " +
                "Analizza i mercati, genera segnali basati su analisi tecnica avanzata " +
                "ed esegue operazioni in modo autonomo sui broker Kraken e Alpaca.",
            style = MaterialTheme.typography.bodyLarge,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(32.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                FeatureRow(
                    icon = Icons.Default.Security,
                    title = "Sicurezza",
                    description = "API keys crittografate con Android KeyStore"
                )
                Spacer(Modifier.height(12.dp))
                FeatureRow(
                    icon = Icons.Default.Analytics,
                    title = "Analisi Tecnica",
                    description = "RSI, MACD, Bollinger, Ichimoku, pattern e molto altro"
                )
                Spacer(Modifier.height(12.dp))
                FeatureRow(
                    icon = Icons.Default.AutoGraph,
                    title = "Trading Automatico",
                    description = "Esecuzione autonoma con stop-loss e take-profit"
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onGetStarted,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp)
        ) {
            Text("Inizia Configurazione", style = MaterialTheme.typography.titleMedium)
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun FeatureRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ApiKeysStep(
    krakenApiKey: String,
    krakenApiSecret: String,
    alpacaApiKey: String,
    alpacaApiSecret: String,
    onKrakenKeyChange: (String) -> Unit,
    onKrakenSecretChange: (String) -> Unit,
    onAlpacaKeyChange: (String) -> Unit,
    onAlpacaSecretChange: (String) -> Unit,
    onNext: () -> Unit,
    onSkip: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Configurazione API",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Inserisci le chiavi API per i broker che vuoi utilizzare. " +
                "Puoi saltare questo passaggio e configurarle in seguito dalle impostazioni.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Kraken section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.AccountBalance,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Kraken (Criptovalute)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = krakenApiKey,
                    onValueChange = onKrakenKeyChange,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii
                    )
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = krakenApiSecret,
                    onValueChange = onKrakenSecretChange,
                    label = { Text("API Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Lascia vuoto per disabilitare il trading crypto. Le chiavi vengono crittografate e salvate in modo sicuro.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(16.dp))

        // Alpaca section
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Storefront,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        text = "Alpaca (Azioni)",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = alpacaApiKey,
                    onValueChange = onAlpacaKeyChange,
                    label = { Text("API Key") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii
                    )
                )

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = alpacaApiSecret,
                    onValueChange = onAlpacaSecretChange,
                    label = { Text("API Secret") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.None,
                        autoCorrect = false,
                        keyboardType = KeyboardType.Ascii
                    )
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    text = "Lascia vuoto per disabilitare il trading di azioni.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            OutlinedButton(
                onClick = onSkip,
                modifier = Modifier.weight(1f)
            ) {
                Text("Salta")
            }
            Button(
                onClick = onNext,
                modifier = Modifier.weight(1f)
            ) {
                Text("Continua")
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun TradingModeStep(
    paperTrading: Boolean,
    onPaperTradingChange: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Modalità di Trading",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Scegli la modalità operativa. Puoi sempre cambiarla in seguito dalle impostazioni.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Paper Trading card
        Card(
            onClick = { onPaperTradingChange(true) },
            modifier = Modifier.fillMaxWidth(),
            border = if (paperTrading) BorderStroke(
                2.dp, MaterialTheme.colorScheme.primary
            ) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = paperTrading,
                    onClick = { onPaperTradingChange(true) }
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Science,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Paper Trading",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Usa denaro simulato per testare le strategie senza rischi. " +
                            "Ideale per principianti e per validare le performance.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // Real Trading card
        Card(
            onClick = { onPaperTradingChange(false) },
            modifier = Modifier.fillMaxWidth(),
            border = if (!paperTrading) BorderStroke(
                2.dp, MaterialTheme.colorScheme.primary
            ) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = !paperTrading,
                    onClick = { onPaperTradingChange(false) }
                )
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.AccountBalanceWallet,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = "Real Trading",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "Opera con denaro reale. Necessita di API keys configurate " +
                            "e un account broker finanziato.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Continua")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun PairsStep(
    selectedPairs: List<String>,
    availablePairs: List<String>,
    onTogglePair: (String) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Seleziona Trading Pairs",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Scegli le criptovalute che vuoi monitorare. " +
                "Puoi sempre modificarle dalle impostazioni.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(16.dp))

        availablePairs.chunked(2).forEach { rowPairs ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                rowPairs.forEach { pair ->
                    val isSelected = selectedPairs.contains(pair)
                    FilterChip(
                        selected = isSelected,
                        onClick = { onTogglePair(pair) },
                        label = { Text(pair) },
                        leadingIcon = if (isSelected) {
                            { Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                        } else null,
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowPairs.size == 1) {
                    Spacer(Modifier.weight(1f))
                }
            }
        }

        Spacer(Modifier.height(16.dp))

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Default.Info,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onTertiaryContainer
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    text = "Seleziona almeno un paio per iniziare. Il bot analizzerà " +
                        "solo i pair selezionati.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            enabled = selectedPairs.isNotEmpty()
        ) {
            Text("Continua")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RiskStep(
    riskPerTrade: Double,
    maxOpenPositions: Int,
    stopLossPercent: Double,
    takeProfitPercent: Double,
    onRiskChange: (Double) -> Unit,
    onMaxPositionsChange: (Int) -> Unit,
    onStopLossChange: (Double) -> Unit,
    onTakeProfitChange: (Double) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(Modifier.height(16.dp))

        Text(
            text = "Configurazione Rischio",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Imposta i parametri di rischio base. Puoi configurarli in dettaglio più tardi.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Risk per trade
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Rischio per Operazione",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${(riskPerTrade * 100).toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Slider(
                    value = (riskPerTrade * 100).toFloat(),
                    onValueChange = { onRiskChange((it / 100f).toDouble()) },
                    valueRange = 5f..50f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Percentuale del capitale disponibile da rischiare per ogni operazione. " +
                        "Valori consigliati: 10-25% per principianti, fino a 50% per esperti.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Max open positions
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Posizioni Massime",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "$maxOpenPositions",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Slider(
                    value = maxOpenPositions.toFloat(),
                    onValueChange = { onMaxPositionsChange(it.toInt()) },
                    valueRange = 1f..10f,
                    steps = 8,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Numero massimo di posizioni aperte contemporaneamente. " +
                        "Un numero più basso riduce il rischio complessivo.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Stop loss
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Stop Loss",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${stopLossPercent.toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }

                Spacer(Modifier.height(8.dp))

                Slider(
                    value = stopLossPercent.toFloat(),
                    onValueChange = { onStopLossChange(it.toDouble()) },
                    valueRange = 1f..10f,
                    steps = 17,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Perdita massima accettabile prima di chiudere automaticamente " +
                        "una posizione. Valori tipici: 2-5%.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        // Take profit
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Take Profit",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = "${takeProfitPercent.toInt()}%",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                Spacer(Modifier.height(8.dp))

                Slider(
                    value = takeProfitPercent.toFloat(),
                    onValueChange = { onTakeProfitChange(it.toDouble()) },
                    valueRange = 1f..20f,
                    steps = 18,
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Profitto target che chiude automaticamente la posizione. " +
                        "Un rapporto take-profit/stop-loss di 2:1 è raccomandato.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("Continua")
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun CompleteStep(
    isSaving: Boolean,
    state: com.uscrooge.app.ui.viewmodel.OnboardingState,
    onComplete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.weight(1f))

        Icon(
            imageVector = Icons.Default.CheckCircle,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )

        Spacer(Modifier.height(24.dp))

        Text(
            text = "Tutto pronto!",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold
        )

        Spacer(Modifier.height(8.dp))

        Text(
            text = "Ecco un riepilogo della tua configurazione:",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(24.dp))

        // Summary cards
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                SummaryRow(
                    label = "API Keys",
                    value = buildString {
                        val hasKraken = state.krakenApiKey.isNotBlank()
                        val hasAlpaca = state.alpacaApiKey.isNotBlank()
                        append(
                            when {
                                hasKraken && hasAlpaca -> "Kraken + Alpaca configurate"
                                hasKraken -> "Kraken configurata"
                                hasAlpaca -> "Alpaca configurata"
                                else -> "Nessuna (configurabile dopo)"
                            }
                        )
                    }
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    label = "Modalità",
                    value = if (state.alpacaPaperTrading) "Paper Trading (simulato)" else "Real Trading"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    label = "Trading Pairs",
                    value = state.selectedPairs.joinToString(", ")
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    label = "Rischio",
                    value = "${(state.riskPerTrade * 100).toInt()}% per operazione"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    label = "Posizioni massime",
                    value = "${state.maxOpenPositions}"
                )
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                SummaryRow(
                    label = "Stop Loss / Take Profit",
                    value = "${state.stopLossPercent.toInt()}% / ${state.takeProfitPercent.toInt()}%"
                )
            }
        }

        Spacer(Modifier.weight(1f))

        Button(
            onClick = onComplete,
            modifier = Modifier
                .fillMaxWidth()
                .height(52.dp),
            enabled = !isSaving
        ) {
            if (isSaving) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Icon(Icons.Default.RocketLaunch, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Inizia a fare trading!", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun SummaryRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.35f)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.65f)
        )
    }
}

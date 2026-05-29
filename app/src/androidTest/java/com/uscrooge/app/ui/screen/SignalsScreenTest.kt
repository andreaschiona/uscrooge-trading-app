package com.uscrooge.app.ui.screen

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.uscrooge.app.data.model.*
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.notification.NotificationHelper
import com.uscrooge.app.ui.viewmodel.ExecutionState
import com.uscrooge.app.ui.viewmodel.SignalsUiState
import com.uscrooge.app.ui.viewmodel.SignalsViewModel
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SignalsScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    private lateinit var viewModel: TestSignalsViewModel
    private val repository: TradingRepository = mockk(relaxed = true)
    private val orderExecutor: OrderExecutor = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @Test
    fun loadingStateShowsCircularProgressIndicator() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        viewModel._uiState.value = SignalsUiState.Loading
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithTag("CircularProgressIndicator").assertExists()
    }

    @Test
    fun emptySignalsShowsNoSignalsText() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        viewModel._uiState.value = SignalsUiState.Success(emptyList())
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("No signals available").assertExists()
    }

    @Test
    fun successStateShowsSignalsTitle() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        viewModel._uiState.value = SignalsUiState.Success(emptyList())
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Trading Signals").assertExists()
    }

    @Test
    fun pendingSignalShowsExecuteAndIgnoreButtons() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        val signal = createTestSignal()
        viewModel._uiState.value = SignalsUiState.Success(listOf(signal))
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Execute").assertExists()
        composeTestRule.onText("Ignore").assertExists()
    }

    @Test
    fun executedSignalDoesNotShowActionButtons() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        val signal = createTestSignal(status = SignalStatus.EXECUTED, executedAt = System.currentTimeMillis(), executedPrice = 50100.0, orderId = "test-order")
        viewModel._uiState.value = SignalsUiState.Success(listOf(signal))
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Execute").assertDoesNotExist()
    }

    @Test
    fun errorStateShowsErrorMessage() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        viewModel._uiState.value = SignalsUiState.Error("Failed to load")
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("Error: Failed to load").assertExists()
    }

    @Test
    fun signalPairAndTypeAreDisplayed() {
        viewModel = TestSignalsViewModel(repository, mockk(relaxed = true), orderExecutor, mockk(relaxed = true), mockk(relaxed = true))
        val signal = createTestSignal(pair = "BTC/EUR", type = SignalType.BUY)
        viewModel._uiState.value = SignalsUiState.Success(listOf(signal))
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onText("BTC/EUR").assertExists()
        composeTestRule.onText("BUY").assertExists()
    }

    private fun createTestSignal(
        pair: String = "BTC/EUR",
        type: SignalType = SignalType.BUY,
        strength: Double = 0.85,
        status: SignalStatus = SignalStatus.PENDING,
        executedAt: Long? = null,
        executedPrice: Double? = null,
        orderId: String? = null
    ): TradingSignal = TradingSignal(
        id = 1,
        pair = pair,
        type = type,
        strength = strength,
        currentPrice = 50000.0,
        suggestedPrice = 50100.0,
        stopLoss = 48500.0,
        takeProfit = 53000.0,
        suggestedAmount = 250.0,
        riskRewardRatio = 2.5,
        timestamp = System.currentTimeMillis(),
        reasons = """["RSI oversold","MACD bullish"]""",
        status = status,
        executedAt = executedAt,
        executedPrice = executedPrice,
        orderId = orderId
    )
}

class TestSignalsViewModel(
    repository: TradingRepository,
    configRepository: ConfigRepository,
    orderExecutor: OrderExecutor,
    gitHubIssueReporter: GitHubIssueReporter,
    notificationHelper: NotificationHelper
) : SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper) {
    val _uiState = MutableStateFlow<SignalsUiState>(SignalsUiState.Loading)
    override val uiState = _uiState
}

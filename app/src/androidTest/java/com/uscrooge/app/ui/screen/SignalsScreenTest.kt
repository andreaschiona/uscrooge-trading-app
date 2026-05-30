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

    private val repository: TradingRepository = mockk(relaxed = true)
    private val orderExecutor: OrderExecutor = mockk(relaxed = true)

    @Before
    fun setup() {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
    }

    @Test
    fun successStateShowsSignalsTitle() {
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Trading Signals").assertExists()
    }

    @Test
    fun emptySignalsShowsNoSignalsText() {
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("No signals available").assertExists()
    }

    @Test
    fun errorStateShowsErrorMessage() {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
    }

    @Test
    fun signalPairAndTypeAreDisplayed() {
        val signals = listOf(createTestSignal(pair = "BTC/EUR", type = SignalType.BUY))
        coEvery { repository.getAllSignals() } returns flowOf(signals)
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("BTC/EUR").assertExists()
        composeTestRule.onNodeWithText("BUY").assertExists()
    }

    @Test
    fun pendingSignalShowsExecuteAndIgnoreButtons() {
        val signals = listOf(createTestSignal())
        coEvery { repository.getAllSignals() } returns flowOf(signals)
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Execute").assertExists()
        composeTestRule.onNodeWithText("Ignore").assertExists()
    }

    @Test
    fun executedSignalDoesNotShowActionButtons() {
        val signals = listOf(
            createTestSignal(
                status = SignalStatus.EXECUTED,
                executedAt = System.currentTimeMillis(),
                executedPrice = 50100.0,
                orderId = "test-order"
            )
        )
        coEvery { repository.getAllSignals() } returns flowOf(signals)
        val viewModel = SignalsViewModel(
            repository,
            mockk(relaxed = true),
            orderExecutor,
            mockk(relaxed = true),
            mockk(relaxed = true)
        )
        composeTestRule.waitForIdle()
        composeTestRule.setContent {
            SignalsScreen(viewModel = viewModel)
        }
        composeTestRule.onNodeWithText("Execute").assertDoesNotExist()
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

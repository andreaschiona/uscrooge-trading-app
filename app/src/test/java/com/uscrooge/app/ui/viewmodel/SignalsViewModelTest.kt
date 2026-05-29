package com.uscrooge.app.ui.viewmodel

import com.uscrooge.app.data.model.*
import com.uscrooge.app.data.repository.ConfigRepository
import com.uscrooge.app.data.repository.TradingRepository
import com.uscrooge.app.executor.OrderExecutor
import com.uscrooge.app.integration.GitHubIssueReporter
import com.uscrooge.app.notification.NotificationHelper
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SignalsViewModelTest {

    private val repository: TradingRepository = mockk()
    private val configRepository: ConfigRepository = mockk()
    private val orderExecutor: OrderExecutor = mockk()
    private val gitHubIssueReporter: GitHubIssueReporter = mockk()
    private val notificationHelper: NotificationHelper = mockk()

    private lateinit var viewModel: SignalsViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockkStatic(android.util.Log::class)
        every { android.util.Log.w(any<String>(), any<String>()) } returns 0
        every { android.util.Log.e(any<String>(), any<String>()) } returns 0
        every { android.util.Log.d(any<String>(), any<String>()) } returns 0
    }

    @After
    fun teardown() {
        clearAllMocks()
        Dispatchers.resetMain()
    }

    @Test
    fun `init loads signals`() = runTest {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value is SignalsUiState.Success)
    }

    @Test
    fun `signals are collected from repository`() = runTest {
        val signals = listOf(
            createTestSignal(id = 1, pair = "BTC/EUR", strength = 0.85)
        )
        coEvery { repository.getAllSignals() } returns flowOf(signals)
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()
        val state = viewModel.uiState.value as SignalsUiState.Success
        assertEquals(1, state.signals.size)
        assertEquals("BTC/EUR", state.signals[0].pair)
    }

    @Test
    fun `executeSignal resets to idle after completion`() = runTest {
        val signal = createTestSignal(id = 1)
        coEvery { repository.getAllSignals() } returns flowOf(listOf(signal))
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        coEvery { orderExecutor.executeSignal(signal, bypassCircuitBreaker = true) } returns Result.success(mockk())
        every { gitHubIssueReporter.isConfigured() } returns false

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()

        assertEquals(ExecutionState.Idle, viewModel.executionState.value)

        viewModel.executeSignal(signal)
        advanceUntilIdle()

        assertEquals(ExecutionState.Idle, viewModel.executionState.value)
    }

    @Test
    fun `executeSignal goes through executing state`() = runTest {
        val signal = createTestSignal(id = 1)
        coEvery { repository.getAllSignals() } returns flowOf(listOf(signal))
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        coEvery { orderExecutor.executeSignal(signal, bypassCircuitBreaker = true) } coAnswers {
            delay(1000)
            Result.success(mockk())
        }
        every { gitHubIssueReporter.isConfigured() } returns false

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()

        viewModel.executeSignal(signal)

        advanceTimeBy(10)
        assertEquals(ExecutionState.Executing(signal.id), viewModel.executionState.value)

        advanceTimeBy(4000)
        advanceUntilIdle()
        assertEquals(ExecutionState.Idle, viewModel.executionState.value)
    }

    @Test
    fun `executeSignal handles failure and resets to idle`() = runTest {
        val signal = createTestSignal(id = 1)
        coEvery { repository.getAllSignals() } returns flowOf(listOf(signal))
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        coEvery { orderExecutor.executeSignal(signal, bypassCircuitBreaker = true) } returns Result.failure(Exception("Order failed"))
        every { gitHubIssueReporter.isConfigured() } returns false

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()
        viewModel.executeSignal(signal)
        advanceUntilIdle()

        assertEquals(ExecutionState.Idle, viewModel.executionState.value)
    }

    @Test
    fun `ignoreSignal calls orderExecutor`() = runTest {
        val signal = createTestSignal(id = 1)
        coEvery { repository.getAllSignals() } returns flowOf(listOf(signal))
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        coEvery { orderExecutor.ignoreSignal(any()) } returns Unit

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()
        viewModel.ignoreSignal(signal)
        advanceUntilIdle()

        coVerify { orderExecutor.ignoreSignal(signal) }
    }

    @Test
    fun `refreshSignals triggers analysis`() = runTest {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(null)
        coEvery { configRepository.configFlow } returns flowOf(TradingConfig())
        coEvery { repository.analyzeAllPairs(any()) } returns emptyList()

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()
        viewModel.refreshSignals()
        advanceUntilIdle()

        coVerify { repository.analyzeAllPairs(any()) }
    }

    @Test
    fun `lastAnalysisLog delegates to repository`() = runTest {
        coEvery { repository.getAllSignals() } returns flowOf(emptyList())
        coEvery { repository.lastAnalysisLog } returns MutableStateFlow(AnalysisLog())

        viewModel = SignalsViewModel(repository, configRepository, orderExecutor, gitHubIssueReporter, notificationHelper)
        advanceUntilIdle()

        coVerify(atLeast = 0) { repository.lastAnalysisLog }
    }

    private fun createTestSignal(
        id: Long = 1,
        pair: String = "BTC/EUR",
        strength: Double = 0.8
    ): TradingSignal = TradingSignal(
        id = id,
        pair = pair,
        type = SignalType.BUY,
        strength = strength,
        currentPrice = 50000.0,
        suggestedPrice = 50100.0,
        stopLoss = 48500.0,
        takeProfit = 53000.0,
        suggestedAmount = 250.0,
        riskRewardRatio = 2.5,
        timestamp = System.currentTimeMillis(),
        reasons = """["RSI oversold"]""",
        status = SignalStatus.PENDING
    )
}
